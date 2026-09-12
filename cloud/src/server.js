const http = require('node:http');
const crypto = require('node:crypto');
const fs = require('node:fs');
const path = require('node:path');
const { createPool } = require('./db');
const { enforceUserLimit, parseUserLimit } = require('./user-limits');
const {
  getPlatformLoginError,
  normalizeAppPlatform,
  planSupportsPlatform,
  restaurantSupportsPlatform,
} = require('./platform-access');
const QRCode = require('qrcode');

// Build a compact, standalone QR SVG string (EC level M) for a staff code.
function qrSvgFor(text, size = 150) {
  if (!text) return '';
  try {
    const qr = QRCode.create(String(text), { errorCorrectionLevel: 'M' });
    const n = qr.modules.size;
    const data = qr.modules.data;
    const quiet = 4;
    const dim = n + quiet * 2;
    let rects = '';
    for (let y = 0; y < n; y++) {
      for (let x = 0; x < n; x++) {
        if (data[y * n + x]) rects += `<rect x="${x + quiet}" y="${y + quiet}" width="1" height="1"/>`;
      }
    }
    return `<svg xmlns="http://www.w3.org/2000/svg" width="${size}" height="${size}" viewBox="0 0 ${dim} ${dim}" shape-rendering="crispEdges"><rect width="${dim}" height="${dim}" fill="#fff"/><g fill="#0b1220">${rects}</g></svg>`;
  } catch {
    return '';
  }
}

const PORT = Number(process.env.PORT || 8080);
const ADMIN_TOKEN = process.env.GI_CLOUD_ADMIN_TOKEN || '';
const CLIENT_TOKEN_SECRET = process.env.GI_CLIENT_TOKEN_SECRET || ADMIN_TOKEN || 'gi-pos-local-client-secret';
const OFFLINE_LICENSE_SECRET = process.env.GI_OFFLINE_LICENSE_SECRET || ADMIN_TOKEN || 'gi-pos-local-offline-secret';
const UPDATE_DIR = process.env.GI_UPDATE_DIR || path.join(__dirname, '..', 'updates', 'win');
const ANDROID_UPDATE_DIR = process.env.GI_ANDROID_UPDATE_DIR || path.join(__dirname, '..', 'updates', 'android');
const DEFAULT_PAIRING_MINUTES = 30;
const UNLIMITED_DEVICE_LIMIT = 999999;
const CLIENT_TOKEN_TTL_MS = 24 * 60 * 60 * 1000;
const STAFF_DIRECTORY_KEY = 'pos-staff-user-directory';
const POS_STAFF_USERS_KEY = 'pos-staff-users';
const DINING_TABLES_KEY = 'pos-dining-table-groups';
const STAFF_PIN_RESET_KEY = 'pos-staff-pin-reset-commands';
const MAIN_APP_DEVICE_NAME = 'Main App';
const MAX_UPDATE_UPLOAD_BYTES = Number(process.env.GI_MAX_UPDATE_UPLOAD_BYTES || 350 * 1024 * 1024);
const PUBLIC_BASE_URL = normalizePublicBaseUrl(process.env.GI_PUBLIC_BASE_URL || '');
const pool = createPool();

// ---------------------------------------------------------------------------
// Real-time sync channel (Server-Sent Events).
//
// Open SSE connections are tracked per restaurant so we can:
//   1. Broadcast a "changed" event the instant a device pushes new data, so
//      other devices pull immediately instead of waiting for a poll.
//   2. Push a "session-invalidated" event to a specific device the moment it is
//      logged out (e.g. another device signed in), so the ousted device signs
//      out instantly and securely.
// The channel is best-effort: if it drops, the client still falls back to a
// slow poll, so data reliability never depends on the socket staying open.
// ---------------------------------------------------------------------------
const REALTIME_HEARTBEAT_MS = 25_000;
const realtimeClients = new Map(); // restaurantId -> Set<{ deviceId, res }>

function realtimeRegister(restaurantId, deviceId, res) {
  let set = realtimeClients.get(restaurantId);
  if (!set) {
    set = new Set();
    realtimeClients.set(restaurantId, set);
  }
  const entry = { deviceId, res };
  set.add(entry);
  return () => {
    const current = realtimeClients.get(restaurantId);
    if (!current) return;
    current.delete(entry);
    if (current.size === 0) {
      realtimeClients.delete(restaurantId);
    }
  };
}

function realtimeSend(res, event, data) {
  try {
    res.write(`event: ${event}\n`);
    res.write(`data: ${JSON.stringify(data || {})}\n\n`);
    return true;
  } catch {
    return false;
  }
}

// Tell every device on a restaurant (optionally excluding the one that made the
// change) that new data is available so they pull right away.
function realtimeBroadcastChange(restaurantId, exceptDeviceId = null) {
  const set = realtimeClients.get(restaurantId);
  if (!set || set.size === 0) return;
  const payload = { at: new Date().toISOString() };
  for (const entry of set) {
    if (exceptDeviceId && entry.deviceId === exceptDeviceId) continue;
    realtimeSend(entry.res, 'changed', payload);
  }
}

// Tell specific device(s) their session was invalidated (logged out elsewhere)
// and close their stream so they sign out immediately.
function realtimeInvalidateDevices(restaurantId, deviceIds) {
  const set = realtimeClients.get(restaurantId);
  if (!set || set.size === 0) return;
  const ids = new Set((deviceIds || []).map((id) => String(id)));
  if (ids.size === 0) return;
  for (const entry of set) {
    if (!ids.has(String(entry.deviceId))) continue;
    realtimeSend(entry.res, 'session-invalidated', {
      reason: 'Signed in on another device',
    });
    try {
      entry.res.end();
    } catch {
      /* ignore */
    }
  }
}

const PLAN_CATALOG = [
  {
    id: 'premium',
    name: 'Premium',
    subtitle: 'Single counter cloud POS',
    maxDevices: 1,
    counterLabel: '1 counter only',
    localPos: false,
    platforms: ['windows'],
    deviceLimits: { windows: 1, android: 0 },
    capabilities: {
      billing: true, tables: true, menuManagement: true, customers: true, dues: true,
      reports: true, bluetoothPrinting: false, networkPrinting: true, cloudSync: true,
      localPosServer: false, qrOrdering: false,
    },
    features: [
      'One Windows billing counter',
      'Cloud backup and restore',
      'Client portal and app download',
      'Portal password and staff PIN reset',
    ],
  },
  {
    id: 'gold',
    name: 'Gold',
    subtitle: 'Local POS server plus cloud',
    maxDevices: UNLIMITED_DEVICE_LIMIT,
    counterLabel: 'Main PC plus local counters',
    localPos: true,
    platforms: ['windows'],
    deviceLimits: { windows: UNLIMITED_DEVICE_LIMIT, android: 0 },
    capabilities: {
      billing: true, tables: true, menuManagement: true, customers: true, dues: true,
      reports: true, bluetoothPrinting: false, networkPrinting: true, cloudSync: true,
      localPosServer: true, qrOrdering: true,
    },
    features: [
      'Main PC local POS server included',
      'Other PCs and mobile devices can work through Main PC on LAN',
      'Cloud backup and restore',
      'Client portal, app download, and staff PIN reset',
    ],
  },
  {
    id: 'offline',
    name: 'Offline',
    subtitle: 'Single PC local POS',
    maxDevices: 1,
    counterLabel: '1 offline PC',
    localPos: false,
    platforms: ['windows'],
    deviceLimits: { windows: 1, android: 0 },
    capabilities: {
      billing: true, tables: true, menuManagement: true, customers: true, dues: true,
      reports: true, bluetoothPrinting: false, networkPrinting: true, cloudSync: false,
      localPosServer: false, qrOrdering: false,
    },
    features: [
      'One Windows billing counter',
      'One-time cloud activation and restore',
      'Local SQLite billing after activation',
      'No cloud backup, local POS server, or QR order portal',
    ],
  },
  {
    id: 'android',
    name: 'Android',
    subtitle: 'Native mobile POS with offline billing',
    maxDevices: UNLIMITED_DEVICE_LIMIT,
    counterLabel: 'Owner plus staff phones',
    localPos: false,
    platforms: ['android'],
    // Multiple Android devices (owner + staff phones) can be active at the same
    // time so staff can each sign in on their own phone.
    deviceLimits: { windows: 0, android: UNLIMITED_DEVICE_LIMIT },
    capabilities: {
      billing: true, tables: true, menuManagement: true, customers: true, dues: true,
      reports: true, bluetoothPrinting: true, networkPrinting: true, cloudSync: true,
      localPosServer: false, qrOrdering: false,
    },
    features: [
      'Owner and multiple staff phones or tablets',
      'Offline-first SQLite billing after activation',
      'Bluetooth and network thermal printing',
      'Cloud backup and sync when internet is available',
      'Tables, menu, customers, dues, and reports',
    ],
  },
];
const PLAN_CATALOG_JSON = JSON.stringify(PLAN_CATALOG);
const PLAN_CARDS_HTML = PLAN_CATALOG.map(
  (plan) => `
    <article class="plan-card ${plan.id === 'gold' ? 'featured' : ''}">
      <h3>${plan.name}<span class="badge ${plan.id === 'gold' ? 'good' : ''}">${plan.counterLabel}</span></h3>
      <p>${plan.subtitle}</p>
      <div class="plan-meta">
        <span class="badge">${plan.platforms.map((platform) => platform === 'android' ? 'Android' : 'Windows').join(' + ')}</span>
        <span class="badge ${plan.localPos ? 'good' : 'warn'}">${plan.localPos ? 'Local POS included' : 'Local POS not included'}</span>
        <span class="badge">Yearly / custom expiry</span>
      </div>
      <ul>${plan.features.map((feature) => `<li>${feature}</li>`).join('')}</ul>
    </article>
  `,
).join('');

const server = http.createServer(async (request, response) => {
  try {
    const url = new URL(request.url || '/', `http://${request.headers.host || 'localhost'}`);

    if (request.method === 'OPTIONS') {
      sendJson(response, 204, {});
      return;
    }

    if (request.method === 'GET' && url.pathname === '/health') {
      sendJson(response, 200, { ok: true, service: 'gi-pos-cloud' });
      return;
    }

    if (request.method === 'GET' && url.pathname === '/api/v1/public/config') {
      sendJson(response, 200, getPublicConfig(request));
      return;
    }

    if (request.method === 'GET' && url.pathname === '/connect') {
      sendHtml(response, createConnectHtml(request));
      return;
    }

    if (request.method === 'GET' && url.pathname === '/') {
      sendHtml(response, HOME_HTML);
      return;
    }

    if (request.method === 'GET' && url.pathname === '/about') {
      sendHtml(response, ABOUT_HTML);
      return;
    }

    if (request.method === 'GET' && url.pathname === '/features') {
      sendHtml(response, FEATURES_HTML);
      return;
    }

    if (request.method === 'GET' && url.pathname === '/pricing') {
      sendHtml(response, PRICING_HTML);
      return;
    }

    if (request.method === 'GET' && url.pathname === '/contact') {
      response.writeHead(301, { Location: '/support' });
      response.end();
      return;
    }

    if (request.method === 'GET' && url.pathname === '/support') {
      sendHtml(response, SUPPORT_HTML);
      return;
    }

    if (request.method === 'GET' && url.pathname === '/status') {
      sendHtml(response, STATUS_HTML);
      return;
    }

    if (request.method === 'GET' && url.pathname === '/demo') {
      sendHtml(response, DEMO_HTML);
      return;
    }

    if (request.method === 'GET' && url.pathname === '/signup') {
      sendHtml(response, SIGNUP_HTML);
      return;
    }

    if (request.method === 'GET' && url.pathname === '/portal') {
      sendHtml(response, PORTAL_HTML);
      return;
    }

    if (request.method === 'GET' && url.pathname === '/admin') {
      sendHtml(response, ADMIN_HTML);
      return;
    }

    if (request.method === 'GET' && url.pathname === '/download/windows') {
      await serveWindowsDownload(response);
      return;
    }

    if (request.method === 'GET' && url.pathname === '/download/android') {
      await serveAndroidDownload(response);
      return;
    }

    if (request.method === 'GET' && url.pathname.startsWith('/updates/win/')) {
      await serveUpdateFile(response, url.pathname);
      return;
    }

    if (request.method === 'POST' && url.pathname === '/api/v1/signup') {
      await handleSignup(request, response);
      return;
    }

    if (request.method === 'POST' && url.pathname === '/api/v1/client/login') {
      await handleClientLogin(request, response);
      return;
    }

    if (request.method === 'POST' && url.pathname === '/api/v1/client/password/reset') {
      await handleClientPasswordReset(request, response);
      return;
    }

    if (request.method === 'GET' && url.pathname === '/api/v1/client/me') {
      await withClient(request, response, async (context) => handleClientMe(response, context));
      return;
    }

    if (request.method === 'POST' && url.pathname === '/api/v1/client/password/change') {
      await withClient(request, response, async (context) => handleClientPasswordChange(request, response, context));
      return;
    }

    if (request.method === 'POST' && url.pathname === '/api/v1/client/recovery-code') {
      await withClient(request, response, async (context) => handleClientRecoveryCode(response, context));
      return;
    }

    const clientPairingMatch = url.pathname.match(/^\/api\/v1\/client\/restaurants\/([^/]+)\/pairing-codes$/);
    if (request.method === 'POST' && clientPairingMatch) {
      await withClient(request, response, async (context) =>
        handleClientPairingCode(request, response, context, clientPairingMatch[1]),
      );
      return;
    }

    const clientDeviceActivateMatch = url.pathname.match(/^\/api\/v1\/client\/restaurants\/([^/]+)\/devices\/activate$/);
    if (request.method === 'POST' && clientDeviceActivateMatch) {
      await withClient(request, response, async (context) =>
        handleClientDeviceActivate(request, response, context, clientDeviceActivateMatch[1]),
      );
      return;
    }

    const clientStaffPinResetMatch = url.pathname.match(/^\/api\/v1\/client\/restaurants\/([^/]+)\/staff-pin-reset$/);
    if (request.method === 'POST' && clientStaffPinResetMatch) {
      await withClient(request, response, async (context) =>
        handleClientStaffPinReset(request, response, context, clientStaffPinResetMatch[1]),
      );
      return;
    }

    // Signed Devices (client portal): list every device linked to the account and
    // disable / re-enable / terminate a specific device.
    if (request.method === 'GET' && url.pathname === '/api/v1/client/devices') {
      await withClient(request, response, async (context) => handleClientDeviceList(response, context));
      return;
    }
    // Terminate + remove every active device on the account at once.
    if (request.method === 'POST' && url.pathname === '/api/v1/client/devices/terminate-all') {
      await withClient(request, response, async (context) => handleClientDeviceTerminateAll(response, context));
      return;
    }
    const clientDeviceActionMatch = url.pathname.match(/^\/api\/v1\/client\/devices\/([^/]+)\/(disable|enable|terminate)$/);
    if (request.method === 'POST' && clientDeviceActionMatch) {
      await withClient(request, response, async (context) =>
        handleClientDeviceAction(response, context, clientDeviceActionMatch[1], clientDeviceActionMatch[2]),
      );
      return;
    }
    // Device-authed self-rename: the phone pushes the friendly connection name set
    // in Settings so it shows on the Signed Devices page.
    if (request.method === 'POST' && url.pathname === '/api/v1/device/rename') {
      await withDevice(request, response, async (context) => handleDeviceRename(request, response, context));
      return;
    }

    if (request.method === 'POST' && url.pathname === '/api/v1/devices/register') {
      await handleDeviceRegister(request, response);
      return;
    }

    if (request.method === 'POST' && url.pathname === '/api/v1/devices/pair') {
      await handleDevicePair(request, response);
      return;
    }

    if (request.method === 'GET' && url.pathname === '/api/v1/admin/restaurants') {
      requireAdmin(request);
      await handleAdminRestaurants(response);
      return;
    }

    if (request.method === 'GET' && url.pathname === '/api/v1/admin/updates/windows') {
      requireAdmin(request);
      await handleAdminUpdateStatus(response);
      return;
    }

    if (request.method === 'POST' && url.pathname === '/api/v1/admin/updates/windows') {
      requireAdmin(request);
      await handleAdminUpdateUpload(request, response);
      return;
    }

    const approveMatch = url.pathname.match(/^\/api\/v1\/admin\/restaurants\/([^/]+)\/approve$/);
    if (request.method === 'POST' && approveMatch) {
      requireAdmin(request);
      await handleAdminApprove(request, response, approveMatch[1]);
      return;
    }

    const suspendMatch = url.pathname.match(/^\/api\/v1\/admin\/restaurants\/([^/]+)\/suspend$/);
    if (request.method === 'POST' && suspendMatch) {
      requireAdmin(request);
      await handleAdminSuspend(response, suspendMatch[1]);
      return;
    }

    const adminDeleteRestaurantMatch = url.pathname.match(/^\/api\/v1\/admin\/restaurants\/([^/]+)$/);
    if (request.method === 'DELETE' && adminDeleteRestaurantMatch) {
      requireAdmin(request);
      await handleAdminDeleteRestaurant(response, adminDeleteRestaurantMatch[1]);
      return;
    }

    const adminPasswordResetMatch = url.pathname.match(/^\/api\/v1\/admin\/restaurants\/([^/]+)\/password-reset$/);
    if (request.method === 'POST' && adminPasswordResetMatch) {
      requireAdmin(request);
      await handleAdminPasswordReset(request, response, adminPasswordResetMatch[1]);
      return;
    }

    const pairingMatch = url.pathname.match(/^\/api\/v1\/admin\/restaurants\/([^/]+)\/pairing-codes$/);
    if (request.method === 'POST' && pairingMatch) {
      requireAdmin(request);
      await handleAdminPairingCode(request, response, pairingMatch[1]);
      return;
    }

    const deviceStatusMatch = url.pathname.match(/^\/api\/v1\/admin\/devices\/([^/]+)\/status$/);
    if (request.method === 'POST' && deviceStatusMatch) {
      requireAdmin(request);
      await handleAdminDeviceStatus(request, response, deviceStatusMatch[1]);
      return;
    }

    if (request.method === 'GET' && url.pathname === '/api/v1/public/status') {
      await handlePublicStatus(response);
      return;
    }

    if (request.method === 'POST' && url.pathname === '/api/v1/support/message') {
      await handleSupportMessageCreate(request, response);
      return;
    }

    if (request.method === 'GET' && url.pathname === '/api/v1/admin/messages') {
      requireAdmin(request);
      await handleAdminMessages(response);
      return;
    }

    const messageUpdateMatch = url.pathname.match(/^\/api\/v1\/admin\/messages\/([^/]+)$/);
    if (request.method === 'POST' && messageUpdateMatch) {
      requireAdmin(request);
      await handleAdminMessageUpdate(request, response, messageUpdateMatch[1]);
      return;
    }

    const tablesMatch = url.pathname.match(/^\/api\/v1\/client\/restaurants\/([^/]+)\/tables$/);
    if (request.method === 'GET' && tablesMatch) {
      await withClient(request, response, async (context) => handleClientTables(response, context, tablesMatch[1]));
      return;
    }

    const reportsMatch = url.pathname.match(/^\/api\/v1\/client\/restaurants\/([^/]+)\/reports$/);
    if (request.method === 'GET' && reportsMatch) {
      await withClient(request, response, async (context) => handleClientReports(response, context, reportsMatch[1], url.searchParams));
      return;
    }

    const staffListMatch = url.pathname.match(/^\/api\/v1\/client\/restaurants\/([^/]+)\/staff$/);
    if (request.method === 'GET' && staffListMatch) {
      await withClient(request, response, async (context) => handleClientStaffList(response, context, staffListMatch[1]));
      return;
    }
    if (request.method === 'POST' && staffListMatch) {
      await withClient(request, response, async (context) => handleClientStaffCreate(request, response, context, staffListMatch[1]));
      return;
    }

    const staffItemMatch = url.pathname.match(/^\/api\/v1\/client\/restaurants\/([^/]+)\/staff\/([^/]+)$/);
    if (request.method === 'POST' && staffItemMatch) {
      await withClient(request, response, async (context) => handleClientStaffUpdate(request, response, context, staffItemMatch[1], staffItemMatch[2]));
      return;
    }
    if (request.method === 'DELETE' && staffItemMatch) {
      await withClient(request, response, async (context) => handleClientStaffDelete(response, context, staffItemMatch[1], staffItemMatch[2]));
      return;
    }

    // Device-authed staff management (GI POS mobile app "User Management").
    // The mobile app holds device credentials (x-restaurant-id / x-device-id / x-api-key),
    // not the owner client token, so staff CRUD for the app lives on device-authed routes.
    // Staff activation: a staff member signs in directly on a fresh device using the
    // credentials the owner created (no owner token). Verifies against portal_staff,
    // activates a device for their restaurant WITHOUT logging out other devices
    // (multiple staff phones can be active at once), and returns a full session +
    // the staff profile/permissions.
    if (request.method === 'POST' && url.pathname === '/api/v1/staff/activate') {
      await handleStaffActivate(request, response);
      return;
    }
    if (request.method === 'POST' && url.pathname === '/api/v1/device/staff/login') {
      await withDevice(request, response, async (context) => handleDeviceStaffLogin(request, response, context));
      return;
    }
    if (request.method === 'GET' && url.pathname === '/api/v1/device/staff') {
      await withDevice(request, response, async (context) => handleDeviceStaffList(response, context));
      return;
    }
    if (request.method === 'POST' && url.pathname === '/api/v1/device/staff') {
      await withDevice(request, response, async (context) => handleDeviceStaffCreate(request, response, context));
      return;
    }
    const deviceStaffItemMatch = url.pathname.match(/^\/api\/v1\/device\/staff\/([^/]+)$/);
    if (request.method === 'POST' && deviceStaffItemMatch) {
      await withDevice(request, response, async (context) => handleDeviceStaffUpdate(request, response, context, deviceStaffItemMatch[1]));
      return;
    }
    if (request.method === 'DELETE' && deviceStaffItemMatch) {
      await withDevice(request, response, async (context) => handleDeviceStaffDelete(response, context, deviceStaffItemMatch[1]));
      return;
    }

    if (request.method === 'POST' && url.pathname === '/api/v1/sync/push') {
      await withDevice(request, response, async (context) => handleSyncPush(request, response, context));
      return;
    }

    if (request.method === 'GET' && url.pathname === '/api/v1/sync/pull') {
      await withDevice(request, response, async (context) => handleSyncPull(response, context, url.searchParams));
      return;
    }

    if (request.method === 'GET' && url.pathname === '/api/v1/sync/stream') {
      await withDevice(request, response, async (context) => handleSyncStream(request, response, context));
      return;
    }

    sendJson(response, 404, { ok: false, error: 'Not found' });
  } catch (error) {
    sendJson(response, error.statusCode || 500, { ok: false, error: error.message || 'Server error' });
  }
});

async function handleSignup(request, response) {
  const body = await readJson(request);
  const businessName = String(body.businessName || '').trim();
  const ownerName = String(body.ownerName || '').trim();
  const phone = String(body.phone || '').trim();
  const email = String(body.email || '').trim().toLowerCase();
  const password = String(body.password || '');

  if (!businessName || !ownerName) {
    sendJson(response, 400, { ok: false, error: 'Business name and owner name are required' });
    return;
  }

  if (!phone && !email) {
    sendJson(response, 400, { ok: false, error: 'Phone or email is required' });
    return;
  }

  if (password.length < 6) {
    sendJson(response, 400, { ok: false, error: 'Password must be at least 6 characters' });
    return;
  }

  const client = await pool.connect();
  try {
    await client.query('BEGIN');
    const duplicate = await client.query(
      `
        SELECT id
        FROM accounts
        WHERE ($1 <> '' AND lower(email) = lower($1))
           OR ($2 <> '' AND phone = $2)
        LIMIT 1
      `,
      [email, phone],
    );

    if (duplicate.rows[0]) {
      await client.query('ROLLBACK');
      sendJson(response, 409, { ok: false, error: 'A signup already exists for this phone/email' });
      return;
    }

    const accountResult = await client.query(
      `
        INSERT INTO accounts (owner_name, phone, email, password_hash, status)
        VALUES ($1, $2, $3, $4, 'pending')
        RETURNING id, owner_name, phone, email, status, created_at
      `,
      [ownerName, phone, email, hashPassword(password)],
    );
    const account = accountResult.rows[0];
    const restaurantResult = await client.query(
      `
        INSERT INTO restaurants (account_id, name, owner_name, phone, email, status)
        VALUES ($1, $2, $3, $4, $5, 'pending')
        RETURNING id, name, owner_name, phone, email, status, created_at
      `,
      [account.id, businessName, ownerName, phone, email],
    );
    await client.query('COMMIT');

    sendJson(response, 201, {
      ok: true,
      message: 'Signup received. GI admin approval is required before connecting the POS app.',
      account,
      restaurant: restaurantResult.rows[0],
    });
  } catch (error) {
    await client.query('ROLLBACK');
    throw error;
  } finally {
    client.release();
  }
}

async function handleClientLogin(request, response) {
  const body = await readJson(request);
  const login = String(body.login || '').trim().toLowerCase();
  const password = String(body.password || '');
  const appPlatform = normalizeAppPlatform(body.appPlatform);

  if (!login || !password) {
    sendJson(response, 400, { ok: false, error: 'Login and password are required' });
    return;
  }

  const result = await pool.query(
    `
      SELECT id, owner_name, phone, email, password_hash, status, created_at
      FROM accounts
      WHERE lower(email) = lower($1) OR phone = $1
      LIMIT 1
    `,
    [login],
  );
  const account = result.rows[0];

  if (!account || !verifyPassword(password, account.password_hash)) {
    sendJson(response, 403, { ok: false, error: 'Invalid login or password' });
    return;
  }

  if (appPlatform) {
    const compatibleRestaurants = await getAccountRestaurants(account.id, appPlatform);
    if (compatibleRestaurants.length === 0) {
      sendJson(response, 403, { ok: false, error: getPlatformLoginError(appPlatform) });
      return;
    }
  }

  sendJson(response, 200, {
    ok: true,
    token: createClientToken(account.id, appPlatform),
    appPlatform,
    account: publicAccount(account),
  });
}

async function handleClientPasswordReset(request, response) {
  const body = await readJson(request);
  const login = String(body.login || '').trim().toLowerCase();
  const recoveryCode = normalizeRecoveryCode(String(body.recoveryCode || ''));
  const newPassword = String(body.newPassword || '');

  if (!login || !recoveryCode || !newPassword) {
    sendJson(response, 400, { ok: false, error: 'Login, recovery code, and new password are required' });
    return;
  }

  if (newPassword.length < 6) {
    sendJson(response, 400, { ok: false, error: 'Password must be at least 6 characters' });
    return;
  }

  const result = await pool.query(
    `
      SELECT id, recovery_code_hash
      FROM accounts
      WHERE lower(email) = lower($1) OR phone = $1
      LIMIT 1
    `,
    [login],
  );
  const account = result.rows[0];

  if (!account || !account.recovery_code_hash || account.recovery_code_hash !== hashSecret(recoveryCode)) {
    sendJson(response, 403, { ok: false, error: 'Invalid recovery code' });
    return;
  }

  await pool.query(
    `
      UPDATE accounts
      SET password_hash = $1,
          recovery_code_hash = '',
          recovery_code_set_at = NULL,
          updated_at = now()
      WHERE id = $2
    `,
    [hashPassword(newPassword), account.id],
  );

  sendJson(response, 200, { ok: true, message: 'Password reset successfully. Login with the new password.' });
}

async function handleClientMe(response, context) {
  sendJson(response, 200, {
    ok: true,
    account: publicAccount(context.account),
    restaurants: await getAccountRestaurants(context.account.id, context.appPlatform),
    planCatalog: PLAN_CATALOG,
    payment: {
      enabled: false,
      message: 'Online payment is not enabled yet. Subscription payment is handled manually by GI.',
    },
  });
}

async function handleClientPasswordChange(request, response, context) {
  const body = await readJson(request);
  const currentPassword = String(body.currentPassword || '');
  const newPassword = String(body.newPassword || '');

  if (!currentPassword || !newPassword) {
    sendJson(response, 400, { ok: false, error: 'Current password and new password are required' });
    return;
  }

  if (newPassword.length < 6) {
    sendJson(response, 400, { ok: false, error: 'Password must be at least 6 characters' });
    return;
  }

  if (!verifyPassword(currentPassword, context.account.password_hash)) {
    sendJson(response, 403, { ok: false, error: 'Current password is wrong' });
    return;
  }

  await pool.query('UPDATE accounts SET password_hash = $1, updated_at = now() WHERE id = $2', [
    hashPassword(newPassword),
    context.account.id,
  ]);

  sendJson(response, 200, { ok: true, message: 'Password changed successfully' });
}

async function handleClientRecoveryCode(response, context) {
  const code = createRecoveryCode();

  await pool.query(
    `
      UPDATE accounts
      SET recovery_code_hash = $1,
          recovery_code_set_at = now(),
          updated_at = now()
      WHERE id = $2
    `,
    [hashSecret(code), context.account.id],
  );

  sendJson(response, 201, {
    ok: true,
    code,
    message: 'Recovery code generated. Save it safely; it is shown only once.',
  });
}

async function handleClientPairingCode(request, response, context, restaurantId) {
  const body = await readJson(request);
  const deviceName = String(body.deviceName || MAIN_APP_DEVICE_NAME).trim();
  const restaurant = await getAccountRestaurant(context.account.id, restaurantId);

  if (!restaurant) {
    sendJson(response, 404, { ok: false, error: 'Restaurant not found for this account' });
    return;
  }

  await createPairingCodeForRestaurant(response, restaurantId, deviceName || MAIN_APP_DEVICE_NAME);
}

async function handleClientDeviceActivate(request, response, context, restaurantId) {
  const body = await readJson(request);
  const deviceName = String(body.deviceName || MAIN_APP_DEVICE_NAME).trim() || MAIN_APP_DEVICE_NAME;
  const devicePlatform = normalizeDevicePlatform(body.platform, deviceName);
  const transferCode = String(body.transferCode || '').replace(/\D/g, '');
  const deviceFingerprint = String(body.deviceFingerprint || '').trim();

  if (!context.appPlatform) {
    sendJson(response, 403, { ok: false, error: 'POS application login is required before activating a device' });
    return;
  }

  if (devicePlatform !== context.appPlatform) {
    sendJson(response, 403, { ok: false, error: 'The signed-in application platform does not match this device' });
    return;
  }

  if (transferCode && !/^\d{6}$/.test(transferCode)) {
    sendJson(response, 400, { ok: false, error: 'Valid 6 digit transfer code is required' });
    return;
  }

  const client = await pool.connect();

  try {
    await client.query('BEGIN');
    const restaurantResult = await client.query(
      `
        SELECT id, name, owner_name, phone, email, status
        FROM restaurants
        WHERE id = $1 AND account_id = $2
        LIMIT 1
      `,
      [restaurantId, context.account.id],
    );
    const restaurant = restaurantResult.rows[0];

    if (!restaurant) {
      await client.query('ROLLBACK');
      sendJson(response, 404, { ok: false, error: 'Restaurant not found for this account' });
      return;
    }

    if (restaurant.status !== 'approved') {
      await client.query('ROLLBACK');
      sendJson(response, 403, { ok: false, error: 'Restaurant account is not approved yet' });
      return;
    }

    const subscription = await getActiveSubscription(client, restaurantId);

    if (!subscription) {
      await client.query('ROLLBACK');
      sendJson(response, 402, { ok: false, error: 'Subscription is not active or expired' });
      return;
    }
    const decoratedSubscription = decorateSubscription(subscription);
    if (!decoratedSubscription.plan_platforms.includes(devicePlatform)) {
      await client.query('ROLLBACK');
      const requestedApp = devicePlatform === 'android' ? 'Android app' : 'Windows app';
      sendJson(response, 403, {
        ok: false,
        error: `${decoratedSubscription.plan_name} plan does not include the ${requestedApp}. Select a compatible plan in GI Cloud Admin.`,
      });
      return;
    }
    const activationMode = decoratedSubscription.plan_id === 'offline' ? 'offline' : 'cloud';

    let transferPairing = null;
    let loggedOutDevices = [];

    if (transferCode) {
      const transferResult = await client.query(
        `
          SELECT id, device_name, platform
          FROM pairing_codes
          WHERE restaurant_id = $1
            AND code_hash = $2
            AND used_at IS NULL
            AND expires_at > now()
          ORDER BY created_at DESC
          LIMIT 1
          FOR UPDATE
        `,
        [restaurantId, hashSecret(transferCode)],
      );
      transferPairing = transferResult.rows[0] || null;

      if (!transferPairing) {
        await client.query('ROLLBACK');
        sendJson(response, 403, { ok: false, error: 'Transfer code is invalid or expired' });
        return;
      }

      if (transferPairing.platform !== devicePlatform) {
        await client.query('ROLLBACK');
        sendJson(response, 403, { ok: false, error: `This transfer code is for ${transferPairing.platform}, not ${devicePlatform}` });
        return;
      }

      const logoutResult = await client.query(
        `
          UPDATE devices
          SET active = false, updated_at = now()
          WHERE restaurant_id = $1
            AND active = true
          RETURNING id, name
        `,
        [restaurantId],
      );
      loggedOutDevices = logoutResult.rows;
    }

    if (!transferPairing) {
      // Multiple Android devices (owner + staff phones) can be active at the same
      // time, so we no longer force-logout other Android devices on activation.
      // We only enforce an explicit plan device limit if one is configured.
      await enforcePlanDeviceLimit(client, restaurantId, decoratedSubscription, devicePlatform);
    }

    const apiKey = createApiKey();
    const deviceResult = await client.query(
      `
        INSERT INTO devices (restaurant_id, name, platform, api_key_hash)
        VALUES ($1, $2, $3, $4)
        RETURNING id, restaurant_id, name, platform, active, last_seen_at, created_at
      `,
      [restaurantId, deviceName, devicePlatform, hashSecret(apiKey)],
    );
    const device = deviceResult.rows[0];

    if (transferPairing) {
      await client.query('UPDATE pairing_codes SET used_at = now(), used_by_device = $1 WHERE id = $2', [
        device.id,
        transferPairing.id,
      ]);
    }

    const offlineLicense =
      activationMode === 'offline'
        ? createOfflineLicensePayload({
            id: subscription.id,
            licenseKey: `GI-${decoratedSubscription.plan_id.toUpperCase()}-${String(restaurant.id).slice(0, 8).toUpperCase()}`,
            businessName: restaurant.name,
            phone: restaurant.phone,
            deviceFingerprint: deviceFingerprint || String(device.id),
            deviceName: device.name,
            activatedAt: new Date().toISOString(),
            expiresAt: subscription.expires_at ? new Date(subscription.expires_at).toISOString() : '',
            subscription: decoratedSubscription,
          })
        : null;

    await client.query('COMMIT');

    // Real-time: instantly push a session-invalidated event to any device that
    // was just logged out so it signs out immediately instead of waiting for
    // its next sync/heartbeat.
    if (loggedOutDevices.length) {
      realtimeInvalidateDevices(
        restaurantId,
        loggedOutDevices.map((entry) => entry.id),
      );
    }

    sendJson(response, 201, {
      ok: true,
      restaurant,
      device,
      subscription: decoratedSubscription,
      apiKey,
      offlineLicense,
      transferApplied: Boolean(transferPairing),
      loggedOutDevices,
    });
  } catch (error) {
    await client.query('ROLLBACK');
    throw error;
  } finally {
    client.release();
  }
}

// ---------------------------------------------------------------------------
// Signed Devices (client portal). Owners see every device linked to their
// account and can disable, re-enable, or terminate any of them.
//   - disable:   set active=false. The device stops validating in withDevice on
//                its next request/stream tick, so it signs out shortly. Status
//                shows "Disabled" and it can be re-enabled later.
//   - enable:    set active=true again (re-activate a disabled device).
//   - terminate: set active=false AND immediately push a session-invalidated
//                event over the real-time channel so the device logs out at once
//                and its session ends now (no waiting for the next tick).
// All actions are scoped to devices whose restaurant belongs to this account.
// ---------------------------------------------------------------------------
async function handleClientDeviceList(response, context) {
  // Only list devices that are currently active (signed in / linked). Disabled or
  // terminated devices drop off the list.
  const result = await pool.query(
    `
      SELECT d.id, d.restaurant_id, d.name, d.platform, d.active, d.last_seen_at, d.created_at,
             r.name AS restaurant_name
      FROM devices d
      JOIN restaurants r ON r.id = d.restaurant_id
      WHERE r.account_id = $1 AND d.active = true
      ORDER BY d.created_at DESC
    `,
    [context.account.id],
  );
  const devices = result.rows.map((row) => ({
    id: row.id,
    serial: row.id,
    name: row.name || 'Device',
    platform: row.platform,
    active: row.active,
    status: row.active ? 'active' : 'disabled',
    lastSeenAt: row.last_seen_at,
    createdAt: row.created_at,
    restaurantId: row.restaurant_id,
    restaurantName: row.restaurant_name,
  }));
  sendJson(response, 200, { ok: true, devices });
}

async function handleClientDeviceAction(response, context, deviceId, action) {
  // Confirm the device exists and belongs to a restaurant under this account.
  const owned = await pool.query(
    `
      SELECT d.id, d.name, d.active, d.restaurant_id
      FROM devices d
      JOIN restaurants r ON r.id = d.restaurant_id
      WHERE d.id = $1 AND r.account_id = $2
      LIMIT 1
    `,
    [deviceId, context.account.id],
  );
  const device = owned.rows[0];
  if (!device) {
    sendJson(response, 404, { ok: false, error: 'Device not found for this account' });
    return;
  }

  if (action === 'enable') {
    // Re-enabling brings a device back online, so it must respect the plan the same
    // way registration/pairing do: the plan must still cover this platform and have
    // room under its active-device limit. Otherwise return a clear error instead of
    // letting the device get auto-disabled again on its next call.
    const detailResult = await pool.query('SELECT platform FROM devices WHERE id = $1 LIMIT 1', [deviceId]);
    const platform = detailResult.rows[0]?.platform || 'windows';
    const subscription = await getActiveSubscription(pool, device.restaurant_id);
    if (!subscription) {
      sendJson(response, 402, { ok: false, error: 'Subscription is not active or expired for this business.' });
      return;
    }
    const decorated = decorateSubscription(subscription);
    if (!decorated.plan_platforms.includes(platform)) {
      sendJson(response, 403, { ok: false, error: `${decorated.plan_name} plan does not include ${platform === 'android' ? 'Android' : 'Windows'} devices.` });
      return;
    }
    const client = await pool.connect();
    try {
      await client.query('BEGIN');
      try {
        await enforcePlanDeviceLimit(client, device.restaurant_id, decorated, platform);
      } catch (limitError) {
        await client.query('ROLLBACK');
        sendJson(response, 409, { ok: false, error: limitError.message || 'Device limit reached for this plan.' });
        return;
      }
      const updated = await client.query(
        'UPDATE devices SET active = true, updated_at = now() WHERE id = $1 RETURNING id, name, platform, active, last_seen_at, created_at, restaurant_id',
        [deviceId],
      );
      await client.query('COMMIT');
      sendJson(response, 200, { ok: true, action, device: publicDeviceRow(updated.rows[0]) });
    } catch (error) {
      await client.query('ROLLBACK');
      throw error;
    } finally {
      client.release();
    }
    return;
  }

  // disable + terminate both deactivate the device.
  const updated = await pool.query(
    'UPDATE devices SET active = false, updated_at = now() WHERE id = $1 RETURNING id, name, platform, active, last_seen_at, created_at, restaurant_id',
    [deviceId],
  );
  const row = updated.rows[0];

  // Terminate additionally pushes an instant session-invalidated event so the
  // device logs out immediately instead of on its next sync/heartbeat tick.
  if (action === 'terminate') {
    try { realtimeInvalidateDevices(device.restaurant_id, [deviceId]); } catch (ignored) { /* best-effort */ }
  }

  sendJson(response, 200, { ok: true, action, device: publicDeviceRow(row) });
}

function publicDeviceRow(row) {
  return {
    id: row.id,
    serial: row.id,
    name: row.name || 'Device',
    platform: row.platform,
    active: row.active,
    status: row.active ? 'active' : 'disabled',
    lastSeenAt: row.last_seen_at,
    createdAt: row.created_at,
    restaurantId: row.restaurant_id,
  };
}

// Terminate every active device on the account: deactivate them all and push an
// instant session-invalidated event to each so they log out immediately.
async function handleClientDeviceTerminateAll(response, context) {
  const updated = await pool.query(
    `
      UPDATE devices d
      SET active = false, updated_at = now()
      FROM restaurants r
      WHERE d.restaurant_id = r.id AND r.account_id = $1 AND d.active = true
      RETURNING d.id, d.restaurant_id
    `,
    [context.account.id],
  );
  // Group by restaurant so we push invalidation to each restaurant's stream set.
  const byRestaurant = new Map();
  for (const row of updated.rows) {
    const list = byRestaurant.get(row.restaurant_id) || [];
    list.push(row.id);
    byRestaurant.set(row.restaurant_id, list);
  }
  for (const [restaurantId, ids] of byRestaurant) {
    try { realtimeInvalidateDevices(restaurantId, ids); } catch (ignored) { /* best-effort */ }
  }
  sendJson(response, 200, { ok: true, terminated: updated.rows.length });
}

// Device self-rename: the mobile app sends the friendly connection name the user
// set in Settings so it appears on the portal's Signed Devices page.
async function handleDeviceRename(request, response, context) {
  const body = await readJson(request);
  const name = String(body.name || '').trim().slice(0, 80);
  if (!name) {
    sendJson(response, 400, { ok: false, error: 'Device name is required' });
    return;
  }
  await pool.query(
    'UPDATE devices SET name = $1, updated_at = now() WHERE id = $2 AND restaurant_id = $3',
    [name, context.deviceId, context.restaurantId],
  );
  sendJson(response, 200, { ok: true, name });
}

async function handleClientStaffPinReset(request, response, context, restaurantId) {
  const body = await readJson(request);
  const staffUserId = String(body.staffUserId || '').trim();
  const newPin = String(body.newPin || '');

  if (!staffUserId) {
    sendJson(response, 400, { ok: false, error: 'App user is required' });
    return;
  }

  if (!/^\d{4,8}$/.test(newPin)) {
    sendJson(response, 400, { ok: false, error: 'PIN must be 4 to 8 digits' });
    return;
  }

  const restaurant = await getAccountRestaurant(context.account.id, restaurantId);

  if (!restaurant) {
    sendJson(response, 404, { ok: false, error: 'Restaurant not found for this account' });
    return;
  }

  const directoryResult = await pool.query(
    'SELECT value FROM cloud_kv WHERE restaurant_id = $1 AND key = $2 LIMIT 1',
    [restaurantId, STAFF_DIRECTORY_KEY],
  );
  const staffUsers = normalizeStaffDirectory(directoryResult.rows[0]?.value);
  const staffUser = staffUsers.find((user) => user.id === staffUserId);

  if (!staffUser) {
    sendJson(response, 404, {
      ok: false,
      error: 'App user not found. Run cloud sync once from the desktop app, then refresh portal.',
    });
    return;
  }

  if (staffUser.active === false) {
    sendJson(response, 409, { ok: false, error: 'This app user is disabled in the desktop app' });
    return;
  }

  const pin = hashDesktopPin(newPin);
  const now = new Date().toISOString();
  const command = {
    id: crypto.randomUUID(),
    staffUserId,
    staffUserName: staffUser.name,
    pinSalt: pin.salt,
    pinHash: pin.hash,
    requestedAt: now,
    requestedBy: context.account.owner_name || context.account.phone || context.account.email || 'client portal',
  };
  const existingResult = await pool.query(
    'SELECT value FROM cloud_kv WHERE restaurant_id = $1 AND key = $2 LIMIT 1',
    [restaurantId, STAFF_PIN_RESET_KEY],
  );
  const existingCommands = Array.isArray(existingResult.rows[0]?.value) ? existingResult.rows[0].value : [];
  const nextCommands = [command, ...existingCommands].slice(0, 50);

  await pool.query(
    `
      INSERT INTO cloud_kv (restaurant_id, key, value, updated_by_device)
      VALUES ($1, $2, $3::jsonb, NULL)
      ON CONFLICT (restaurant_id, key) DO UPDATE SET
        value = excluded.value,
        updated_at = now(),
        updated_by_device = NULL
    `,
    [restaurantId, STAFF_PIN_RESET_KEY, JSON.stringify(nextCommands)],
  );

  sendJson(response, 201, {
    ok: true,
    message: `PIN reset sent for ${staffUser.name}. Run cloud sync in the desktop app to apply it.`,
    command: {
      id: command.id,
      staffUserId: command.staffUserId,
      staffUserName: command.staffUserName,
      requestedAt: command.requestedAt,
    },
  });
}

async function handleDeviceRegister(request, response) {
  requireAdmin(request);
  const body = await readJson(request);
  const restaurantName = String(body.restaurantName || body.businessName || '').trim();
  const deviceName = String(body.deviceName || MAIN_APP_DEVICE_NAME).trim();
  const ownerName = String(body.ownerName || '').trim();
  const phone = String(body.phone || '').trim();
  const email = String(body.email || '').trim().toLowerCase();
  const plan = getPlanDefinition(body.planName || 'Premium');
  const maxDevices = plan.maxDevices;
  const expiresAt = parseExpiryDate(body.expiresAt, 365);

  if (!restaurantName) {
    sendJson(response, 400, { ok: false, error: 'restaurantName is required' });
    return;
  }

  const apiKey = createApiKey();
  const apiKeyHash = hashSecret(apiKey);

  const client = await pool.connect();
  try {
    await client.query('BEGIN');
    const restaurantResult = await client.query(
      `
        INSERT INTO restaurants (name, owner_name, phone, email, status)
        VALUES ($1, $2, $3, $4, 'approved')
        RETURNING id, name, owner_name, phone, email, status, created_at
      `,
      [restaurantName, ownerName, phone, email],
    );
    const restaurant = restaurantResult.rows[0];
    const subscriptionResult = await client.query(
      `
        INSERT INTO subscriptions (restaurant_id, plan_name, status, starts_at, expires_at, max_devices)
        VALUES ($1, $2, 'active', now(), $3, $4)
        RETURNING id, plan_name, status, starts_at, expires_at, max_devices
      `,
      [restaurant.id, plan.name, expiresAt, maxDevices],
    );
    const deviceResult = await client.query(
      `
        INSERT INTO devices (restaurant_id, name, platform, api_key_hash)
        VALUES ($1, $2, $3, $4)
        RETURNING id, name, platform, active, created_at
      `,
      [restaurant.id, deviceName, plan.platforms[0], apiKeyHash],
    );
    await client.query('COMMIT');

    sendJson(response, 201, {
      ok: true,
      restaurant,
      device: deviceResult.rows[0],
      subscription: decorateSubscription(subscriptionResult.rows[0]),
      apiKey,
    });
  } catch (error) {
    await client.query('ROLLBACK');
    throw error;
  } finally {
    client.release();
  }
}

async function handleAdminRestaurants(response) {
  const restaurantsResult = await pool.query(`
    SELECT
      r.id,
      r.name,
      r.owner_name,
      r.phone,
      r.email,
      r.status,
      r.created_at,
      r.updated_at,
      a.id AS account_id,
      a.status AS account_status,
      s.id AS subscription_id,
      s.plan_name,
      s.status AS subscription_status,
      s.starts_at,
      s.expires_at,
      s.max_devices,
      s.max_users,
      COALESCE(ds.active_devices, 0)::int AS active_devices,
      COALESCE(ds.total_devices, 0)::int AS total_devices,
      COALESCE(us.active_users, 1)::int AS active_users
    FROM restaurants r
    LEFT JOIN accounts a ON a.id = r.account_id
    LEFT JOIN LATERAL (
      SELECT *
      FROM subscriptions latest_s
      WHERE latest_s.restaurant_id = r.id
      ORDER BY latest_s.created_at DESC
      LIMIT 1
    ) s ON true
    LEFT JOIN LATERAL (
      SELECT
        COUNT(*) FILTER (WHERE d.active)::int AS active_devices,
        COUNT(*)::int AS total_devices
      FROM devices d
      WHERE d.restaurant_id = r.id
    ) ds ON true
    LEFT JOIN LATERAL (
      SELECT GREATEST(
        1,
        COALESCE(COUNT(*) FILTER (WHERE COALESCE((entry->>'active')::boolean, true)), 0)
      )::int AS active_users
      FROM cloud_kv users_kv
      CROSS JOIN LATERAL jsonb_array_elements(
        CASE WHEN jsonb_typeof(users_kv.value) = 'array' THEN users_kv.value ELSE '[]'::jsonb END
      ) entry
      WHERE users_kv.restaurant_id = r.id AND users_kv.key = '${STAFF_DIRECTORY_KEY}'
    ) us ON true
    ORDER BY r.created_at DESC
    LIMIT 200
  `);
  const devicesResult = await pool.query(`
    SELECT id, restaurant_id, name, platform, active, last_seen_at, created_at
    FROM devices
    ORDER BY created_at DESC
    LIMIT 1000
  `);
  const devicesByRestaurant = new Map();

  for (const device of devicesResult.rows) {
    const list = devicesByRestaurant.get(device.restaurant_id) || [];
    list.push(device);
    devicesByRestaurant.set(device.restaurant_id, list);
  }

  sendJson(response, 200, {
    ok: true,
    restaurants: restaurantsResult.rows.map((restaurant) => ({
      ...decorateRestaurantPlanFields(restaurant),
      devices: devicesByRestaurant.get(restaurant.id) || [],
    })),
  });
}

async function handleAdminApprove(request, response, restaurantId) {
  const body = await readJson(request);
  const plan = getPlanDefinition(body.planName || 'Premium');
  const maxDevices = plan.maxDevices;
  const maxUsers = parseUserLimit(body.maxUsers);
  const expiresAt = parseExpiryDate(body.expiresAt, 365);

  const client = await pool.connect();
  try {
    await client.query('BEGIN');
    const restaurantResult = await client.query('SELECT * FROM restaurants WHERE id = $1 FOR UPDATE', [restaurantId]);
    const restaurant = restaurantResult.rows[0];

    if (!restaurant) {
      await client.query('ROLLBACK');
      sendJson(response, 404, { ok: false, error: 'Restaurant not found' });
      return;
    }

    await client.query("UPDATE restaurants SET status = 'approved', updated_at = now() WHERE id = $1", [restaurantId]);

    if (restaurant.account_id) {
      await client.query("UPDATE accounts SET status = 'approved', updated_at = now() WHERE id = $1", [restaurant.account_id]);
    }

    await client.query(
      "UPDATE subscriptions SET status = 'cancelled', updated_at = now() WHERE restaurant_id = $1 AND status IN ('trial', 'active')",
      [restaurantId],
    );
    const subscriptionResult = await client.query(
      `
        INSERT INTO subscriptions (restaurant_id, plan_name, status, starts_at, expires_at, max_devices, max_users)
        VALUES ($1, $2, 'active', now(), $3, $4, $5)
        RETURNING id, plan_name, status, starts_at, expires_at, max_devices, max_users
      `,
      [restaurantId, plan.name, expiresAt, maxDevices, maxUsers],
    );
    await client.query(
      `UPDATE devices SET active = false, updated_at = now() WHERE restaurant_id = $1 AND NOT (platform = ANY($2::text[]))`,
      [restaurantId, plan.platforms],
    );
    await client.query('COMMIT');

    sendJson(response, 200, { ok: true, subscription: decorateSubscription(subscriptionResult.rows[0]) });
  } catch (error) {
    await client.query('ROLLBACK');
    throw error;
  } finally {
    client.release();
  }
}

async function handleAdminSuspend(response, restaurantId) {
  const result = await pool.query(
    `
      UPDATE restaurants
      SET status = 'suspended', updated_at = now()
      WHERE id = $1
      RETURNING id, account_id, name, status
    `,
    [restaurantId],
  );
  const restaurant = result.rows[0];

  if (!restaurant) {
    sendJson(response, 404, { ok: false, error: 'Restaurant not found' });
    return;
  }

  if (restaurant.account_id) {
    await pool.query("UPDATE accounts SET status = 'suspended', updated_at = now() WHERE id = $1", [restaurant.account_id]);
  }

  sendJson(response, 200, { ok: true, restaurant });
}

async function handleAdminDeleteRestaurant(response, restaurantId) {
  const client = await pool.connect();
  try {
    await client.query('BEGIN');

    const lookup = await client.query(
      'SELECT id, account_id, name FROM restaurants WHERE id = $1 LIMIT 1',
      [restaurantId],
    );
    const restaurant = lookup.rows[0];

    if (!restaurant) {
      await client.query('ROLLBACK');
      sendJson(response, 404, { ok: false, error: 'Restaurant not found' });
      return;
    }

    // Child tables (devices, subscriptions, pairing_codes, cloud_kv, sync_events,
    // portal_staff) all reference restaurants(id) ON DELETE CASCADE, so this one
    // delete removes every associated record for the client.
    await client.query('DELETE FROM restaurants WHERE id = $1', [restaurantId]);

    // Remove the owning account too, but only when it has no other restaurants left.
    if (restaurant.account_id) {
      const remaining = await client.query(
        'SELECT 1 FROM restaurants WHERE account_id = $1 LIMIT 1',
        [restaurant.account_id],
      );
      if (remaining.rowCount === 0) {
        await client.query('DELETE FROM accounts WHERE id = $1', [restaurant.account_id]);
      }
    }

    await client.query('COMMIT');
    sendJson(response, 200, {
      ok: true,
      message: 'Client "' + restaurant.name + '" and all associated data were permanently deleted.',
      restaurant: { id: restaurant.id, name: restaurant.name },
    });
  } catch (error) {
    await client.query('ROLLBACK');
    throw error;
  } finally {
    client.release();
  }
}

async function handleAdminPasswordReset(request, response, restaurantId) {
  const body = await readJson(request);
  const newPassword = String(body.newPassword || '');

  if (newPassword.length < 6) {
    sendJson(response, 400, { ok: false, error: 'Password must be at least 6 characters' });
    return;
  }

  const restaurantResult = await pool.query(
    `
      SELECT
        r.id,
        r.name,
        r.account_id,
        a.owner_name,
        a.phone,
        a.email
      FROM restaurants r
      LEFT JOIN accounts a ON a.id = r.account_id
      WHERE r.id = $1
      LIMIT 1
    `,
    [restaurantId],
  );
  const restaurant = restaurantResult.rows[0];

  if (!restaurant) {
    sendJson(response, 404, { ok: false, error: 'Restaurant not found' });
    return;
  }

  if (!restaurant.account_id) {
    sendJson(response, 409, { ok: false, error: 'This restaurant has no client portal account' });
    return;
  }

  await pool.query(
    `
      UPDATE accounts
      SET password_hash = $1,
          recovery_code_hash = '',
          recovery_code_set_at = NULL,
          updated_at = now()
      WHERE id = $2
    `,
    [hashPassword(newPassword), restaurant.account_id],
  );

  sendJson(response, 200, {
    ok: true,
    message: 'Client portal password reset successfully. Ask the client to login and generate a new recovery code.',
    restaurant: {
      id: restaurant.id,
      name: restaurant.name,
    },
    account: {
      id: restaurant.account_id,
      ownerName: restaurant.owner_name,
      phone: restaurant.phone,
      email: restaurant.email,
    },
  });
}

async function handleAdminPairingCode(request, response, restaurantId) {
  const body = await readJson(request);
  const deviceName = String(body.deviceName || MAIN_APP_DEVICE_NAME).trim();
  await createPairingCodeForRestaurant(response, restaurantId, deviceName || MAIN_APP_DEVICE_NAME, body.expiresMinutes);
}

async function createPairingCodeForRestaurant(response, restaurantId, deviceName, expiresMinutesValue = DEFAULT_PAIRING_MINUTES) {
  const expiresMinutes = clampInteger(expiresMinutesValue, 5, 1440, DEFAULT_PAIRING_MINUTES);
  const expiresAt = new Date(Date.now() + expiresMinutes * 60_000);
  const client = await pool.connect();

  try {
    const restaurantResult = await client.query('SELECT id, name, status FROM restaurants WHERE id = $1', [restaurantId]);
    const restaurant = restaurantResult.rows[0];

    if (!restaurant) {
      sendJson(response, 404, { ok: false, error: 'Restaurant not found' });
      return;
    }

    if (restaurant.status !== 'approved') {
      sendJson(response, 403, { ok: false, error: 'Restaurant must be approved before device transfer' });
      return;
    }

    const subscription = await getActiveSubscription(client, restaurantId);
    if (!subscription) {
      sendJson(response, 402, { ok: false, error: 'No active subscription for this restaurant' });
      return;
    }
    const pairingPlatform = subscription.plan_platforms[0];

    const code = createPairingCode();
    const codeResult = await client.query(
      `
        INSERT INTO pairing_codes (restaurant_id, code_hash, device_name, platform, expires_at)
        VALUES ($1, $2, $3, $4, $5)
        RETURNING id, device_name, platform, expires_at, created_at
      `,
      [restaurantId, hashSecret(code), deviceName || MAIN_APP_DEVICE_NAME, pairingPlatform, expiresAt.toISOString()],
    );

    sendJson(response, 201, {
      ok: true,
      code,
      transferCode: code,
      pairingCode: codeResult.rows[0],
      restaurant,
      subscription,
    });
  } finally {
    client.release();
  }
}

async function handleAdminDeviceStatus(request, response, deviceId) {
  const body = await readJson(request);
  const active = Boolean(body.active);
  if (active) {
    const deviceResult = await pool.query('SELECT restaurant_id, platform FROM devices WHERE id = $1 LIMIT 1', [deviceId]);
    const device = deviceResult.rows[0];
    if (!device) {
      sendJson(response, 404, { ok: false, error: 'Device not found' });
      return;
    }
    const subscription = await getActiveSubscription(pool, device.restaurant_id);
    if (!subscription || !subscription.plan_platforms.includes(device.platform)) {
      sendJson(response, 403, { ok: false, error: 'This device platform is not included in the active subscription plan' });
      return;
    }
  }
  const result = await pool.query(
    `
      UPDATE devices
      SET active = $1, updated_at = now()
      WHERE id = $2
      RETURNING id, restaurant_id, name, platform, active, last_seen_at, created_at
    `,
    [active, deviceId],
  );

  if (!result.rows[0]) {
    sendJson(response, 404, { ok: false, error: 'Device not found' });
    return;
  }

  sendJson(response, 200, { ok: true, device: result.rows[0] });
}

// ---- Support / contact messages ----
async function handleSupportMessageCreate(request, response) {
  const body = await readJson(request);
  const name = String(body.name || '').trim().slice(0, 160);
  const phone = String(body.phone || '').trim().slice(0, 40);
  const email = String(body.email || '').trim().toLowerCase().slice(0, 200);
  const message = String(body.message || '').trim().slice(0, 4000);
  const source = String(body.source || 'support').trim().slice(0, 40) || 'support';

  if (!name) {
    sendJson(response, 400, { ok: false, error: 'Please enter your name' });
    return;
  }
  if (!phone && !email) {
    sendJson(response, 400, { ok: false, error: 'Please provide a phone or email so we can reply' });
    return;
  }

  const result = await pool.query(
    `
      INSERT INTO support_messages (name, phone, email, message, source, status)
      VALUES ($1, $2, $3, $4, $5, 'new')
      RETURNING id, created_at
    `,
    [name, phone, email, message, source],
  );

  sendJson(response, 201, {
    ok: true,
    message: 'Thanks! Your message was delivered to our team. We will reach out shortly.',
    id: result.rows[0].id,
  });
}

async function handleAdminMessages(response) {
  const result = await pool.query(
    `
      SELECT id, name, phone, email, message, source, status, admin_note, created_at, updated_at
      FROM support_messages
      ORDER BY created_at DESC
      LIMIT 500
    `,
  );
  const rows = result.rows;
  const counts = {
    total: rows.length,
    new: rows.filter((row) => row.status === 'new').length,
    read: rows.filter((row) => row.status === 'read').length,
    resolved: rows.filter((row) => row.status === 'resolved').length,
  };
  sendJson(response, 200, { ok: true, messages: rows, counts });
}

async function handleAdminMessageUpdate(request, response, messageId) {
  const body = await readJson(request);

  if (String(body.action || '') === 'delete') {
    const deleted = await pool.query('DELETE FROM support_messages WHERE id = $1 RETURNING id', [messageId]);
    if (!deleted.rows[0]) {
      sendJson(response, 404, { ok: false, error: 'Message not found' });
      return;
    }
    sendJson(response, 200, { ok: true, deleted: true });
    return;
  }

  const allowedStatus = ['new', 'read', 'resolved'];
  const status = allowedStatus.includes(String(body.status || '')) ? String(body.status) : null;
  const adminNote = body.adminNote == null ? null : String(body.adminNote).slice(0, 2000);

  const result = await pool.query(
    `
      UPDATE support_messages
      SET status = COALESCE($2, status),
          admin_note = COALESCE($3, admin_note),
          updated_at = now()
      WHERE id = $1
      RETURNING id, name, phone, email, message, source, status, admin_note, created_at, updated_at
    `,
    [messageId, status, adminNote],
  );

  if (!result.rows[0]) {
    sendJson(response, 404, { ok: false, error: 'Message not found' });
    return;
  }

  sendJson(response, 200, { ok: true, message: result.rows[0] });
}

// ---- Public cloud/service status ----
async function handlePublicStatus(response) {
  const services = [];
  const startedAt = Date.now();

  // Core API is responding because this handler ran.
  services.push({ key: 'api', name: 'Cloud API', description: 'Client & admin API gateway', status: 'operational' });

  // Database connectivity check.
  let dbStatus = 'operational';
  let dbDetail = 'Connected';
  try {
    await pool.query('SELECT 1');
  } catch (error) {
    dbStatus = 'outage';
    dbDetail = 'Connection failed';
  }
  services.push({ key: 'database', name: 'Database', description: 'Postgres primary datastore', status: dbStatus, detail: dbDetail });

  // Sync service shares the DB + API health.
  services.push({ key: 'sync', name: 'Sync Service', description: 'Device push/pull synchronization', status: dbStatus === 'operational' ? 'operational' : 'degraded' });

  // Download / update distribution.
  let winStatus = 'operational';
  try {
    if (!findLatestWindowsSetupFile()) winStatus = 'degraded';
  } catch { winStatus = 'degraded'; }
  services.push({ key: 'downloads', name: 'Software Downloads', description: 'Windows setup & Android APK delivery', status: winStatus, detail: winStatus === 'operational' ? 'Available' : 'Setup file not published' });

  const anyOutage = services.some((s) => s.status === 'outage');
  const anyDegraded = services.some((s) => s.status === 'degraded');
  const overall = anyOutage ? 'outage' : (anyDegraded ? 'degraded' : 'operational');

  sendJson(response, 200, {
    ok: true,
    overall,
    checkedAt: new Date().toISOString(),
    responseMs: Date.now() - startedAt,
    services,
  });
}

// ---- Client-managed staff (Users page) ----
async function loadRestaurantForClient(context, restaurantId) {
  const result = await pool.query(
    `
      SELECT r.id, r.name, s.max_users
      FROM restaurants r
      LEFT JOIN LATERAL (
        SELECT max_users FROM subscriptions ss
        WHERE ss.restaurant_id = r.id
        ORDER BY ss.created_at DESC LIMIT 1
      ) s ON true
      WHERE r.id = $1 AND r.account_id = $2
      LIMIT 1
    `,
    [restaurantId, context.account.id],
  );
  return result.rows[0] || null;
}

function publicStaff(row) {
  return {
    id: row.id,
    name: row.name,
    mobile: row.mobile,
    role: row.role,
    staffCode: row.staff_code || '',
    qrSvg: qrSvgFor(row.staff_code || ''),
    permissions: normalizeStringArray(row.permissions),
    tableAccess: normalizeStringArray(row.table_access),
    active: row.active,
    hasPassword: Boolean(row.password_hash),
    createdAt: row.created_at,
    updatedAt: row.updated_at,
  };
}

function normalizeStringArray(value) {
  let parsed = value;
  if (typeof parsed === 'string') {
    try {
      parsed = JSON.parse(parsed);
    } catch {
      parsed = [];
    }
  }
  if (!Array.isArray(parsed)) return [];
  return parsed
    .map((item) => String(item == null ? '' : item).trim().slice(0, 80))
    .filter(Boolean)
    .slice(0, 200);
}

function generateStaffCode() {
  // Human-friendly, scannable staff code, e.g. GS-7F3K9Q2A
  const alphabet = 'ABCDEFGHJKLMNPQRSTUVWXYZ23456789';
  const bytes = crypto.randomBytes(8);
  const body = Array.from(bytes, (byte) => alphabet[byte % alphabet.length]).join('');
  return `GS-${body}`;
}

function normalizeDiningTableGroups(value) {
  let parsed = value;
  if (typeof parsed === 'string') {
    try {
      parsed = JSON.parse(parsed);
    } catch {
      parsed = [];
    }
  }
  if (!Array.isArray(parsed)) return [];
  return parsed
    .filter((group) => group && typeof group === 'object')
    .map((group, index) => ({
      id: String(group.id || `area-${index + 1}`).trim() || `area-${index + 1}`,
      label: String(group.label || `Area ${index + 1}`).trim() || `Area ${index + 1}`,
      tables: (Array.isArray(group.tables) ? group.tables : [])
        .map((t) => String(t == null ? '' : t).trim().slice(0, 60))
        .filter(Boolean)
        .slice(0, 400),
    }))
    .filter((group) => group.tables.length)
    .slice(0, 60);
}

async function handleClientTables(response, context, restaurantId) {
  const restaurant = await loadRestaurantForClient(context, restaurantId);
  if (!restaurant) {
    sendJson(response, 404, { ok: false, error: 'Restaurant not found for this account' });
    return;
  }
  const result = await pool.query(
    'SELECT value, updated_at FROM cloud_kv WHERE restaurant_id = $1 AND key = $2 LIMIT 1',
    [restaurantId, DINING_TABLES_KEY],
  );
  const groups = normalizeDiningTableGroups(result.rows[0]?.value);
  sendJson(response, 200, {
    ok: true,
    synced: groups.length > 0,
    syncedAt: result.rows[0]?.updated_at || null,
    groups,
  });
}

const POS_ORDERS_KEY = 'pos-orders';
const POS_CATEGORIES_KEY = 'pos-categories';

// Parse a possibly-stringified JSONB value from cloud_kv into a JS value.
function parseCloudKvValue(raw, fallback) {
  if (raw == null) return fallback;
  if (typeof raw === 'string') {
    try {
      return JSON.parse(raw);
    } catch {
      return fallback;
    }
  }
  return raw;
}

function toFiniteNumber(value) {
  const n = Number(value);
  return Number.isFinite(n) ? n : 0;
}

function round2(value) {
  return Math.round((toFiniteNumber(value) + Number.EPSILON) * 100) / 100;
}

// Convert a GI POS mobile order (mobile-v1:order:*) into the shape the report
// aggregator expects (matching the desktop "pos-orders" entries): status,
// createdAt (ISO string), orderType, payment label, total, and a cart[] of
// {name, qty, price, category}. Returns null if the value isn't a usable order.
function normalizeMobileOrderForReport(order, productCategory) {
  if (!order || typeof order !== 'object') return null;
  const status = String(order.status || '');
  // Prefer the finalized time, then closed/updated; values are epoch millis.
  const millis = Number(order.finalizedAt || order.closedAt || order.updatedAt || order.openedAt || 0);
  const createdAt = millis > 0 ? new Date(millis).toISOString() : '';
  // Payment label from the payments array (cash/upi/card/due/gateway), else Cash.
  let paymentLabel = 'Cash';
  const payments = Array.isArray(order.payments) ? order.payments.filter((p) => p && Number(p.amount) > 0) : [];
  if (payments.length === 1) {
    const m = String(payments[0].method || '').toLowerCase();
    paymentLabel = m === 'upi' ? 'UPI' : m === 'card' ? 'Card' : m === 'due' ? 'Due' : m === 'gateway' ? 'Gateway' : 'Cash';
  } else if (payments.length > 1) {
    paymentLabel = 'Split';
  } else if (String(order.paymentStatus || '') === 'due') {
    paymentLabel = 'Due';
  }
  const typeMap = { dine_in: 'Dine-in', delivery: 'Delivery', takeaway: 'Takeaway' };
  const orderType = typeMap[String(order.orderType || 'dine_in')] || 'Dine-in';
  const items = Array.isArray(order.items) ? order.items : [];
  const cart = items.map((line) => ({
    name: String((line && line.name) || 'Item') + (line && line.variant ? ' / ' + line.variant : ''),
    qty: Number((line && line.quantity) || 0),
    price: Number((line && line.unitPrice) || 0),
    category: productCategory.get(String((line && line.productId) || '')) || '',
  }));
  return {
    billNo: order.billNumber != null ? String(order.billNumber) : '',
    status,
    createdAt,
    orderType,
    table: String(order.table || order.tableId || ''),
    customer: String(order.customerName || ''),
    paymentMethodLabel: paymentLabel,
    total: Number(order.total || 0),
    cart,
  };
}

// Reads the synced orders for a restaurant and returns a filtered, aggregated
// report. All filtering/aggregation runs server-side over the cloud_kv snapshot.
async function handleClientReports(response, context, restaurantId, searchParams) {
  const restaurant = await loadRestaurantForClient(context, restaurantId);
  if (!restaurant) {
    sendJson(response, 404, { ok: false, error: 'Restaurant not found for this account' });
    return;
  }

  const kv = await pool.query(
    'SELECT key, value, updated_at FROM cloud_kv WHERE restaurant_id = $1 AND key = ANY($2)',
    [restaurantId, [POS_ORDERS_KEY, POS_CATEGORIES_KEY]],
  );
  let ordersRaw = [];
  let categoriesRaw = [];
  let syncedAt = null;
  for (const row of kv.rows) {
    if (row.key === POS_ORDERS_KEY) {
      ordersRaw = parseCloudKvValue(row.value, []);
      syncedAt = row.updated_at || syncedAt;
    } else if (row.key === POS_CATEGORIES_KEY) {
      categoriesRaw = parseCloudKvValue(row.value, []);
    }
  }
  const orders = Array.isArray(ordersRaw) ? ordersRaw : [];
  const categories = Array.isArray(categoriesRaw) ? categoriesRaw : [];

  // The GI POS mobile app syncs each order/product/category as its own cloud_kv row
  // keyed "mobile-v1:<type>:<id>" (not the single desktop "pos-orders" array). Load
  // those rows and normalize them into the same report shape so mobile-app sales
  // show up in the portal reports exactly like desktop sales.
  const mobileRows = await pool.query(
    "SELECT key, value, updated_at FROM cloud_kv WHERE restaurant_id = $1 AND key LIKE 'mobile-v1:%'",
    [restaurantId],
  );
  const mobileCategoryLabels = new Map(); // categoryId -> label
  const mobileProductCategory = new Map(); // productId -> categoryId
  const mobileOrderValues = [];
  for (const row of mobileRows.rows) {
    const key = String(row.key || '');
    const value = parseCloudKvValue(row.value, null);
    if (!value || typeof value !== 'object') continue;
    if (key.startsWith('mobile-v1:order:')) {
      mobileOrderValues.push(value);
      if (row.updated_at && (!syncedAt || row.updated_at > syncedAt)) syncedAt = row.updated_at;
    } else if (key.startsWith('mobile-v1:category:')) {
      if (value.id) mobileCategoryLabels.set(String(value.id), String(value.name || value.id));
    } else if (key.startsWith('mobile-v1:product:')) {
      if (value.id) mobileProductCategory.set(String(value.id), String(value.categoryId || ''));
    }
  }
  // Merge normalized mobile orders into the report order list, and register mobile
  // categories so category breakdowns resolve to real names.
  for (const value of mobileOrderValues) {
    const normalized = normalizeMobileOrderForReport(value, mobileProductCategory);
    if (normalized) orders.push(normalized);
  }
  for (const [id, label] of mobileCategoryLabels) {
    if (!categories.some((c) => c && String(c.id) === id)) categories.push({ id, label });
  }

  // Category id -> display label map.
  const categoryLabels = new Map();
  for (const category of categories) {
    if (category && category.id) {
      categoryLabels.set(String(category.id), String(category.label || category.name || category.id));
    }
  }
  const categoryLabelOf = (id) => {
    const key = String(id || '').trim();
    if (!key) return 'Uncategorised';
    return categoryLabels.get(key) || key;
  };
  const paymentLabelOf = (order) => {
    const label = String(order.paymentMethodLabel || '').trim();
    return label || String(order.paymentMethod || 'Cash');
  };

  // Filters.
  const fromParam = searchParams.get('from') || '';
  const toParam = searchParams.get('to') || '';
  const typeFilter = String(searchParams.get('orderType') || 'all');
  const paymentFilter = String(searchParams.get('payment') || 'all');
  const categoryFilter = String(searchParams.get('category') || 'all');
  const fromTime = fromParam ? new Date(fromParam + 'T00:00:00').getTime() : null;
  const toTime = toParam ? new Date(toParam + 'T23:59:59.999').getTime() : null;

  // Only settled (paid) bills count towards sales. Cancelled/hold/unclosed are excluded.
  const paidOrders = orders.filter((order) => {
    if (!order || order.status !== 'paid') return false;
    const created = new Date(order.createdAt || 0).getTime();
    if (fromTime != null && created < fromTime) return false;
    if (toTime != null && created > toTime) return false;
    if (typeFilter !== 'all' && String(order.orderType || '') !== typeFilter) return false;
    if (paymentFilter !== 'all' && paymentLabelOf(order) !== paymentFilter) return false;
    if (categoryFilter !== 'all') {
      const cart = Array.isArray(order.cart) ? order.cart : [];
      const hasCategory = cart.some((line) => categoryLabelOf(line && line.category) === categoryFilter);
      if (!hasCategory) return false;
    }
    return true;
  });

  const byPayment = new Map();
  const byType = new Map();
  const byCategory = new Map();
  const itemMap = new Map();
  let totalSales = 0;

  for (const order of paidOrders) {
    const total = round2(order.totals && order.totals.total != null ? order.totals.total : order.total);
    totalSales = round2(totalSales + total);

    const pay = paymentLabelOf(order);
    const payEntry = byPayment.get(pay) || { method: pay, amount: 0, count: 0 };
    payEntry.amount = round2(payEntry.amount + total);
    payEntry.count += 1;
    byPayment.set(pay, payEntry);

    const type = String(order.orderType || 'Other');
    const typeEntry = byType.get(type) || { type, amount: 0, count: 0 };
    typeEntry.amount = round2(typeEntry.amount + total);
    typeEntry.count += 1;
    byType.set(type, typeEntry);

    const cart = Array.isArray(order.cart) ? order.cart : [];
    for (const line of cart) {
      if (!line) continue;
      const qty = toFiniteNumber(line.qty);
      const lineAmount = round2(toFiniteNumber(line.price) * qty);
      const catLabel = categoryLabelOf(line.category);
      const catEntry = byCategory.get(catLabel) || { category: catLabel, amount: 0, qty: 0 };
      catEntry.amount = round2(catEntry.amount + lineAmount);
      catEntry.qty += qty;
      byCategory.set(catLabel, catEntry);

      const itemName = String(line.name || 'Item');
      const itemEntry = itemMap.get(itemName) || { name: itemName, qty: 0, amount: 0 };
      itemEntry.qty += qty;
      itemEntry.amount = round2(itemEntry.amount + lineAmount);
      itemMap.set(itemName, itemEntry);
    }
  }

  // Distinct filter option lists (from ALL paid orders, unfiltered, so the UI can
  // offer every choice regardless of the current selection).
  const allPaid = orders.filter((order) => order && order.status === 'paid');
  const paymentOptions = Array.from(new Set(allPaid.map(paymentLabelOf))).filter(Boolean).sort();
  const typeOptions = Array.from(new Set(allPaid.map((order) => String(order.orderType || '')).filter(Boolean))).sort();
  const categoryOptions = Array.from(
    new Set(
      allPaid.flatMap((order) => (Array.isArray(order.cart) ? order.cart : []).map((line) => categoryLabelOf(line && line.category))),
    ),
  ).filter(Boolean).sort();

  const orderList = paidOrders
    .slice()
    .sort((a, b) => new Date(b.createdAt || 0) - new Date(a.createdAt || 0))
    .slice(0, 500)
    .map((order) => ({
      billNo: String(order.billNo || ''),
      createdAt: order.createdAt || '',
      orderType: String(order.orderType || ''),
      table: String(order.table || ''),
      customer: String(order.customer || ''),
      payment: paymentLabelOf(order),
      items: (Array.isArray(order.cart) ? order.cart : []).reduce((sum, line) => sum + toFiniteNumber(line && line.qty), 0),
      total: round2(order.totals && order.totals.total != null ? order.totals.total : order.total),
    }));

  sendJson(response, 200, {
    ok: true,
    syncedAt,
    hasData: orders.length > 0,
    summary: {
      totalSales,
      billCount: paidOrders.length,
      avgBill: paidOrders.length ? round2(totalSales / paidOrders.length) : 0,
      itemsSold: Array.from(itemMap.values()).reduce((sum, item) => sum + item.qty, 0),
    },
    byPayment: Array.from(byPayment.values()).sort((a, b) => b.amount - a.amount),
    byType: Array.from(byType.values()).sort((a, b) => b.amount - a.amount),
    byCategory: Array.from(byCategory.values()).sort((a, b) => b.amount - a.amount),
    topItems: Array.from(itemMap.values()).sort((a, b) => b.amount - a.amount).slice(0, 15),
    orders: orderList,
    options: {
      payments: paymentOptions,
      types: typeOptions,
      categories: categoryOptions,
    },
  });
}

// The POS/desktop capability permissions understood by the Windows app and LAN server.
const POS_CAPABILITY_PERMISSIONS = new Set([
  'pos_access', 'reports', 'business_profile', 'menu_manage', 'printer_manage',
  'cloud_sync', 'user_manage', 'expense_manage', 'discount_manage', 'due_manage', 'order_delete',
]);

// Maps the portal's free-form permission tokens onto POS capability permissions. Unknown tokens
// are ignored, and pos_access is always granted so the staff member can sign in to the POS/app.
function mapPortalPermissionsToPos(portalPermissions) {
  const source = normalizeStringArray(portalPermissions).map((permission) => String(permission).trim().toLowerCase());
  const mapped = new Set(['pos_access']);
  for (const permission of source) {
    if (POS_CAPABILITY_PERMISSIONS.has(permission)) {
      mapped.add(permission);
      continue;
    }
    // Friendly aliases the portal UI may use.
    if (permission === 'billing' || permission === 'pos' || permission === 'sales') mapped.add('pos_access');
    else if (permission === 'report' || permission === 'reports') mapped.add('reports');
    else if (permission === 'menu' || permission === 'items') mapped.add('menu_manage');
    else if (permission === 'discount' || permission === 'discounts') mapped.add('discount_manage');
    else if (permission === 'due' || permission === 'dues' || permission === 'credit') mapped.add('due_manage');
    else if (permission === 'expense' || permission === 'expenses') mapped.add('expense_manage');
    else if (permission === 'delete' || permission === 'order_delete') mapped.add('order_delete');
    else if (permission === 'printer' || permission === 'printers') mapped.add('printer_manage');
  }
  return Array.from(mapped);
}

// Projects a portal_staff row into the POS staff-user shape stored in cloud_kv (pos-staff-users).
// These are QR-only users: they sign in through the Android app by scanning their staff code, so
// they carry a staffCode and mobile device access, and have no desktop PIN.
function portalStaffToPosUser(row) {
  return {
    id: `portal-${row.id}`,
    name: String(row.name || 'Staff').trim() || 'Staff',
    staffCode: String(row.staff_code || '').trim().toUpperCase(),
    permissions: mapPortalPermissionsToPos(row.permissions),
    tableAccess: normalizeStringArray(row.table_access),
    deviceAccess: 'mobile',
    source: 'portal',
    active: row.active !== false,
    createdAt: row.created_at instanceof Date ? row.created_at.toISOString() : String(row.created_at || ''),
    updatedAt: row.updated_at instanceof Date ? row.updated_at.toISOString() : String(row.updated_at || new Date().toISOString()),
  };
}

// Bridge: rebuild pos-staff-users + pos-staff-user-directory in cloud_kv from portal_staff so the
// desktop app (and, through it, the Main PC LAN server) receives portal-created staff on next sync.
// Portal staff without a staff code are skipped (they cannot be used for QR login).
async function syncPortalStaffToKv(restaurantId) {
  const staffResult = await pool.query(
    'SELECT id, name, mobile, role, staff_code, permissions, table_access, active, created_at, updated_at FROM portal_staff WHERE restaurant_id = $1 ORDER BY created_at ASC',
    [restaurantId],
  );

  const posUsers = staffResult.rows
    .map(portalStaffToPosUser)
    .filter((user) => user.staffCode);

  const directory = posUsers.map((user) => ({
    id: user.id,
    name: user.name,
    active: user.active,
    updatedAt: user.updatedAt,
    lastLoginAt: '',
  }));

  const client = await pool.connect();
  try {
    await client.query('BEGIN');
    for (const [key, value] of [
      [POS_STAFF_USERS_KEY, posUsers],
      [STAFF_DIRECTORY_KEY, directory],
    ]) {
      await client.query(
        `
          INSERT INTO cloud_kv (restaurant_id, key, value, updated_by_device)
          VALUES ($1, $2, $3::jsonb, NULL)
          ON CONFLICT (restaurant_id, key) DO UPDATE SET
            value = excluded.value,
            updated_at = now(),
            updated_by_device = NULL
        `,
        [restaurantId, key, JSON.stringify(value)],
      );
    }
    await client.query('COMMIT');
  } catch (error) {
    await client.query('ROLLBACK');
    throw error;
  } finally {
    client.release();
  }

  // Nudge every connected device on this restaurant to pull right away so newly
  // created/updated staff (and their permissions/table access) appear in real time
  // instead of waiting for the next periodic sync.
  try { realtimeBroadcastChange(restaurantId); } catch (ignored) { /* best-effort */ }

  return posUsers;
}

// ---------------------------------------------------------------------------
// Device-authed staff management for the GI POS mobile app ("User Management").
// The Android app authenticates with device credentials (withDevice), so these
// endpoints let it create/list/update/delete portal_staff and log staff in
// against portal_staff.password_hash. All mutations call syncPortalStaffToKv so
// the staff directory syncs in real time to every device/desktop.
// ---------------------------------------------------------------------------

async function loadRestaurantSubscriptionLimits(restaurantId) {
  // max_users lives on the active subscription; mirror the client-portal behaviour.
  const subscription = await getActiveSubscription(pool, restaurantId);
  const maxUsers = subscription && subscription.max_users != null ? Number(subscription.max_users) : null;
  return { maxUsers };
}

async function handleDeviceStaffList(response, context) {
  const restaurantId = context.restaurantId;
  const staffResult = await pool.query(
    'SELECT id, name, mobile, role, staff_code, permissions, table_access, active, password_hash, created_at, updated_at FROM portal_staff WHERE restaurant_id = $1 ORDER BY created_at ASC',
    [restaurantId],
  );
  const activeCount = staffResult.rows.filter((row) => row.active).length;
  const { maxUsers } = await loadRestaurantSubscriptionLimits(restaurantId);
  sendJson(response, 200, {
    ok: true,
    maxUsers,
    activeCount,
    staff: staffResult.rows.map(publicStaff),
  });
}

async function handleDeviceStaffCreate(request, response, context) {
  const restaurantId = context.restaurantId;
  const body = await readJson(request);
  const name = String(body.name || '').trim().slice(0, 120);
  const mobile = String(body.mobile || '').trim().slice(0, 40);
  const role = String(body.role || 'Staff').trim().slice(0, 60) || 'Staff';
  const permissions = normalizeStringArray(body.permissions);
  const tableAccess = normalizeStringArray(body.tableAccess);
  const password = String(body.password || '');

  if (!name) {
    sendJson(response, 400, { ok: false, error: 'Staff name is required' });
    return;
  }
  if (!password || password.length < 4) {
    sendJson(response, 400, { ok: false, error: 'Password must be at least 4 characters' });
    return;
  }
  if (mobile) {
    const dupe = await pool.query(
      'SELECT id FROM portal_staff WHERE restaurant_id = $1 AND mobile = $2 LIMIT 1',
      [restaurantId, mobile],
    );
    if (dupe.rows[0]) {
      sendJson(response, 409, { ok: false, error: 'A staff member with this phone number already exists' });
      return;
    }
  }

  const { maxUsers } = await loadRestaurantSubscriptionLimits(restaurantId);
  if (maxUsers != null) {
    const countResult = await pool.query(
      'SELECT COUNT(*)::int AS n FROM portal_staff WHERE restaurant_id = $1 AND active = true',
      [restaurantId],
    );
    if (Number(countResult.rows[0].n) >= maxUsers) {
      sendJson(response, 409, { ok: false, error: `User limit reached. This license allows ${maxUsers} active user${maxUsers === 1 ? '' : 's'}.` });
      return;
    }
  }

  const staffCode = generateStaffCode();
  const result = await pool.query(
    `
      INSERT INTO portal_staff (restaurant_id, name, mobile, role, staff_code, permissions, table_access, password_hash, active)
      VALUES ($1, $2, $3, $4, $5, $6::jsonb, $7::jsonb, $8, true)
      RETURNING id, name, mobile, role, staff_code, permissions, table_access, active, password_hash, created_at, updated_at
    `,
    [restaurantId, name, mobile, role, staffCode, JSON.stringify(permissions), JSON.stringify(tableAccess), hashPassword(password)],
  );
  await syncPortalStaffToKv(restaurantId);
  sendJson(response, 201, { ok: true, staff: publicStaff(result.rows[0]) });
}

async function handleDeviceStaffUpdate(request, response, context, staffId) {
  const restaurantId = context.restaurantId;
  const body = await readJson(request);
  const existingResult = await pool.query(
    'SELECT id, active FROM portal_staff WHERE id = $1 AND restaurant_id = $2 LIMIT 1',
    [staffId, restaurantId],
  );
  const existing = existingResult.rows[0];
  if (!existing) {
    sendJson(response, 404, { ok: false, error: 'Staff member not found' });
    return;
  }

  const name = body.name == null ? null : (String(body.name).trim().slice(0, 120) || null);
  const mobile = body.mobile == null ? null : String(body.mobile).trim().slice(0, 40);
  const role = body.role == null ? null : (String(body.role).trim().slice(0, 60) || 'Staff');
  const permissions = body.permissions == null ? null : JSON.stringify(normalizeStringArray(body.permissions));
  const tableAccess = body.tableAccess == null ? null : JSON.stringify(normalizeStringArray(body.tableAccess));
  const active = body.active == null ? null : Boolean(body.active);
  const password = body.password == null ? '' : String(body.password);

  if (password && password.length < 4) {
    sendJson(response, 400, { ok: false, error: 'Password must be at least 4 characters' });
    return;
  }

  const { maxUsers } = await loadRestaurantSubscriptionLimits(restaurantId);
  if (maxUsers != null && active === true && existing.active === false) {
    const countResult = await pool.query(
      'SELECT COUNT(*)::int AS n FROM portal_staff WHERE restaurant_id = $1 AND active = true',
      [restaurantId],
    );
    if (Number(countResult.rows[0].n) >= maxUsers) {
      sendJson(response, 409, { ok: false, error: `User limit reached. This license allows ${maxUsers} active user${maxUsers === 1 ? '' : 's'}.` });
      return;
    }
  }

  const result = await pool.query(
    `
      UPDATE portal_staff
      SET name = COALESCE($3, name),
          mobile = COALESCE($4, mobile),
          role = COALESCE($5, role),
          active = COALESCE($6, active),
          permissions = COALESCE($9::jsonb, permissions),
          table_access = COALESCE($10::jsonb, table_access),
          staff_code = CASE WHEN staff_code = '' THEN $11 ELSE staff_code END,
          password_hash = CASE WHEN $7 <> '' THEN $8 ELSE password_hash END,
          updated_at = now()
      WHERE id = $1 AND restaurant_id = $2
      RETURNING id, name, mobile, role, staff_code, permissions, table_access, active, password_hash, created_at, updated_at
    `,
    [staffId, restaurantId, name, mobile, role, active, password, password ? hashPassword(password) : '', permissions, tableAccess, generateStaffCode()],
  );
  await syncPortalStaffToKv(restaurantId);
  sendJson(response, 200, { ok: true, staff: publicStaff(result.rows[0]) });
}

async function handleDeviceStaffDelete(response, context, staffId) {
  const restaurantId = context.restaurantId;
  const deleted = await pool.query(
    'DELETE FROM portal_staff WHERE id = $1 AND restaurant_id = $2 RETURNING id',
    [staffId, restaurantId],
  );
  if (!deleted.rows[0]) {
    sendJson(response, 404, { ok: false, error: 'Staff member not found' });
    return;
  }
  await syncPortalStaffToKv(restaurantId);
  sendJson(response, 200, { ok: true, deleted: true });
}

// Staff sign-in directly on a device (no owner token). The staff member enters the
// phone + password the owner created; we authenticate against portal_staff, then
// activate a device for their restaurant. Unlike owner activation, this does NOT log
// out other Android devices, so multiple staff phones can run at the same time.
async function handleStaffActivate(request, response) {
  const body = await readJson(request);
  const login = String(body.login || body.mobile || '').trim();
  const password = String(body.password || '');
  const deviceName = String(body.deviceName || 'GI POS Mobile (Staff)').trim() || 'GI POS Mobile (Staff)';
  const deviceFingerprint = String(body.deviceFingerprint || '').trim();

  if (!login || !password) {
    sendJson(response, 400, { ok: false, error: 'Phone number and password are required' });
    return;
  }

  // Find the staff by mobile or staff code. Join the restaurant so we can validate
  // approval + subscription. A staff phone/code is expected to be unique per account.
  const staffResult = await pool.query(
    `
      SELECT
        ps.id, ps.restaurant_id, ps.name, ps.mobile, ps.role, ps.staff_code,
        ps.permissions, ps.table_access, ps.active, ps.password_hash,
        ps.created_at, ps.updated_at,
        r.name AS restaurant_name, r.owner_name, r.phone, r.email, r.status AS restaurant_status
      FROM portal_staff ps
      JOIN restaurants r ON r.id = ps.restaurant_id
      WHERE ps.mobile = $1 OR upper(ps.staff_code) = upper($1)
      ORDER BY ps.created_at ASC
      LIMIT 1
    `,
    [login],
  );
  const staff = staffResult.rows[0];

  if (!staff || !staff.password_hash || !verifyPassword(password, staff.password_hash)) {
    sendJson(response, 403, { ok: false, error: 'Invalid phone number or password' });
    return;
  }
  if (staff.active === false) {
    sendJson(response, 403, { ok: false, error: 'This staff account is disabled. Contact the owner.' });
    return;
  }
  if (staff.restaurant_status !== 'approved') {
    sendJson(response, 403, { ok: false, error: 'This account is not approved yet. Contact the owner.' });
    return;
  }

  const restaurantId = staff.restaurant_id;
  const subscription = await getActiveSubscription(pool, restaurantId);
  if (!subscription) {
    sendJson(response, 402, { ok: false, error: 'Subscription is not active or expired. Contact the owner.' });
    return;
  }
  const decoratedSubscription = decorateSubscription(subscription);
  if (!decoratedSubscription.plan_platforms.includes('android')) {
    sendJson(response, 403, {
      ok: false,
      error: `${decoratedSubscription.plan_name} plan does not include the Android app. Contact the owner.`,
    });
    return;
  }

  // Activate a device for this staff member. Multiple staff devices are allowed, so
  // we do NOT deactivate other Android devices here. We still respect an explicit
  // plan device limit if one is set (unlimited by default).
  const apiKey = createApiKey();
  const client = await pool.connect();
  let device;
  try {
    await client.query('BEGIN');
    try {
      await enforcePlanDeviceLimit(client, restaurantId, decoratedSubscription, 'android');
    } catch (limitError) {
      await client.query('ROLLBACK');
      sendJson(response, 403, { ok: false, error: limitError.message || 'Device limit reached for this plan.' });
      return;
    }
    const deviceResult = await client.query(
      `
        INSERT INTO devices (restaurant_id, name, platform, api_key_hash)
        VALUES ($1, $2, 'android', $3)
        RETURNING id, restaurant_id, name, platform, active, created_at
      `,
      [restaurantId, deviceName, hashSecret(apiKey)],
    );
    device = deviceResult.rows[0];
    await client.query('COMMIT');
  } catch (error) {
    await client.query('ROLLBACK');
    throw error;
  } finally {
    client.release();
  }

  const restaurant = {
    id: restaurantId,
    name: staff.restaurant_name,
    owner_name: staff.owner_name,
    phone: staff.phone,
    email: staff.email,
    status: staff.restaurant_status,
    expires_at: subscription.expires_at || '',
  };

  sendJson(response, 201, {
    ok: true,
    restaurant,
    device,
    subscription: decoratedSubscription,
    apiKey,
    staff: publicStaff(staff),
  });
}

// Staff login from their own phones: authenticate against portal_staff.password_hash
// using their phone number (mobile) or staff code + password. Returns the staff
// profile (permissions + table access) the app applies for the session.
async function handleDeviceStaffLogin(request, response, context) {
  const restaurantId = context.restaurantId;
  const body = await readJson(request);
  const login = String(body.login || body.mobile || '').trim();
  const password = String(body.password || '');

  if (!login || !password) {
    sendJson(response, 400, { ok: false, error: 'Phone number and password are required' });
    return;
  }

  const result = await pool.query(
    `
      SELECT id, name, mobile, role, staff_code, permissions, table_access, active, password_hash, created_at, updated_at
      FROM portal_staff
      WHERE restaurant_id = $1 AND (mobile = $2 OR upper(staff_code) = upper($2))
      ORDER BY created_at ASC
      LIMIT 1
    `,
    [restaurantId, login],
  );
  const staff = result.rows[0];

  if (!staff || !staff.password_hash || !verifyPassword(password, staff.password_hash)) {
    sendJson(response, 403, { ok: false, error: 'Invalid phone number or password' });
    return;
  }
  if (staff.active === false) {
    sendJson(response, 403, { ok: false, error: 'This staff account is disabled. Contact the owner.' });
    return;
  }

  sendJson(response, 200, { ok: true, staff: publicStaff(staff) });
}

async function handleClientStaffList(response, context, restaurantId) {
  const restaurant = await loadRestaurantForClient(context, restaurantId);
  if (!restaurant) {
    sendJson(response, 404, { ok: false, error: 'Restaurant not found for this account' });
    return;
  }
  const staffResult = await pool.query(
    'SELECT id, name, mobile, role, staff_code, permissions, table_access, active, password_hash, created_at, updated_at FROM portal_staff WHERE restaurant_id = $1 ORDER BY created_at ASC',
    [restaurantId],
  );
  const activeCount = staffResult.rows.filter((row) => row.active).length;
  sendJson(response, 200, {
    ok: true,
    maxUsers: restaurant.max_users == null ? null : Number(restaurant.max_users),
    activeCount,
    staff: staffResult.rows.map(publicStaff),
  });
}

async function handleClientStaffCreate(request, response, context, restaurantId) {
  // Staff creation now lives in the GI POS Windows software (User Manage). The portal is view-only
  // for staff, so reject create attempts with a clear message.
  sendJson(response, 403, {
    ok: false,
    error: 'Staff are now created in the GI POS Windows software under User Manage. The client portal is view-only for staff.',
  });
  return;
  // eslint-disable-next-line no-unreachable
  const restaurant = await loadRestaurantForClient(context, restaurantId);
  if (!restaurant) {
    sendJson(response, 404, { ok: false, error: 'Restaurant not found for this account' });
    return;
  }
  const body = await readJson(request);
  const name = String(body.name || '').trim().slice(0, 120);
  const mobile = String(body.mobile || '').trim().slice(0, 40);
  const role = String(body.role || 'Staff').trim().slice(0, 60) || 'Staff';
  const permissions = normalizeStringArray(body.permissions);
  const tableAccess = normalizeStringArray(body.tableAccess);
  const password = String(body.password || '');

  if (!name) {
    sendJson(response, 400, { ok: false, error: 'Staff name is required' });
    return;
  }
  if (password && password.length < 4) {
    sendJson(response, 400, { ok: false, error: 'Password must be at least 4 characters' });
    return;
  }

  const maxUsers = restaurant.max_users == null ? null : Number(restaurant.max_users);
  if (maxUsers != null) {
    const countResult = await pool.query(
      'SELECT COUNT(*)::int AS n FROM portal_staff WHERE restaurant_id = $1 AND active = true',
      [restaurantId],
    );
    if (Number(countResult.rows[0].n) >= maxUsers) {
      sendJson(response, 409, { ok: false, error: `User limit reached. This license allows ${maxUsers} active user${maxUsers === 1 ? '' : 's'}.` });
      return;
    }
  }

  const staffCode = generateStaffCode();
  const result = await pool.query(
    `
      INSERT INTO portal_staff (restaurant_id, name, mobile, role, staff_code, permissions, table_access, password_hash, active)
      VALUES ($1, $2, $3, $4, $5, $6::jsonb, $7::jsonb, $8, true)
      RETURNING id, name, mobile, role, staff_code, permissions, table_access, active, password_hash, created_at, updated_at
    `,
    [restaurantId, name, mobile, role, staffCode, JSON.stringify(permissions), JSON.stringify(tableAccess), password ? hashPassword(password) : ''],
  );
  await syncPortalStaffToKv(restaurantId);
  sendJson(response, 201, { ok: true, staff: publicStaff(result.rows[0]) });
}

async function handleClientStaffUpdate(request, response, context, restaurantId, staffId) {
  const restaurant = await loadRestaurantForClient(context, restaurantId);
  if (!restaurant) {
    sendJson(response, 404, { ok: false, error: 'Restaurant not found for this account' });
    return;
  }
  const body = await readJson(request);
  const existingResult = await pool.query(
    'SELECT id, active FROM portal_staff WHERE id = $1 AND restaurant_id = $2 LIMIT 1',
    [staffId, restaurantId],
  );
  const existing = existingResult.rows[0];
  if (!existing) {
    sendJson(response, 404, { ok: false, error: 'Staff member not found' });
    return;
  }

  const name = body.name == null ? null : String(body.name).trim().slice(0, 120) || null;
  const mobile = body.mobile == null ? null : String(body.mobile).trim().slice(0, 40);
  const role = body.role == null ? null : (String(body.role).trim().slice(0, 60) || 'Staff');
  const permissions = body.permissions == null ? null : JSON.stringify(normalizeStringArray(body.permissions));
  const tableAccess = body.tableAccess == null ? null : JSON.stringify(normalizeStringArray(body.tableAccess));
  const active = body.active == null ? null : Boolean(body.active);
  const password = body.password == null ? '' : String(body.password);

  if (password && password.length < 4) {
    sendJson(response, 400, { ok: false, error: 'Password must be at least 4 characters' });
    return;
  }

  // Enforce user limit when re-activating.
  const maxUsers = restaurant.max_users == null ? null : Number(restaurant.max_users);
  if (maxUsers != null && active === true && existing.active === false) {
    const countResult = await pool.query(
      'SELECT COUNT(*)::int AS n FROM portal_staff WHERE restaurant_id = $1 AND active = true',
      [restaurantId],
    );
    if (Number(countResult.rows[0].n) >= maxUsers) {
      sendJson(response, 409, { ok: false, error: `User limit reached. This license allows ${maxUsers} active user${maxUsers === 1 ? '' : 's'}.` });
      return;
    }
  }

  const result = await pool.query(
    `
      UPDATE portal_staff
      SET name = COALESCE($3, name),
          mobile = COALESCE($4, mobile),
          role = COALESCE($5, role),
          active = COALESCE($6, active),
          permissions = COALESCE($9::jsonb, permissions),
          table_access = COALESCE($10::jsonb, table_access),
          staff_code = CASE WHEN staff_code = '' THEN $11 ELSE staff_code END,
          password_hash = CASE WHEN $7 <> '' THEN $8 ELSE password_hash END,
          updated_at = now()
      WHERE id = $1 AND restaurant_id = $2
      RETURNING id, name, mobile, role, staff_code, permissions, table_access, active, password_hash, created_at, updated_at
    `,
    [staffId, restaurantId, name, mobile, role, active, password, password ? hashPassword(password) : '', permissions, tableAccess, generateStaffCode()],
  );
  await syncPortalStaffToKv(restaurantId);
  sendJson(response, 200, { ok: true, staff: publicStaff(result.rows[0]) });
}

async function handleClientStaffDelete(response, context, restaurantId, staffId) {
  const restaurant = await loadRestaurantForClient(context, restaurantId);
  if (!restaurant) {
    sendJson(response, 404, { ok: false, error: 'Restaurant not found for this account' });
    return;
  }
  const deleted = await pool.query(
    'DELETE FROM portal_staff WHERE id = $1 AND restaurant_id = $2 RETURNING id',
    [staffId, restaurantId],
  );
  if (!deleted.rows[0]) {
    sendJson(response, 404, { ok: false, error: 'Staff member not found' });
    return;
  }
  await syncPortalStaffToKv(restaurantId);
  sendJson(response, 200, { ok: true, deleted: true });
}

async function handleAdminUpdateStatus(response) {
  sendJson(response, 200, { ok: true, updateDir: UPDATE_DIR, ...getUpdateStatusPayload() });
}

async function handleAdminUpdateUpload(request, response) {
  const form = await readMultipartFormData(request, MAX_UPDATE_UPLOAD_BYTES);
  const latestFile = getUploadedFile(form.files, 'latestYml');
  const setupFile = getUploadedFile(form.files, 'setupExe');
  const blockmapFile = getUploadedFile(form.files, 'setupBlockmap');

  if (!latestFile || !setupFile || !blockmapFile) {
    sendJson(response, 400, {
      ok: false,
      error: 'Select latest.yml, setup .exe, and setup .exe.blockmap before uploading.',
    });
    return;
  }

  const latestName = 'latest.yml';
  const setupName = sanitizeUpdateFileName(setupFile.filename);
  const blockmapName = sanitizeUpdateFileName(blockmapFile.filename);

  if (!/\.ya?ml$/i.test(latestFile.filename) && latestFile.filename !== latestName) {
    sendJson(response, 400, { ok: false, error: 'Version file must be latest.yml.' });
    return;
  }

  if (!/\.exe$/i.test(setupName) || !/setup/i.test(setupName)) {
    sendJson(response, 400, { ok: false, error: 'Setup file must be a Windows Setup .exe file.' });
    return;
  }

  if (!/\.exe\.blockmap$/i.test(blockmapName)) {
    sendJson(response, 400, { ok: false, error: 'Blockmap file must end with .exe.blockmap.' });
    return;
  }

  if (blockmapName !== `${setupName}.blockmap`) {
    sendJson(response, 400, {
      ok: false,
      error: `Blockmap filename must match the setup file: ${setupName}.blockmap`,
    });
    return;
  }

  const latestContent = latestFile.data.toString('utf8');
  const manifestSetupName = getSetupFileNameFromLatestContent(latestContent);

  if (!manifestSetupName) {
    sendJson(response, 400, { ok: false, error: 'latest.yml must contain the setup exe path or url.' });
    return;
  }

  if (manifestSetupName !== setupName) {
    sendJson(response, 400, {
      ok: false,
      error: `latest.yml points to ${manifestSetupName}, but selected setup file is ${setupName}.`,
    });
    return;
  }

  fs.mkdirSync(UPDATE_DIR, { recursive: true });

  const uploads = [
    { name: latestName, data: latestFile.data },
    { name: setupName, data: setupFile.data },
    { name: blockmapName, data: blockmapFile.data },
  ];
  const tempFiles = uploads.map((file) => ({
    ...file,
    tempPath: path.join(UPDATE_DIR, `${file.name}.upload-${Date.now()}-${crypto.randomBytes(4).toString('hex')}.tmp`),
    finalPath: path.join(UPDATE_DIR, file.name),
  }));

  try {
    for (const file of tempFiles) {
      fs.writeFileSync(file.tempPath, file.data);
    }

    for (const file of tempFiles) {
      fs.renameSync(file.tempPath, file.finalPath);
    }

    cleanupOldUpdateFiles(new Set(uploads.map((file) => file.name)));
  } finally {
    for (const file of tempFiles) {
      if (fs.existsSync(file.tempPath)) {
        fs.rmSync(file.tempPath, { force: true });
      }
    }
  }

  sendJson(response, 200, {
    ok: true,
    message: 'Windows app update files uploaded successfully.',
    updateDir: UPDATE_DIR,
    ...getUpdateStatusPayload(),
  });
}

async function handleDevicePair(request, response) {
  const body = await readJson(request);
  const code = String(body.code || '').replace(/\D/g, '');
  const deviceName = String(body.deviceName || MAIN_APP_DEVICE_NAME).trim();
  const devicePlatform = normalizeDevicePlatform(body.platform, deviceName);

  if (!/^\d{6}$/.test(code)) {
    sendJson(response, 400, { ok: false, error: 'Valid 6 digit pairing code is required' });
    return;
  }

  const apiKey = createApiKey();
  const client = await pool.connect();
  try {
    await client.query('BEGIN');
    const pairingResult = await client.query(
      `
        SELECT
          pc.id AS pairing_id,
          pc.restaurant_id,
          pc.device_name,
          pc.platform,
          r.name AS restaurant_name,
          r.status AS restaurant_status
        FROM pairing_codes pc
        JOIN restaurants r ON r.id = pc.restaurant_id
        WHERE pc.code_hash = $1
          AND pc.used_at IS NULL
          AND pc.expires_at > now()
        ORDER BY pc.created_at DESC
        LIMIT 1
        FOR UPDATE OF pc
      `,
      [hashSecret(code)],
    );
    const pairing = pairingResult.rows[0];

    if (!pairing) {
      await client.query('ROLLBACK');
      sendJson(response, 404, { ok: false, error: 'Pairing code is invalid or expired' });
      return;
    }

    if (pairing.restaurant_status !== 'approved') {
      await client.query('ROLLBACK');
      sendJson(response, 403, { ok: false, error: 'Restaurant is not approved' });
      return;
    }

    if (pairing.platform !== devicePlatform) {
      await client.query('ROLLBACK');
      sendJson(response, 403, { ok: false, error: `This pairing code is for ${pairing.platform}, not ${devicePlatform}` });
      return;
    }

    const subscription = await getActiveSubscription(client, pairing.restaurant_id);
    if (!subscription) {
      await client.query('ROLLBACK');
      sendJson(response, 402, { ok: false, error: 'Subscription is not active' });
      return;
    }

    if (!subscription.plan_platforms.includes(devicePlatform)) {
      await client.query('ROLLBACK');
      sendJson(response, 403, { ok: false, error: 'This device platform is not included in the active subscription plan' });
      return;
    }

    await enforcePlanDeviceLimit(client, pairing.restaurant_id, subscription, devicePlatform);

    const deviceResult = await client.query(
      `
        INSERT INTO devices (restaurant_id, name, platform, api_key_hash)
        VALUES ($1, $2, $3, $4)
        RETURNING id, name, platform, active, created_at
      `,
      [pairing.restaurant_id, deviceName || pairing.device_name || MAIN_APP_DEVICE_NAME, devicePlatform, hashSecret(apiKey)],
    );
    const device = deviceResult.rows[0];
    await client.query('UPDATE pairing_codes SET used_at = now(), used_by_device = $1 WHERE id = $2', [
      device.id,
      pairing.pairing_id,
    ]);
    await client.query('COMMIT');

    sendJson(response, 201, {
      ok: true,
      restaurant: {
        id: pairing.restaurant_id,
        name: pairing.restaurant_name,
        status: pairing.restaurant_status,
      },
      device,
      subscription: decorateSubscription(subscription),
      apiKey,
    });
  } catch (error) {
    await client.query('ROLLBACK');
    throw error;
  } finally {
    client.release();
  }
}

async function handleSyncPush(request, response, context) {
  const body = await readJson(request);
  const changes = Array.isArray(body.changes) ? body.changes : [];

  if (!changes.length) {
    sendJson(response, 200, { ok: true, acceptedIds: [], count: 0, subscription: context.subscription });
    return;
  }

  const acceptedIds = [];
  const client = await pool.connect();

  try {
    await client.query('BEGIN');
    const staffDirectoryChange = changes.find((change) =>
      change?.entityType === 'app_kv' &&
      change?.operation !== 'delete' &&
      change?.payload?.key === STAFF_DIRECTORY_KEY
    );
    if (staffDirectoryChange) {
      enforceUserLimit(context.subscription, staffDirectoryChange.payload.value);
    }
    for (const change of changes) {
      const clientEventId = String(change.id || '').trim();
      const entityType = String(change.entityType || '').trim();
      const entityId = String(change.entityId || '').trim();
      const operation = String(change.operation || 'upsert').trim();
      const payload = change.payload && typeof change.payload === 'object' ? change.payload : {};

      if (!clientEventId || !entityType || !entityId) {
        continue;
      }

      await client.query(
        `
          INSERT INTO sync_events (
            client_event_id, restaurant_id, device_id, entity_type, entity_id, operation, payload
          )
          VALUES ($1, $2, $3, $4, $5, $6, $7::jsonb)
          ON CONFLICT (restaurant_id, device_id, client_event_id) DO NOTHING
        `,
        [
          clientEventId,
          context.restaurantId,
          context.deviceId,
          entityType,
          entityId,
          operation,
          JSON.stringify(payload),
        ],
      );

      if (entityType === 'app_kv' && operation === 'upsert' && typeof payload.key === 'string') {
        await client.query(
          `
            INSERT INTO cloud_kv (restaurant_id, key, value, updated_by_device)
            VALUES ($1, $2, $3::jsonb, $4)
            ON CONFLICT (restaurant_id, key) DO UPDATE SET
              value = excluded.value,
              updated_at = now(),
              updated_by_device = excluded.updated_by_device
          `,
          [context.restaurantId, payload.key, safeJsonValue(payload.value), context.deviceId],
        );
      }

      acceptedIds.push(clientEventId);
    }

    await client.query('UPDATE devices SET last_seen_at = now() WHERE id = $1', [context.deviceId]);
    await client.query('COMMIT');
  } catch (error) {
    await client.query('ROLLBACK');
    throw error;
  } finally {
    client.release();
  }

  // Real-time: nudge every other device on this restaurant to pull the new data
  // immediately. Best-effort; if no stream is open the data still syncs on the
  // next poll.
  if (acceptedIds.length) {
    realtimeBroadcastChange(context.restaurantId, context.deviceId);
  }

  sendJson(response, 200, { ok: true, acceptedIds, count: acceptedIds.length, subscription: context.subscription });
}

async function handleSyncPull(response, context, searchParams) {
  const since = searchParams.get('since') || '1970-01-01T00:00:00.000Z';
  const result = await pool.query(
    `
      SELECT key, value, updated_at, updated_by_device
      FROM cloud_kv
      WHERE restaurant_id = $1
        AND updated_at > $2::timestamptz
      ORDER BY updated_at ASC
      LIMIT 500
    `,
    [context.restaurantId, since],
  );

  sendJson(response, 200, {
    ok: true,
    serverTime: new Date().toISOString(),
    subscription: context.subscription,
    restaurant: context.restaurant,
    changes: result.rows.map((row) => ({
      key: row.key,
      value: row.value,
      updatedAt: row.updated_at,
      updatedByDevice: row.updated_by_device,
    })),
  });
}

// Server-Sent Events stream for real-time sync. The client opens this once and
// keeps it open; the server pushes a "changed" event whenever another device
// writes data, and a "session-invalidated" event (then closes) if this device
// is logged out. A periodic heartbeat both keeps the connection alive and lets
// the server detect the device becoming inactive so it can force a sign-out.
async function handleSyncStream(request, response, context) {
  response.writeHead(200, {
    'access-control-allow-origin': '*',
    'cache-control': 'no-cache, no-transform',
    'connection': 'keep-alive',
    'content-type': 'text/event-stream; charset=utf-8',
    'x-accel-buffering': 'no',
  });

  // Prime the stream so proxies flush headers and the client knows it is live.
  realtimeSend(response, 'ready', { at: new Date().toISOString() });

  const unregister = realtimeRegister(context.restaurantId, context.deviceId, response);

  const heartbeat = setInterval(async () => {
    // Verify the device is still active; if it was logged out elsewhere, tell
    // the client and close so it signs out immediately.
    try {
      const check = await pool.query(
        'SELECT active FROM devices WHERE id = $1 AND restaurant_id = $2 LIMIT 1',
        [context.deviceId, context.restaurantId],
      );
      const stillActive = check.rows[0]?.active === true;
      if (!stillActive) {
        realtimeSend(response, 'session-invalidated', { reason: 'Signed in on another device' });
        cleanup();
        try {
          response.end();
        } catch {
          /* ignore */
        }
        return;
      }
      await pool.query('UPDATE devices SET last_seen_at = now() WHERE id = $1', [context.deviceId]);
    } catch {
      /* transient DB error — keep the stream open and retry next tick */
    }
    realtimeSend(response, 'heartbeat', { at: new Date().toISOString() });
  }, REALTIME_HEARTBEAT_MS);

  let cleanedUp = false;
  function cleanup() {
    if (cleanedUp) return;
    cleanedUp = true;
    clearInterval(heartbeat);
    unregister();
  }

  request.on('close', cleanup);
  request.on('error', cleanup);
  response.on('error', cleanup);
}

async function withDevice(request, response, handler) {
  const restaurantId = String(request.headers['x-restaurant-id'] || '');
  const deviceId = String(request.headers['x-device-id'] || '');
  const apiKey = String(request.headers['x-api-key'] || '');

  if (!restaurantId || !deviceId || !apiKey) {
    sendJson(response, 401, { ok: false, error: 'Missing device credentials' });
    return;
  }

  const result = await pool.query(
    `
      SELECT
        d.id,
        d.restaurant_id,
        d.api_key_hash,
        d.platform,
        r.name AS restaurant_name,
        r.owner_name,
        r.phone,
        r.email,
        r.status AS restaurant_status
      FROM devices d
      JOIN restaurants r ON r.id = d.restaurant_id
      WHERE d.id = $1 AND d.restaurant_id = $2 AND d.active = true
      LIMIT 1
    `,
    [deviceId, restaurantId],
  );
  const device = result.rows[0];

  if (!device || device.api_key_hash !== hashSecret(apiKey)) {
    sendJson(response, 403, { ok: false, error: 'Invalid device credentials' });
    return;
  }

  if (device.restaurant_status !== 'approved') {
    sendJson(response, 403, { ok: false, error: 'Restaurant account is not approved' });
    return;
  }

  const subscription = await getActiveSubscription(pool, restaurantId);
  if (!subscription) {
    sendJson(response, 402, { ok: false, error: 'Subscription is not active or expired' });
    return;
  }

  if (!planSupportsPlatform(getPlanDefinition(subscription.plan_name, subscription.max_devices), device.platform)) {
    await pool.query('UPDATE devices SET active = false, updated_at = now() WHERE id = $1', [deviceId]);
    sendJson(response, 403, {
      ok: false,
      error: 'This device is no longer included in the current subscription plan. Sign in with a compatible app or contact the administrator.',
    });
    return;
  }

  await handler({
    restaurantId,
    deviceId,
    subscription,
    restaurant: {
      id: device.restaurant_id,
      name: device.restaurant_name,
      owner_name: device.owner_name,
      phone: device.phone,
      email: device.email,
      status: device.restaurant_status,
    },
  });
}

async function withClient(request, response, handler) {
  const token = String(request.headers['x-client-token'] || '').trim();
  const tokenPayload = verifyClientToken(token);

  if (!tokenPayload?.accountId) {
    sendJson(response, 401, { ok: false, error: 'Client login required' });
    return;
  }

  const result = await pool.query(
    `
      SELECT id, owner_name, phone, email, password_hash, recovery_code_hash, recovery_code_set_at, status, created_at
      FROM accounts
      WHERE id = $1
      LIMIT 1
    `,
    [tokenPayload.accountId],
  );
  const account = result.rows[0];

  if (!account) {
    sendJson(response, 401, { ok: false, error: 'Client account not found' });
    return;
  }

  await handler({ account, appPlatform: tokenPayload.appPlatform || null });
}

async function getAccountRestaurants(accountId, appPlatform = null) {
  const restaurantsResult = await pool.query(
    `
      SELECT
        r.id,
        r.name,
        r.owner_name,
        r.phone,
        r.email,
        r.status,
        r.created_at,
        s.id AS subscription_id,
        s.plan_name,
        s.status AS subscription_status,
        s.starts_at,
        s.expires_at,
        s.max_devices,
        s.max_users,
        sd.value AS staff_users,
        COALESCE(ds.active_devices, 0)::int AS active_devices,
        COALESCE(ds.total_devices, 0)::int AS total_devices
      FROM restaurants r
      LEFT JOIN LATERAL (
        SELECT *
        FROM subscriptions latest_s
        WHERE latest_s.restaurant_id = r.id
        ORDER BY latest_s.created_at DESC
        LIMIT 1
      ) s ON true
      LEFT JOIN LATERAL (
        SELECT
          COUNT(*) FILTER (WHERE d.active)::int AS active_devices,
          COUNT(*)::int AS total_devices
        FROM devices d
        WHERE d.restaurant_id = r.id
      ) ds ON true
      LEFT JOIN cloud_kv sd ON sd.restaurant_id = r.id AND sd.key = $2
      WHERE r.account_id = $1
      ORDER BY r.created_at DESC
    `,
    [accountId, STAFF_DIRECTORY_KEY],
  );
  const devicesResult = await pool.query(
    `
      SELECT d.id, d.restaurant_id, d.name, d.platform, d.active, d.last_seen_at, d.created_at
      FROM devices d
      JOIN restaurants r ON r.id = d.restaurant_id
      WHERE r.account_id = $1
      ORDER BY d.created_at DESC
    `,
    [accountId],
  );
  const devicesByRestaurant = new Map();

  for (const device of devicesResult.rows) {
    const list = devicesByRestaurant.get(device.restaurant_id) || [];
    list.push(device);
    devicesByRestaurant.set(device.restaurant_id, list);
  }

  return restaurantsResult.rows
    .filter((restaurant) => restaurantSupportsPlatform(restaurant, appPlatform, getPlanDefinition))
    .map((restaurant) => {
      const { staff_users: staffUsers, ...rest } = restaurant;
      const decoratedRestaurant = decorateRestaurantPlanFields(rest);

      return {
        ...decoratedRestaurant,
        devices: devicesByRestaurant.get(restaurant.id) || [],
        staffUsers: normalizeStaffDirectory(staffUsers),
        active_users: Math.max(1, normalizeStaffDirectory(staffUsers).filter((user) => user.active).length),
      };
    });
}

async function getAccountRestaurant(accountId, restaurantId) {
  const result = await pool.query('SELECT id FROM restaurants WHERE id = $1 AND account_id = $2 LIMIT 1', [
    restaurantId,
    accountId,
  ]);

  return result.rows[0] || null;
}

async function getActiveSubscription(client, restaurantId) {
  const result = await client.query(
    `
      SELECT id, plan_name, status, starts_at, expires_at, max_devices, max_users
      FROM subscriptions
      WHERE restaurant_id = $1
        AND status IN ('trial', 'active')
        AND starts_at <= now()
        AND expires_at >= now()
      ORDER BY expires_at DESC
      LIMIT 1
    `,
    [restaurantId],
  );

  return decorateSubscription(result.rows[0]) || null;
}

function requireAdmin(request) {
  const token = String(request.headers['x-admin-token'] || '');
  if (!ADMIN_TOKEN || token !== ADMIN_TOKEN) {
    const error = new Error('Invalid admin token');
    error.statusCode = 403;
    throw error;
  }
}

function safeJsonValue(value) {
  if (typeof value === 'string') {
    try {
      return JSON.stringify(JSON.parse(value));
    } catch {
      return JSON.stringify(value);
    }
  }

  return JSON.stringify(value ?? null);
}

function normalizeStaffDirectory(value) {
  if (!Array.isArray(value)) {
    return [];
  }

  return value
    .map((user) => ({
      id: String(user?.id || '').trim(),
      name: String(user?.name || 'App User').trim() || 'App User',
      active: user?.active !== false,
      updatedAt: String(user?.updatedAt || ''),
      lastLoginAt: user?.lastLoginAt ? String(user.lastLoginAt) : '',
    }))
    .filter((user) => user.id);
}

function createApiKey() {
  return `gipos_${crypto.randomBytes(24).toString('hex')}`;
}

function createPairingCode() {
  return String(crypto.randomInt(100000, 1000000));
}

function createRecoveryCode() {
  const alphabet = 'ABCDEFGHJKLMNPQRSTUVWXYZ23456789';
  const bytes = crypto.randomBytes(12);
  const code = Array.from(bytes, (byte) => alphabet[byte % alphabet.length]).join('');
  return `${code.slice(0, 4)}-${code.slice(4, 8)}-${code.slice(8, 12)}`;
}

function signOfflineLicensePayload(payload) {
  const stablePayload = JSON.stringify(Object.keys(payload).sort().reduce((acc, key) => {
    acc[key] = payload[key];
    return acc;
  }, {}));
  return crypto.createHmac('sha256', OFFLINE_LICENSE_SECRET).update(stablePayload).digest('hex');
}

function createOfflineLicensePayload({
  id,
  licenseKey,
  businessName,
  phone,
  deviceFingerprint,
  deviceName,
  activatedAt,
  expiresAt,
  subscription,
}) {
  const payload = {
    plan: 'offline',
    licenseId: String(id || ''),
    licenseKey: String(licenseKey || ''),
    businessName: String(businessName || ''),
    phone: String(phone || ''),
    deviceFingerprint: String(deviceFingerprint || ''),
    deviceName: String(deviceName || ''),
    activatedAt: String(activatedAt || ''),
    expiresAt: String(expiresAt || ''),
    issuedAt: new Date().toISOString(),
    subscriptionPlan: String(subscription?.plan_name || subscription?.planName || ''),
    subscriptionStatus: String(subscription?.status || ''),
    subscriptionExpiresAt: String(subscription?.expires_at || expiresAt || ''),
    subscriptionMaxDevices: Number(subscription?.max_devices || 0),
  };

  return { ...payload, signature: signOfflineLicensePayload(payload) };
}

function normalizeRecoveryCode(value) {
  const cleaned = String(value || '').toUpperCase().replace(/[^A-Z0-9]/g, '');
  return cleaned.length === 12 ? `${cleaned.slice(0, 4)}-${cleaned.slice(4, 8)}-${cleaned.slice(8, 12)}` : cleaned;
}

function hashSecret(value) {
  return crypto.createHash('sha256').update(String(value)).digest('hex');
}

function hashPassword(password) {
  const salt = crypto.randomBytes(16).toString('hex');
  const hash = crypto.scryptSync(String(password), salt, 64).toString('hex');
  return `scrypt:${salt}:${hash}`;
}

function verifyPassword(password, storedHash) {
  const parts = String(storedHash || '').split(':');
  if (parts.length !== 3 || parts[0] !== 'scrypt') {
    return false;
  }

  const expected = Buffer.from(parts[2], 'hex');
  const actual = crypto.scryptSync(String(password), parts[1], expected.length);
  return expected.length === actual.length && crypto.timingSafeEqual(expected, actual);
}

function hashDesktopPin(pin) {
  const salt = crypto.randomBytes(16).toString('hex');
  const hash = crypto.createHash('sha256').update(`${salt}:${pin}`).digest('hex');
  return { salt, hash };
}

function publicAccount(account) {
  return {
    id: account.id,
    ownerName: account.owner_name,
    phone: account.phone,
    email: account.email,
    status: account.status,
    recoveryCodeSetAt: account.recovery_code_set_at,
    createdAt: account.created_at,
  };
}

function createClientToken(accountId, appPlatform = null) {
  const payload = {
    accountId,
    ...(appPlatform ? { appPlatform } : {}),
    expiresAt: Date.now() + CLIENT_TOKEN_TTL_MS,
  };
  const body = base64UrlEncode(JSON.stringify(payload));
  const signature = signTokenBody(body);
  return `${body}.${signature}`;
}

function verifyClientToken(token) {
  const [body, signature] = String(token || '').split('.');
  if (!body || !signature || signature !== signTokenBody(body)) {
    return null;
  }

  try {
    const payload = JSON.parse(base64UrlDecode(body));
    if (!payload.accountId || Number(payload.expiresAt || 0) < Date.now()) {
      return null;
    }

    return payload;
  } catch {
    return null;
  }
}

function signTokenBody(body) {
  return crypto.createHmac('sha256', CLIENT_TOKEN_SECRET).update(body).digest('base64url');
}

function base64UrlEncode(value) {
  return Buffer.from(value, 'utf8').toString('base64url');
}

function base64UrlDecode(value) {
  return Buffer.from(value, 'base64url').toString('utf8');
}

function clampInteger(value, min, max, fallback) {
  const number = Number(value);
  if (!Number.isFinite(number)) {
    return fallback;
  }

  return Math.min(max, Math.max(min, Math.trunc(number)));
}

function parseExpiryDate(value, fallbackDays) {
  if (value) {
    const parsed = new Date(String(value));
    if (!Number.isNaN(parsed.getTime()) && parsed.getTime() > Date.now()) {
      return parsed.toISOString();
    }
  }

  return new Date(Date.now() + fallbackDays * 24 * 60 * 60 * 1000).toISOString();
}

function normalizePlanId(value, maxDevices = 0) {
  const planText = String(value || '').trim().toLowerCase();

  if (planText.includes('gold')) {
    return 'gold';
  }

  if (planText.includes('premium')) {
    return 'premium';
  }

  if (planText.includes('offline')) {
    return 'offline';
  }

  if (planText.includes('android') || planText.includes('mobile')) {
    return 'android';
  }

  if (Number(maxDevices || 0) >= UNLIMITED_DEVICE_LIMIT) {
    return 'gold';
  }

  return 'premium';
}

function getPlanDefinition(value, maxDevices = 0) {
  const planId = normalizePlanId(value, maxDevices);
  return PLAN_CATALOG.find((plan) => plan.id === planId) || PLAN_CATALOG[0];
}

function getPlanMaxDevices(value) {
  return getPlanDefinition(value).maxDevices;
}

function decorateSubscription(subscription) {
  if (!subscription) {
    return null;
  }

  const plan = getPlanDefinition(subscription.plan_name, subscription.max_devices);

  return {
    ...subscription,
    plan_id: plan.id,
    plan_name: plan.name,
    plan_subtitle: plan.subtitle,
    plan_features: plan.features,
    plan_local_pos: plan.localPos,
    plan_counter_label: plan.counterLabel,
    plan_platforms: plan.platforms,
    plan_device_limits: plan.deviceLimits,
    plan_capabilities: plan.capabilities,
    max_devices: Number(subscription.max_devices || plan.maxDevices),
    max_users: subscription.max_users == null ? null : Number(subscription.max_users),
  };
}

function decorateRestaurantPlanFields(restaurant) {
  const subscription = restaurant?.subscription_id
    ? decorateSubscription({
        id: restaurant.subscription_id,
        plan_name: restaurant.plan_name,
        status: restaurant.subscription_status,
        starts_at: restaurant.starts_at,
        expires_at: restaurant.expires_at,
        max_devices: restaurant.max_devices,
        max_users: restaurant.max_users,
      })
    : null;

  if (!subscription) {
    return restaurant;
  }

  return {
    ...restaurant,
    plan_id: subscription.plan_id,
    plan_name: subscription.plan_name,
    plan_subtitle: subscription.plan_subtitle,
    plan_features: subscription.plan_features,
    plan_local_pos: subscription.plan_local_pos,
    plan_counter_label: subscription.plan_counter_label,
    plan_platforms: subscription.plan_platforms,
    plan_device_limits: subscription.plan_device_limits,
    plan_capabilities: subscription.plan_capabilities,
    max_devices: subscription.max_devices,
    max_users: subscription.max_users,
    active_users: Number(restaurant.active_users || 1),
  };
}

function normalizeDevicePlatform(value, deviceName = '') {
  const normalized = String(value || '').trim().toLowerCase();
  if (normalized === 'android' || normalized === 'windows') {
    return normalized;
  }
  return /android|mobile/i.test(String(deviceName || '')) ? 'android' : 'windows';
}

async function enforcePlanDeviceLimit(client, restaurantId, subscription, platform = 'windows') {
  const plan = getPlanDefinition(subscription?.plan_name, subscription?.max_devices);
  const maxDevices = Number(plan.deviceLimits?.[platform] ?? subscription?.max_devices ?? plan.maxDevices);
  if (maxDevices >= UNLIMITED_DEVICE_LIMIT) {
    return true;
  }

  const result = await client.query(
    `
      SELECT COUNT(*)::int AS active_devices
      FROM devices
      WHERE restaurant_id = $1
        AND active = true
        AND platform = $2
    `,
    [restaurantId, platform],
  );
  const activeDevices = Number(result.rows[0]?.active_devices || 0);

  if (activeDevices < maxDevices) {
    return true;
  }

  const deviceLabel = platform === 'android' ? 'Android device' : 'Windows counter';
  const error = new Error(`${plan.name} plan allows ${maxDevices} active ${deviceLabel}${maxDevices === 1 ? '' : 's'} only. Deactivate or transfer the existing device before activating another one.`);
  error.statusCode = 409;
  throw error;
}

function readJson(request) {
  return new Promise((resolve, reject) => {
    let body = '';

    request.on('data', (chunk) => {
      body += chunk;
      if (body.length > 2_000_000) {
        reject(new Error('Request body too large'));
        request.destroy();
      }
    });
    request.on('end', () => {
      try {
        resolve(body ? JSON.parse(body) : {});
      } catch {
        reject(new Error('Invalid JSON body'));
      }
    });
    request.on('error', reject);
  });
}

function readRequestBuffer(request, maxBytes) {
  return new Promise((resolve, reject) => {
    const chunks = [];
    let total = 0;
    let rejected = false;

    request.on('data', (chunk) => {
      total += chunk.length;
      if (total > maxBytes) {
        rejected = true;
        const error = new Error(`Request body too large. Maximum upload size is ${formatBytes(maxBytes)}.`);
        error.statusCode = 413;
        reject(error);
        request.destroy();
        return;
      }

      chunks.push(chunk);
    });
    request.on('end', () => {
      if (!rejected) {
        resolve(Buffer.concat(chunks, total));
      }
    });
    request.on('error', reject);
  });
}

async function readMultipartFormData(request, maxBytes) {
  const contentType = String(request.headers['content-type'] || '');
  const boundaryMatch = contentType.match(/boundary=(?:"([^"]+)"|([^;]+))/i);
  const boundary = boundaryMatch?.[1] || boundaryMatch?.[2];

  if (!boundary) {
    const error = new Error('Multipart boundary is missing');
    error.statusCode = 400;
    throw error;
  }

  const buffer = await readRequestBuffer(request, maxBytes);
  return parseMultipartBuffer(buffer, boundary);
}

function parseMultipartBuffer(buffer, boundary) {
  const delimiter = Buffer.from(`--${boundary}`);
  const headerSeparator = Buffer.from('\r\n\r\n');
  const fields = {};
  const files = [];
  let cursor = 0;

  while (cursor < buffer.length) {
    const delimiterStart = buffer.indexOf(delimiter, cursor);
    if (delimiterStart === -1) {
      break;
    }

    let partStart = delimiterStart + delimiter.length;
    if (buffer[partStart] === 45 && buffer[partStart + 1] === 45) {
      break;
    }

    if (buffer[partStart] === 13 && buffer[partStart + 1] === 10) {
      partStart += 2;
    }

    const nextDelimiterStart = buffer.indexOf(delimiter, partStart);
    if (nextDelimiterStart === -1) {
      break;
    }

    let partEnd = nextDelimiterStart;
    if (buffer[partEnd - 2] === 13 && buffer[partEnd - 1] === 10) {
      partEnd -= 2;
    }

    const part = buffer.subarray(partStart, partEnd);
    const headerEnd = part.indexOf(headerSeparator);
    if (headerEnd !== -1) {
      const headers = part.subarray(0, headerEnd).toString('utf8');
      const data = part.subarray(headerEnd + headerSeparator.length);
      const disposition = headers.match(/^content-disposition:\s*(.+)$/im)?.[1] || '';
      const name = disposition.match(/name="([^"]+)"/i)?.[1] || '';
      const filename = disposition.match(/filename="([^"]*)"/i)?.[1] || '';

      if (name && filename) {
        files.push({ fieldName: name, filename: path.basename(filename), data });
      } else if (name) {
        fields[name] = data.toString('utf8');
      }
    }

    cursor = nextDelimiterStart;
  }

  return { fields, files };
}

function normalizePublicBaseUrl(value) {
  const trimmed = String(value || '').trim().replace(/\/+$/, '');
  if (!trimmed) {
    return '';
  }

  try {
    const parsed = new URL(trimmed);
    if (parsed.protocol !== 'http:' && parsed.protocol !== 'https:') {
      return '';
    }
    return parsed.origin;
  } catch {
    return '';
  }
}

function getPublicBaseUrl(request) {
  if (PUBLIC_BASE_URL) {
    return PUBLIC_BASE_URL;
  }

  const forwardedProto = String(request.headers['x-forwarded-proto'] || '').split(',')[0].trim();
  const forwardedHost = String(request.headers['x-forwarded-host'] || '').split(',')[0].trim();
  const host = forwardedHost || String(request.headers.host || '').split(',')[0].trim() || `localhost:${PORT}`;
  const proto = forwardedProto || (request.socket?.encrypted ? 'https' : 'http');
  return `${proto}://${host}`.replace(/\/+$/, '');
}

function getPublicConfig(request) {
  const baseUrl = getPublicBaseUrl(request);
  const updateStatus = getUpdateStatusPayload();

  return {
    ok: true,
    service: 'gi-pos-cloud',
    baseUrl,
    desktop: {
      cloudUrl: baseUrl,
      updateUrl: `${baseUrl}/updates/win`,
      downloadWindows: `${baseUrl}/download/windows`,
    },
    android: {
      cloudUrl: baseUrl,
      downloadApk: `${baseUrl}/download/android`,
    },
    endpoints: {
      health: `${baseUrl}/health`,
      connect: `${baseUrl}/connect`,
      signup: `${baseUrl}/signup`,
      portal: `${baseUrl}/portal`,
      admin: `${baseUrl}/admin`,
      downloadWindows: `${baseUrl}/download/windows`,
      downloadAndroid: `${baseUrl}/download/android`,
      latestYml: `${baseUrl}/updates/win/latest.yml`,
    },
    updates: {
      ready: updateStatus.ready,
      version: updateStatus.version,
      setup: updateStatus.setup ? updateStatus.setup.name : '',
      blockmap: updateStatus.blockmap ? updateStatus.blockmap.name : '',
    },
    plans: PLAN_CATALOG.map((plan) => ({
      id: plan.id,
      name: plan.name,
      localPos: plan.localPos,
      maxDevices: plan.maxDevices,
      counterLabel: plan.counterLabel,
      platforms: plan.platforms,
      deviceLimits: plan.deviceLimits,
      capabilities: plan.capabilities,
    })),
  };
}

function escapeHtml(value) {
  return String(value ?? '')
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;')
    .replace(/'/g, '&#39;');
}

function sendJson(response, statusCode, payload) {
  response.writeHead(statusCode, {
    'access-control-allow-headers': 'content-type, x-admin-token, x-client-token, x-api-key, x-device-id, x-restaurant-id',
    'access-control-allow-methods': 'GET, POST, DELETE, OPTIONS',
    'access-control-allow-origin': '*',
    'content-type': 'application/json',
  });
  response.end(statusCode === 204 ? undefined : JSON.stringify(payload));
}

function sendHtml(response, html) {
  response.writeHead(200, {
    'content-type': 'text/html; charset=utf-8',
  });
  response.end(html);
}

async function serveUpdateFile(response, pathname) {
  const requestedName = decodeURIComponent(pathname.replace('/updates/win/', ''));
  const safeName = path.basename(requestedName);

  if (!safeName || safeName !== requestedName) {
    sendJson(response, 400, { ok: false, error: 'Invalid update file path' });
    return;
  }

  const filePath = path.join(UPDATE_DIR, safeName);

  if (!fs.existsSync(filePath) || !fs.statSync(filePath).isFile()) {
    sendJson(response, 404, { ok: false, error: 'Update file not found' });
    return;
  }

  response.writeHead(200, {
    'cache-control': safeName === 'latest.yml' ? 'no-store' : 'public, max-age=31536000, immutable',
    'content-type': getUpdateContentType(safeName),
  });
  fs.createReadStream(filePath).pipe(response);
}

async function serveWindowsDownload(response) {
  const downloadFile = findLatestWindowsSetupFile();

  if (!downloadFile) {
    sendJson(response, 404, {
      ok: false,
      error: 'Windows setup file not found. Upload the latest Setup exe to cloud/updates/win.',
    });
    return;
  }

  const stat = fs.statSync(downloadFile.path);

  response.writeHead(200, {
    'cache-control': 'no-store',
    'content-disposition': `attachment; filename="${downloadFile.name.replace(/"/g, '')}"`,
    'content-length': stat.size,
    'content-type': 'application/vnd.microsoft.portable-executable',
  });
  fs.createReadStream(downloadFile.path).pipe(response);
}

async function serveAndroidDownload(response) {
  const downloadFile = findLatestAndroidApk();

  if (!downloadFile) {
    sendJson(response, 404, {
      ok: false,
      error: 'Android APK not found. Copy the latest APK to cloud/updates/android or GI_ANDROID_UPDATE_DIR.',
    });
    return;
  }

  const stat = fs.statSync(downloadFile.path);
  response.writeHead(200, {
    'cache-control': 'no-store',
    'content-disposition': `attachment; filename="${downloadFile.name.replace(/"/g, '')}"`,
    'content-length': stat.size,
    'content-type': 'application/vnd.android.package-archive',
  });
  fs.createReadStream(downloadFile.path).pipe(response);
}

function findLatestAndroidApk() {
  if (!fs.existsSync(ANDROID_UPDATE_DIR)) return null;
  const files = fs.readdirSync(ANDROID_UPDATE_DIR)
    .filter((fileName) => /\.apk$/i.test(fileName))
    .map((fileName) => {
      const filePath = path.join(ANDROID_UPDATE_DIR, fileName);
      return { name: fileName, path: filePath, updatedAt: fs.statSync(filePath).mtimeMs };
    })
    .sort((first, second) => second.updatedAt - first.updatedAt);
  return files[0] || null;
}

function findLatestWindowsSetupFile() {
  const searchDirs = [UPDATE_DIR, path.join(__dirname, '..', '..', 'release')];

  for (const directory of searchDirs) {
    const fileFromLatest = getSetupFileFromLatestManifest(directory);

    if (fileFromLatest) {
      return fileFromLatest;
    }
  }

  for (const directory of searchDirs) {
    if (!fs.existsSync(directory)) {
      continue;
    }

    const files = fs
      .readdirSync(directory)
      .filter((fileName) => /\.exe$/i.test(fileName) && /setup/i.test(fileName))
      .map((fileName) => {
        const filePath = path.join(directory, fileName);
        return { name: fileName, path: filePath, mtimeMs: fs.statSync(filePath).mtimeMs };
      })
      .filter((file) => fs.statSync(file.path).isFile())
      .sort((first, second) => second.mtimeMs - first.mtimeMs);

    if (files[0]) {
      return { name: files[0].name, path: files[0].path };
    }
  }

  return null;
}

function getSetupFileFromLatestManifest(directory) {
  const latestPath = path.join(directory, 'latest.yml');

  if (!fs.existsSync(latestPath)) {
    return null;
  }

  const latestContent = fs.readFileSync(latestPath, 'utf8');
  const fileName = getSetupFileNameFromLatestContent(latestContent);

  if (!fileName || !/\.exe$/i.test(fileName) || !/setup/i.test(fileName)) {
    return null;
  }

  const filePath = path.join(directory, fileName);

  if (!fs.existsSync(filePath) || !fs.statSync(filePath).isFile()) {
    return null;
  }

  return { name: fileName, path: filePath };
}

function getSetupFileNameFromLatestContent(latestContent) {
  const pathMatch = latestContent.match(/^path:\s*['"]?(.+?)['"]?\s*$/m);
  const urlMatch = latestContent.match(/^\s*-\s+url:\s*['"]?(.+?)['"]?\s*$/m);
  const fileName = path.basename(String(pathMatch?.[1] || urlMatch?.[1] || '').trim());

  return fileName || '';
}

function sanitizeUpdateFileName(fileName) {
  const safeName = path.basename(String(fileName || '').trim());

  if (!safeName || safeName !== String(fileName || '').trim()) {
    return '';
  }

  return safeName;
}

function getUploadedFile(files, fieldName) {
  const file = files.find((candidate) => candidate.fieldName === fieldName && candidate.filename && candidate.data?.length);
  if (!file) {
    return null;
  }

  return { ...file, filename: sanitizeUpdateFileName(file.filename) };
}

function getUpdateStatusPayload() {
  const files = listUpdateFiles();
  const setupFile = getSetupFileFromLatestManifest(UPDATE_DIR);
  const latestFile = files.find((file) => file.name === 'latest.yml');
  const blockmapFile = setupFile
    ? files.find((file) => file.name === `${setupFile.name}.blockmap`)
    : files.find((file) => /\.exe\.blockmap$/i.test(file.name));

  return {
    files,
    latest: latestFile || null,
    setup: setupFile ? files.find((file) => file.name === setupFile.name) || setupFile : null,
    blockmap: blockmapFile || null,
    ready: Boolean(latestFile && setupFile && blockmapFile),
    version: latestFile ? getLatestVersionFromFile(latestFile.path) : '',
  };
}

function listUpdateFiles() {
  if (!fs.existsSync(UPDATE_DIR)) {
    return [];
  }

  return fs
    .readdirSync(UPDATE_DIR)
    .filter((fileName) => fileName === 'latest.yml' || /\.exe$/i.test(fileName) || /\.exe\.blockmap$/i.test(fileName))
    .map((fileName) => {
      const filePath = path.join(UPDATE_DIR, fileName);
      const stat = fs.statSync(filePath);
      return {
        name: fileName,
        path: filePath,
        size: stat.size,
        sizeLabel: formatBytes(stat.size),
        updatedAt: stat.mtime.toISOString(),
      };
    })
    .filter((file) => fs.statSync(file.path).isFile())
    .sort((first, second) => first.name.localeCompare(second.name));
}

function getLatestVersionFromFile(filePath) {
  try {
    const content = fs.readFileSync(filePath, 'utf8');
    return String(content.match(/^version:\s*['"]?(.+?)['"]?\s*$/m)?.[1] || '').trim();
  } catch {
    return '';
  }
}

function cleanupOldUpdateFiles(keepNames) {
  if (!fs.existsSync(UPDATE_DIR)) {
    return;
  }

  for (const fileName of fs.readdirSync(UPDATE_DIR)) {
    if (!keepNames.has(fileName) && (fileName === 'latest.yml' || /\.exe$/i.test(fileName) || /\.exe\.blockmap$/i.test(fileName))) {
      fs.rmSync(path.join(UPDATE_DIR, fileName), { force: true });
    }
  }
}

function formatBytes(bytes) {
  const value = Number(bytes || 0);
  if (value >= 1024 * 1024) {
    return `${(value / 1024 / 1024).toFixed(1)} MB`;
  }

  if (value >= 1024) {
    return `${(value / 1024).toFixed(1)} KB`;
  }

  return `${value} B`;
}

function getUpdateContentType(fileName) {
  if (fileName.endsWith('.yml') || fileName.endsWith('.yaml')) {
    return 'application/x-yaml; charset=utf-8';
  }

  if (fileName.endsWith('.exe')) {
    return 'application/vnd.microsoft.portable-executable';
  }

  if (fileName.endsWith('.blockmap')) {
    return 'application/octet-stream';
  }

  return 'application/octet-stream';
}

const BASE_STYLES = `
  :root {
    color-scheme: light;
    font-family: Inter, Segoe UI, Arial, sans-serif;
    color: #0f1729;
    background: #eef1f8;
    --ink: #0f1729;
    --muted: #5c6780;
    --line: #dde2ee;
    --soft: #f5f7fc;
    --surface: #ffffff;
    --teal: #6d5ae6;
    --teal-dark: #5847c9;
    --red: #dc2b4b;
    --green: #159a5a;
    --amber: #d98a09;
  }
  * { box-sizing: border-box; }
  body {
    margin: 0;
    min-height: 100vh;
    background:
      radial-gradient(circle at top left, rgba(139, 92, 246, .10), transparent 28%),
      radial-gradient(circle at top right, rgba(124, 58, 237, .12), transparent 30%),
      #eef2f6;
  }
  header {
    min-height: 76px;
    display: flex;
    align-items: center;
    justify-content: space-between;
    gap: 16px;
    padding: 14px 28px;
    background: rgba(255, 255, 255, .94);
    border-bottom: 1px solid var(--line);
    backdrop-filter: blur(12px);
    position: sticky;
    top: 0;
    z-index: 10;
  }
  main { padding: 28px; }
  h1, h2, h3, p { margin: 0; }
  h1 { font-size: 25px; line-height: 1.15; }
  h2 { font-size: 20px; line-height: 1.2; }
  h3 { font-size: 15px; line-height: 1.25; }
  p { color: var(--muted); font-weight: 700; }
  a { color: var(--teal); font-weight: 900; text-decoration: none; }
  a.button, .nav-link {
    height: 42px;
    display: inline-flex;
    align-items: center;
    justify-content: center;
    gap: 8px;
    padding: 0 14px;
    border: 1px solid #cbd5e1;
    border-radius: 7px;
    background: #fff;
    color: var(--ink);
    font-weight: 900;
  }
  a.button.primary, .nav-link.primary { background: var(--teal); color: #fff; border-color: var(--teal); }
  .brand-lockup { display: flex; align-items: center; gap: 12px; min-width: 0; }
  .brand-mark {
    width: 46px;
    height: 46px;
    display: grid;
    place-items: center;
    border-radius: 10px;
    background: linear-gradient(135deg, #8b5cf6, #7c3aed);
    color: #fff;
    font-size: 18px;
    font-weight: 950;
    box-shadow: 0 10px 26px rgba(15, 23, 42, .16);
  }
  /* Consistent brand logo on a black tile everywhere */
  .brand-logo-img {
    width: 46px; height: 46px; flex: 0 0 auto;
    object-fit: contain; padding: 7px;
    border-radius: 10px;
    background: #0b1220;
    box-shadow: 0 10px 26px rgba(15, 23, 42, .16);
  }
  .sidebar-logo-img { box-shadow: 0 6px 16px rgba(0,0,0,.35); background: #0b1220; }
  .nav-actions { display: flex; align-items: center; gap: 10px; flex-wrap: wrap; justify-content: flex-end; }
  .eyebrow { color: var(--teal); font-size: 11px; font-weight: 950; text-transform: uppercase; }
  .card { background: var(--surface); border: 1px solid var(--line); border-radius: 10px; box-shadow: 0 18px 44px rgba(15, 23, 42, .08); }
  .layout { max-width: 1220px; margin: 0 auto; display: grid; gap: 18px; }
  .page-shell { max-width: 1220px; margin: 0 auto; display: grid; gap: 18px; }
  .grid { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 16px; }
  .three-grid { display: grid; grid-template-columns: repeat(3, minmax(0, 1fr)); gap: 14px; }
  .panel { padding: 22px; display: grid; gap: 14px; }
  .section-head { display: flex; align-items: flex-start; justify-content: space-between; gap: 14px; flex-wrap: wrap; }
  .auth-shell { max-width: 1120px; margin: 0 auto; display: grid; grid-template-columns: minmax(320px, .9fr) minmax(380px, 1.1fr); gap: 18px; align-items: stretch; }
  .hero-panel {
    padding: 30px;
    display: grid;
    align-content: space-between;
    gap: 28px;
    min-height: 460px;
    color: #fff;
    background: linear-gradient(135deg, #8b5cf6 0%, #7c3aed 74%);
    border-color: transparent;
    overflow: hidden;
    position: relative;
  }
  .hero-panel:after {
    content: "";
    position: absolute;
    inset: auto -90px -110px auto;
    width: 240px;
    height: 240px;
    border-radius: 50%;
    background: rgba(255, 255, 255, .14);
  }
  .hero-panel h2 { font-size: 34px; }
  .hero-panel p, .hero-panel small { color: rgba(255, 255, 255, .88); }
  .hero-badges { display: flex; gap: 8px; flex-wrap: wrap; position: relative; z-index: 1; }
  .hero-badges span { padding: 8px 10px; border-radius: 999px; background: rgba(255,255,255,.14); border: 1px solid rgba(255,255,255,.26); font-weight: 900; }
  .step-list { display: grid; gap: 10px; position: relative; z-index: 1; }
  .step-list div { padding: 12px; border-radius: 8px; background: rgba(255,255,255,.12); border: 1px solid rgba(255,255,255,.22); font-weight: 900; }
  .form-panel { padding: 28px; display: grid; gap: 16px; }
  .toolbar { display: flex; align-items: center; justify-content: space-between; gap: 12px; flex-wrap: wrap; }
  .stack { display: grid; gap: 12px; }
  .admin-page { padding-top: 22px; }
  .admin-shell { max-width: 1280px; margin: 0 auto; display: grid; gap: 16px; }
  .admin-hero {
    display: grid;
    grid-template-columns: minmax(0, 1.15fr) minmax(340px, .85fr);
    gap: 18px;
    align-items: stretch;
    padding: 0;
    overflow: hidden;
  }
  .admin-hero-copy {
    padding: 28px;
    display: grid;
    align-content: space-between;
    gap: 22px;
    color: #fff;
    background: linear-gradient(135deg, #111827 0%, #7c3aed 68%);
  }
  .admin-hero-copy .eyebrow,
  .admin-hero-copy p { color: rgba(255,255,255,.86); }
  .admin-hero-copy h2 { font-size: 34px; line-height: 1.05; }
  .admin-hero-points { display: flex; gap: 8px; flex-wrap: wrap; }
  .admin-hero-points span { padding: 8px 10px; border: 1px solid rgba(255,255,255,.24); border-radius: 999px; background: rgba(255,255,255,.12); font-weight: 900; }
  .admin-command {
    padding: 22px;
    display: grid;
    gap: 13px;
    align-content: start;
    background: #fff;
  }
  .admin-board { display: grid; grid-template-columns: minmax(0, .9fr) minmax(0, 1.1fr); gap: 16px; align-items: start; }
  .admin-compact-card { min-height: 100%; }
  .admin-release-grid { display: grid; grid-template-columns: repeat(3, minmax(0, 1fr)); gap: 12px; }
  .release-upload-card { overflow: hidden; }
  .release-upload-card .section-head { padding-bottom: 2px; border-bottom: 1px solid #e2e8f0; }
  .release-actions { justify-content: space-between; }
  .release-action-buttons { display: flex; gap: 8px; flex-wrap: wrap; }
  .release-upload-card .file-list { grid-template-columns: repeat(3, minmax(0, 1fr)); }
  .release-checklist {
    grid-column: 1 / -1;
    display: grid;
    grid-template-columns: repeat(3, minmax(0, 1fr));
    gap: 8px;
  }
  .release-slot {
    display: grid;
    gap: 4px;
    padding: 10px;
    border: 1px solid #dbe3ee;
    border-radius: 9px;
    background: #fff;
  }
  .release-slot strong { color: var(--ink); }
  .release-slot span { color: #64748b; font-size: 12px; font-weight: 850; }
  .release-slot.ok { border-color: #b9efcd; background: #f0fdf4; }
  .release-slot.missing { border-color: #fed7aa; background: #fff7ed; }
  .upload-progress-card {
    display: none;
    gap: 8px;
    padding: 12px;
    border: 1px solid #dbe3ee;
    border-radius: 9px;
    background: #f8fafc;
  }
  .upload-progress-card.active { display: grid; }
  .upload-progress-head { display: flex; justify-content: space-between; gap: 12px; color: #334155; font-size: 12px; font-weight: 900; }
  .upload-progress-track { height: 10px; overflow: hidden; border-radius: 999px; background: #e2e8f0; }
  .upload-progress-fill { width: 0%; height: 100%; background: linear-gradient(90deg, var(--teal), #22c55e); transition: width .18s ease; }
  .upload-progress-meta { display: flex; justify-content: space-between; gap: 10px; flex-wrap: wrap; color: #64748b; font-size: 12px; font-weight: 850; }
  .release-upload-card .file-row {
    min-height: 82px;
    align-content: space-between;
    grid-template-columns: 1fr;
    border-radius: 10px;
  }
  .release-upload-card .file-row > span { justify-self: start; }
  .admin-client-tools { display: grid; grid-template-columns: minmax(240px, 1fr) 180px; gap: 12px; align-items: end; }
  .admin-client-head { align-items: end; }
  .admin-note-row { display: flex; gap: 8px; flex-wrap: wrap; }
  .admin-note-row span { padding: 7px 9px; border-radius: 999px; background: #f1f5f9; color: #475569; font-size: 12px; font-weight: 900; }
  label { display: grid; gap: 6px; color: #4a5568; font-size: 12px; font-weight: 900; }
  input, select { width: 100%; height: 42px; padding: 0 12px; border: 1px solid #cbd5e1; border-radius: 7px; font: inherit; font-weight: 800; outline: none; }
  input[type="file"] { height: auto; min-height: 46px; padding: 7px; background: #f8fafc; }
  input[type="file"]::file-selector-button {
    height: 30px;
    margin-right: 9px;
    padding: 0 10px;
    border: 1px solid #cbd5e1;
    border-radius: 6px;
    background: #fff;
    color: var(--ink);
    font-weight: 900;
    cursor: pointer;
  }
  input:focus, select:focus { border-color: var(--teal); box-shadow: 0 0 0 3px rgba(124, 58, 237, .16); }
  button { min-height: 42px; padding: 0 14px; border: 1px solid #cbd5e1; border-radius: 7px; background: #fff; color: var(--ink); font-weight: 900; cursor: pointer; }
  button.primary { background: var(--teal); color: #fff; border-color: var(--teal); }
  button.primary:hover { background: var(--teal-dark); }
  button.danger { background: var(--red); color: #fff; border-color: var(--red); }
  button:disabled { opacity: .55; cursor: not-allowed; }
  .row { display: flex; gap: 10px; align-items: center; flex-wrap: wrap; }
  .status { padding: 12px 14px; border-radius: 7px; background: #f4f7fb; border: 1px solid #dbe3ee; color: #334155; font-weight: 800; }
  .status.ok, .status.good { background: #ecfdf3; border-color: #b9efcd; color: #126b36; }
  .status.error { background: #fff1f2; border-color: #fecdd3; color: #a30f2f; }
  .status.warn { background: #fff7ed; border-color: #fed7aa; color: #9a3412; }
  .metric-grid { display: grid; grid-template-columns: repeat(4, minmax(0, 1fr)); gap: 12px; }
  .metric { padding: 14px; border: 1px solid #dbe3ee; border-radius: 8px; background: #f8fafc; display: grid; gap: 6px; }
  .metric span { color: #64748b; font-size: 12px; font-weight: 900; }
  .metric strong { font-size: 24px; }
  .plan-grid { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 12px; }
  .plan-card { padding: 16px; border: 1px solid #dbe3ee; border-radius: 10px; background: #f8fafc; display: grid; gap: 12px; }
  .plan-card.featured { border-color: rgba(124,58,237,.55); background: #eefafa; }
  .plan-card h3 { display: flex; align-items: center; justify-content: space-between; gap: 10px; }
  .plan-card ul { margin: 0; padding-left: 18px; color: #475569; font-weight: 800; line-height: 1.55; }
  .plan-card p { line-height: 1.4; }
  .plan-meta { display: flex; gap: 8px; flex-wrap: wrap; }
  .current-plan-card { border-color: rgba(124,58,237,.45); background: #eefafa; }
  .action-grid { display: grid; grid-template-columns: repeat(3, minmax(0, 1fr)); gap: 12px; }
  .action-card { padding: 16px; border: 1px solid #dbe3ee; border-radius: 8px; background: #f8fafc; display: grid; gap: 6px; }
  .action-card strong { font-size: 16px; }
  .file-list { display: grid; gap: 8px; }
  .file-row { display: grid; grid-template-columns: minmax(0, 1fr) auto; gap: 10px; padding: 10px; border: 1px solid #dbe3ee; border-radius: 7px; background: #f8fafc; }
  .file-row strong, .file-row span { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
  .file-row span, small { color: #64748b; font-weight: 800; }
  .table-wrap { overflow: auto; border: 1px solid #dbe3ee; border-radius: 8px; }
  table { width: 100%; border-collapse: collapse; font-size: 13px; }
  th, td { padding: 11px; border-bottom: 1px solid #e2e8f0; text-align: left; vertical-align: top; }
  th { color: #475569; font-size: 11px; text-transform: uppercase; }
  .badge { display: inline-flex; padding: 5px 8px; border-radius: 999px; background: #edf2f7; color: #334155; font-size: 11px; font-weight: 900; }
  .badge.good { background: #dcfce7; color: #166534; }
  .badge.bad { background: #ffe4e6; color: #9f1239; }
  .badge.warn { background: #ffedd5; color: #9a3412; }
  .code-box { font-size: 34px; letter-spacing: .14em; font-weight: 950; color: var(--teal); }
  .restaurant-list { display: grid; gap: 12px; }
  .restaurant-card { border: 1px solid #dbe3ee; border-radius: 10px; background: #fff; padding: 16px; display: grid; gap: 14px; overflow: hidden; }
  .restaurant-card.pending { border-color: #fed7aa; background: #fffaf3; }
  .restaurant-card.suspended { border-color: #fecdd3; background: #fff8f9; }
  .restaurant-main { display: grid; grid-template-columns: minmax(220px, 1.2fr) minmax(160px, .8fr) minmax(220px, 1fr); gap: 14px; align-items: start; }
  .admin-client-card { padding: 0; background: #fff; }
  .admin-client-card .restaurant-main {
    grid-template-columns: minmax(0, .95fr) minmax(220px, .55fr);
    gap: 0;
  }
  .admin-client-card .restaurant-title,
  .admin-client-card .stack,
  .admin-client-card .subscription-box { padding: 16px; }
  .admin-client-card .stack { border-left: 1px solid #e2e8f0; }
  .admin-client-card .subscription-box {
    grid-column: 1 / -1;
    border-right: 0;
    border-bottom: 0;
    border-left: 0;
    border-radius: 0;
    background: #f8fafc;
  }
  .admin-client-card .subscription-edit { grid-template-columns: minmax(120px, 1fr) 110px 145px 130px minmax(126px, auto); }
  .admin-client-card .subscription-box .row { justify-content: flex-end; }
  .restaurant-title { display: grid; gap: 6px; }
  .restaurant-title strong { font-size: 18px; }
  .subscription-box { padding: 12px; border: 1px solid #dbe3ee; border-radius: 8px; background: #f8fafc; display: grid; gap: 10px; }
  .subscription-edit { display: grid; grid-template-columns: minmax(120px, 1fr) 130px 150px auto; gap: 8px; align-items: end; }
  .device-list { color: #64748b; font-size: 12px; font-weight: 800; line-height: 1.45; }
  .empty-state { padding: 22px; border: 1px dashed #cbd5e1; border-radius: 10px; background: #f8fafc; color: #64748b; font-weight: 900; text-align: center; }
  .sidebar-count { display: inline-grid; place-items: center; min-width: 20px; height: 20px; padding: 0 6px; border-radius: 999px; background: #ef4444; color: #fff; font-size: 11px; font-weight: 900; margin-left: 6px; }
  .btn-sm { height: 34px; padding: 0 12px; font-size: 12.5px; border-radius: 8px; }
  .btn-danger { background: #fef2f2; border-color: #fecaca; color: #b91c1c; }
  .btn-danger:hover { background: #fee2e2; }
  .message-list { display: grid; gap: 14px; }
  .message-card { border: 1px solid var(--line); border-radius: 14px; background: #fff; padding: 16px 18px; display: grid; gap: 10px; box-shadow: 0 8px 22px rgba(15,23,42,.05); }
  .message-card.is-new { border-color: rgba(124,58,237,.4); box-shadow: 0 10px 26px rgba(124,58,237,.12); }
  .message-head { display: flex; align-items: center; justify-content: space-between; gap: 12px; flex-wrap: wrap; }
  .message-head strong { font-size: 15px; margin-right: 8px; }
  .message-when { font-size: 12px; color: #64748b; font-weight: 800; }
  .message-contact { font-size: 13px; color: #334155; font-weight: 800; }
  .message-body { font-size: 14px; color: #475569; font-weight: 650; line-height: 1.55; margin: 0; padding: 12px 14px; background: #f8fafc; border-radius: 10px; }
  .message-actions { display: flex; flex-wrap: wrap; gap: 8px; }
  .staff-toolbar { display: flex; align-items: center; justify-content: space-between; gap: 18px; flex-wrap: wrap; }
  .staff-toolbar label { display: grid; gap: 6px; font-weight: 850; font-size: 13px; min-width: 240px; }
  .staff-capacity { font-size: 14px; font-weight: 800; color: #334155; }
  .staff-capacity strong { color: var(--brand-teal-dark, #6d28d9); }
  .staff-list { display: grid; gap: 12px; }
  .staff-card { display: flex; align-items: center; justify-content: space-between; gap: 12px; flex-wrap: wrap; padding: 15px 18px; border: 1px solid var(--line); border-radius: 14px; background: #fff; transition: box-shadow .18s, border-color .18s; }
  .staff-card:hover { box-shadow: 0 10px 26px rgba(15,23,42,.08); border-color: rgba(124,58,237,.28); }
  .staff-card-main { display: flex; align-items: center; gap: 13px; }
  .staff-avatar { width: 44px; height: 44px; flex: 0 0 auto; border-radius: 12px; display: grid; place-items: center; background: linear-gradient(135deg, #8b5cf6, #6d28d9); color: #fff; font-weight: 950; font-size: 16px; }
  .staff-info { display: grid; gap: 3px; }
  .staff-info strong { font-size: 15px; font-weight: 900; }
  .staff-info .staff-meta { font-size: 12.5px; color: #64748b; font-weight: 800; }
  .staff-info .staff-tags { display: flex; flex-wrap: wrap; gap: 5px; margin-top: 3px; }
  .staff-chip { font-size: 10.5px; font-weight: 900; padding: 3px 8px; border-radius: 999px; background: rgba(124,58,237,.1); color: #6d28d9; }
  .staff-chip.muted { background: #f1f5f9; color: #64748b; }
  .staff-actions { display: flex; flex-wrap: wrap; gap: 8px; }
  .role-pill { display: inline-block; font-size: 11px; font-weight: 900; padding: 3px 9px; border-radius: 999px; background: rgba(124,58,237,.12); color: #6d28d9; margin-left: 6px; vertical-align: middle; }

  /* Portal modal */
  .pmodal-overlay { position: fixed; inset: 0; z-index: 200; display: grid; place-items: center; padding: 20px; background: rgba(11,18,32,.55); backdrop-filter: blur(4px); opacity: 0; visibility: hidden; transition: opacity .25s, visibility .25s; }
  .pmodal-overlay.open { opacity: 1; visibility: visible; }
  .pmodal { width: 100%; max-width: 760px; max-height: 92vh; display: flex; flex-direction: column; background: #fff; border-radius: 20px; box-shadow: 0 40px 90px rgba(15,23,42,.35); transform: translateY(16px) scale(.98); transition: transform .3s cubic-bezier(.22,.61,.36,1); overflow: hidden; }
  .pmodal-sm { max-width: 460px; }
  .pmodal-overlay.open .pmodal { transform: translateY(0) scale(1); }
  .pmodal-head { display: flex; align-items: flex-start; justify-content: space-between; gap: 12px; padding: 22px 26px; border-bottom: 1px solid var(--line); }
  .pmodal-head h3 { font-size: 21px; font-weight: 950; margin-top: 4px; }
  .pmodal-close { width: 36px; height: 36px; border-radius: 10px; border: 1px solid var(--line); background: #fff; font-size: 22px; line-height: 1; cursor: pointer; color: #475569; }
  .pmodal-close:hover { background: #f1f5f9; }
  .pmodal-body { padding: 22px 26px; overflow-y: auto; }
  .pmodal-steps { display: flex; gap: 8px; margin-bottom: 22px; flex-wrap: wrap; }
  .pmodal-step { flex: 1; min-width: 120px; padding: 11px 12px; border-radius: 12px; border: 1px solid var(--line); background: #f8fafc; font-weight: 900; font-size: 13px; color: #64748b; cursor: pointer; transition: all .18s; }
  .pmodal-step.active { background: var(--brand-grad, linear-gradient(120deg,#8b5cf6,#6d28d9)); color: #fff; border-color: transparent; box-shadow: 0 10px 22px rgba(124,58,237,.28); }
  .pmodal-section { display: none; animation: pmodalFade .3s ease; }
  .pmodal-section.active { display: block; }
  @keyframes pmodalFade { from { opacity: 0; transform: translateY(6px); } to { opacity: 1; transform: none; } }
  .pmodal-section-title { font-size: 16px; font-weight: 950; }
  .pmodal-section-sub { font-size: 13px; color: #64748b; font-weight: 700; margin: 4px 0 16px; }
  .pmodal-check-actions { display: flex; gap: 8px; margin-bottom: 14px; }
  .check-grid { display: grid; grid-template-columns: repeat(2, minmax(0,1fr)); gap: 10px; }
  .check-item { display: flex; align-items: center; gap: 10px; padding: 11px 13px; border: 1px solid var(--line); border-radius: 11px; background: #fff; font-weight: 800; font-size: 13.5px; cursor: pointer; transition: border-color .16s, background .16s; }
  .check-item:hover { border-color: rgba(124,58,237,.35); }
  .check-item input { width: 17px; height: 17px; accent-color: #7c3aed; flex: 0 0 auto; }
  .check-item.checked { border-color: rgba(124,58,237,.5); background: rgba(124,58,237,.06); }
  .table-sync-note { font-size: 12.5px; font-weight: 800; color: #64748b; padding: 9px 12px; border-radius: 10px; background: #f1f5f9; margin-bottom: 12px; }
  .table-sync-note.ok { background: rgba(34,197,94,.1); color: #15803d; }
  .table-area-group { margin-bottom: 16px; }
  .table-area-label { font-size: 12px; font-weight: 950; letter-spacing: .06em; text-transform: uppercase; color: #6d28d9; margin-bottom: 8px; }
  .pmodal-foot { display: flex; align-items: center; gap: 10px; padding: 18px 26px; border-top: 1px solid var(--line); background: #fbfaff; }
  .pmodal-foot .btn { flex: 0 0 auto; }
  /* Staff details: QR + barcode */
  .staff-detail-grid { display: grid; gap: 14px; }
  .staff-detail-row { display: flex; justify-content: space-between; gap: 12px; font-size: 13.5px; }
  .staff-detail-row span { color: #64748b; font-weight: 800; }
  .staff-detail-row strong { font-weight: 900; text-align: right; }
  .staff-codes { display: grid; gap: 16px; justify-items: center; margin-top: 6px; padding: 18px; border: 1px solid var(--line); border-radius: 14px; background: #f8fafc; }
  .staff-code-label { font-size: 11px; font-weight: 900; letter-spacing: .12em; text-transform: uppercase; color: #7c3aed; }
  .staff-qr { background: #fff; padding: 10px; border-radius: 10px; box-shadow: 0 6px 16px rgba(15,23,42,.08); }
  .staff-qr svg { display: block; }
  .staff-barcode { width: 100%; text-align: center; }
  .staff-barcode svg { max-width: 100%; height: 64px; }
  .staff-code-text { font-family: ui-monospace, monospace; font-weight: 900; letter-spacing: .12em; font-size: 14px; margin-top: 4px; }
  @media (max-width: 620px) { .check-grid { grid-template-columns: 1fr; } .pmodal-step { min-width: 0; font-size: 12px; } }
  @media (max-width: 1000px) {
    .auth-shell { grid-template-columns: 1fr; }
    .admin-hero, .admin-board { grid-template-columns: 1fr; }
    .hero-panel { min-height: auto; }
    .metric-grid, .action-grid, .three-grid, .plan-grid, .admin-release-grid { grid-template-columns: repeat(2, minmax(0, 1fr)); }
    .restaurant-main, .subscription-edit, .admin-client-card .restaurant-main, .admin-client-card .subscription-edit { grid-template-columns: 1fr; }
    .admin-client-card .stack { border-left: 0; border-top: 1px solid #e2e8f0; }
    .admin-client-card .subscription-box { grid-column: auto; }
    .release-upload-card .file-list, .release-checklist { grid-template-columns: repeat(2, minmax(0, 1fr)); }
  }
  @media (max-width: 680px) {
    header, main { padding-left: 16px; padding-right: 16px; }
    header { align-items: flex-start; }
    .grid, .metric-grid, .action-grid, .three-grid, .plan-grid, .admin-release-grid, .admin-client-tools { grid-template-columns: 1fr; }
    .release-upload-card .file-list, .release-checklist { grid-template-columns: 1fr; }
    .brand-mark { width: 40px; height: 40px; }
    .nav-actions { width: 100%; justify-content: flex-start; }
  }

  /* ---- Professional dashboard layer ---- */
  .section-title { display: grid; gap: 4px; margin-bottom: 4px; }
  .section-title h2 { font-size: 18px; }
  .section-title p { font-size: 13px; }
  .kpi-grid { display: grid; grid-template-columns: repeat(4, minmax(0, 1fr)); gap: 14px; }
  .kpi {
    position: relative;
    padding: 18px;
    display: grid;
    gap: 8px;
    background: var(--surface);
    border: 1px solid var(--line);
    border-radius: 14px;
    box-shadow: 0 10px 26px rgba(15, 23, 42, .06);
    overflow: hidden;
  }
  .kpi::before { content: ""; position: absolute; left: 0; top: 0; bottom: 0; width: 4px; background: var(--teal); }
  .kpi.kpi-red::before { background: var(--red); }
  .kpi.kpi-green::before { background: var(--green); }
  .kpi.kpi-amber::before { background: #d97706; }
  .kpi.kpi-slate::before { background: #64748b; }
  .kpi-top { display: flex; align-items: center; justify-content: space-between; gap: 10px; }
  .kpi-label { color: var(--muted); font-size: 12px; font-weight: 900; text-transform: uppercase; letter-spacing: .03em; }
  .kpi-icon { width: 34px; height: 34px; display: grid; place-items: center; border-radius: 10px; background: rgba(124,58,237,.12); color: var(--teal); font-size: 16px; font-weight: 950; }
  .kpi.kpi-red .kpi-icon { background: rgba(220,43,75,.12); color: var(--red); }
  .kpi.kpi-green .kpi-icon { background: rgba(22,163,74,.14); color: var(--green); }
  .kpi.kpi-amber .kpi-icon { background: rgba(217,119,6,.14); color: #b45309; }
  .kpi.kpi-slate .kpi-icon { background: rgba(100,116,139,.16); color: #475569; }
  .kpi-value { font-size: 30px; font-weight: 950; line-height: 1; letter-spacing: -.02em; color: var(--ink); }
  .kpi-hint { color: var(--muted); font-size: 12px; font-weight: 800; }
  .dash-grid { display: grid; grid-template-columns: minmax(0, 1.4fr) minmax(0, 1fr); gap: 18px; align-items: start; }
  .dash-side { display: grid; gap: 18px; align-content: start; }
  .topbar-actions { display: flex; gap: 8px; align-items: center; }
  .portal-hero { display: flex; align-items: center; justify-content: space-between; gap: 16px; flex-wrap: wrap; padding: 22px 24px; border-radius: 16px; color: #fff; background: linear-gradient(135deg, #6d28d9 0%, #8b5cf6 100%); box-shadow: 0 16px 34px rgba(124,58,237,.28); }
  .portal-hero-main h2 { color: #fff; font-size: 26px; line-height: 1.1; margin-top: 2px; }
  .portal-hero-main p { color: rgba(255,255,255,.9); font-weight: 800; margin-top: 4px; }
  .portal-hero-badge .badge { font-size: 13px; padding: 8px 14px; }
  /* Reports page */
  .report-filter-card { gap: 14px; }
  .report-filter-grid { display: grid; grid-template-columns: repeat(4, minmax(0, 1fr)); gap: 12px; }
  .report-filter-grid label { display: grid; gap: 5px; font-weight: 900; font-size: 12px; color: var(--muted); text-transform: uppercase; letter-spacing: .03em; }
  .report-filter-grid input, .report-filter-grid select { height: 42px; padding: 0 12px; border: 1px solid var(--line); border-radius: 10px; background: #fff; color: var(--ink); font: inherit; font-weight: 700; }
  .report-filter-grid input:focus, .report-filter-grid select:focus { outline: 0; border-color: var(--teal); box-shadow: 0 0 0 3px rgba(124,58,237,.16); }
  .report-quick-ranges { display: flex; gap: 8px; flex-wrap: wrap; }
  .report-quick-ranges .chip { height: 34px; padding: 0 14px; border: 1px solid var(--line); border-radius: 999px; background: #fff; color: var(--muted); font-weight: 900; font-size: 12.5px; cursor: pointer; }
  .report-quick-ranges .chip:hover { border-color: var(--teal); color: var(--teal); }
  .report-overview-grid { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 22px; }
  .report-sub { font-size: 13px; font-weight: 950; color: var(--muted); text-transform: uppercase; letter-spacing: .04em; margin: 0 0 10px; }
  @media (max-width: 900px) {
    .report-filter-grid { grid-template-columns: repeat(2, minmax(0, 1fr)); }
    .report-overview-grid { grid-template-columns: 1fr; }
  }
  .analytics-bars { display: grid; gap: 12px; }
  .analytics-bar { display: grid; gap: 6px; }
  .analytics-bar-head { display: flex; justify-content: space-between; gap: 10px; font-size: 13px; font-weight: 900; }
  .analytics-bar-head span:last-child { color: var(--muted); }
  .analytics-track { height: 10px; border-radius: 999px; background: #eef2f7; overflow: hidden; }
  .analytics-fill { height: 100%; border-radius: 999px; background: linear-gradient(90deg, var(--teal), #22c55e); }
  .analytics-fill.plan-premium { background: linear-gradient(90deg, #7c3aed, #a78bfa); }
  .analytics-fill.plan-gold { background: linear-gradient(90deg, #d97706, #f59e0b); }
  .analytics-fill.plan-offline { background: linear-gradient(90deg, #64748b, #94a3b8); }
  .analytics-fill.plan-android { background: linear-gradient(90deg, #16a34a, #4ade80); }
  /* Reports page graphs: clean flat purple on a soft white track (no gradients). */
  [data-portal-view="reports"] .analytics-track { background: #f3f0fb; }
  [data-portal-view="reports"] .analytics-fill { background: var(--teal); }
  [data-portal-view="reports"] .analytics-bar-head span:last-child { color: var(--teal-dark); }
  .activity-list { display: grid; gap: 10px; }
  .activity-row { display: grid; grid-template-columns: 34px minmax(0,1fr) auto; gap: 12px; align-items: center; padding: 10px 12px; border: 1px solid var(--line); border-radius: 10px; background: var(--soft); }
  .activity-dot { width: 34px; height: 34px; display: grid; place-items: center; border-radius: 10px; background: rgba(124,58,237,.12); color: var(--teal); font-weight: 950; }
  .activity-row.pending .activity-dot { background: rgba(217,119,6,.16); color: #b45309; }
  .activity-row.suspended .activity-dot { background: rgba(220,43,75,.14); color: var(--red); }
  .activity-main { min-width: 0; display: grid; gap: 2px; }
  .activity-main strong { font-size: 14px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
  .activity-main span { color: var(--muted); font-size: 12px; font-weight: 800; }
  .activity-time { color: var(--muted); font-size: 12px; font-weight: 800; white-space: nowrap; }
  .quick-actions { display: grid; grid-template-columns: repeat(2, minmax(0,1fr)); gap: 10px; }
  .quick-action { display: grid; gap: 4px; padding: 14px; border: 1px solid var(--line); border-radius: 12px; background: var(--soft); text-align: left; cursor: pointer; transition: border-color .14s ease, transform .12s ease, box-shadow .14s ease; }
  .quick-action:hover { border-color: rgba(124,58,237,.5); transform: translateY(-2px); box-shadow: 0 10px 22px rgba(15,23,42,.08); }
  .quick-action strong { font-size: 14px; color: var(--ink); }
  .quick-action span { color: var(--muted); font-size: 12px; font-weight: 800; }
  .info-line { display: flex; justify-content: space-between; gap: 12px; padding: 11px 0; border-bottom: 1px dashed var(--line); font-weight: 800; }
  .info-line:last-child { border-bottom: 0; }
  .info-line span { color: var(--muted); }
  .info-line strong { color: var(--ink); text-align: right; }
  .license-card { display: grid; gap: 4px; padding: 16px; border: 1px solid var(--line); border-radius: 12px; background: var(--soft); }
  .license-card.expired { border-color: #fecdd3; background: #fff8f9; }
  .license-card.expiring { border-color: #fed7aa; background: #fffaf3; }
  .license-card-head { display: flex; align-items: center; justify-content: space-between; gap: 10px; }
  .validity-bar { height: 8px; border-radius: 999px; background: #eef2f7; overflow: hidden; margin-top: 8px; }
  .validity-fill { height: 100%; background: linear-gradient(90deg, var(--teal), #22c55e); }
  .validity-fill.warn { background: linear-gradient(90deg, #f59e0b, #fbbf24); }
  .validity-fill.danger { background: linear-gradient(90deg, #dc2626, #f87171); }
  .portal-tabs { display: flex; gap: 8px; flex-wrap: wrap; margin-bottom: 4px; }
  .portal-tab { height: 40px; padding: 0 16px; border: 1px solid var(--line); border-radius: 999px; background: #fff; color: var(--muted); font-weight: 900; cursor: pointer; }
  .portal-tab.active { background: var(--teal); color: #fff; border-color: var(--teal); }
  @media (max-width: 1000px) {
    .kpi-grid { grid-template-columns: repeat(2, minmax(0, 1fr)); }
    .dash-grid { grid-template-columns: 1fr; }
  }
  @media (max-width: 680px) {
    .kpi-grid, .quick-actions { grid-template-columns: 1fr; }
  }

  /* ---- Enterprise admin app shell (login + sidebar + pages) ---- */
  .admin-login-layer { min-height: 100vh; display: grid; place-items: center; padding: 24px; }
  .admin-login-card {
    width: 100%;
    max-width: 940px;
    display: grid;
    grid-template-columns: 1.05fr .95fr;
    background: var(--surface);
    border: 1px solid var(--line);
    border-radius: 18px;
    overflow: hidden;
    box-shadow: 0 30px 80px rgba(15, 23, 42, .18);
  }
  .admin-login-brand {
    padding: 40px;
    color: #fff;
    background: linear-gradient(150deg, #0b1220 0%, #7c3aed 92%);
    display: grid;
    align-content: space-between;
    gap: 28px;
  }
  .admin-login-brand .eyebrow, .admin-login-brand p { color: rgba(255,255,255,.86); }
  .admin-login-brand h2 { font-size: 30px; line-height: 1.1; }
  .admin-login-points { display: grid; gap: 10px; }
  .admin-login-points div { padding: 11px 13px; border-radius: 9px; background: rgba(255,255,255,.12); border: 1px solid rgba(255,255,255,.2); font-weight: 800; }
  .admin-login-form { padding: 40px; display: grid; align-content: center; gap: 16px; }
  .app-shell { display: grid; grid-template-columns: 254px minmax(0, 1fr); min-height: 100vh; }
  .app-sidebar {
    position: sticky;
    top: 0;
    align-self: start;
    height: 100vh;
    display: grid;
    grid-template-rows: auto 1fr auto;
    gap: 8px;
    padding: 20px 16px;
    background: #0b1220;
    color: #e2e8f0;
    overflow-y: auto;
  }
  .app-sidebar .brand-lockup h1 { color: #fff; font-size: 17px; }
  .app-sidebar .brand-lockup p { color: #94a3b8; font-size: 12px; }
  .sidebar-nav { display: grid; gap: 4px; align-content: start; margin-top: 8px; }
  .sidebar-section-label { color: #64748b; font-size: 10px; font-weight: 950; text-transform: uppercase; letter-spacing: .08em; padding: 12px 12px 4px; }
  .sidebar-link {
    display: flex; align-items: center; gap: 11px;
    padding: 11px 12px; border-radius: 9px;
    color: #cbd5e1; background: transparent; border: 0;
    font-weight: 850; font-size: 14px; cursor: pointer; text-align: left; width: 100%;
  }
  .sidebar-link:hover { background: rgba(255,255,255,.06); color: #fff; }
  .sidebar-link.active { background: linear-gradient(135deg, #8b5cf6, #7c3aed); color: #fff; }
  .sidebar-link .sidebar-ic { width: 22px; height: 22px; display: grid; place-items: center; border-radius: 6px; background: rgba(255,255,255,.1); font-size: 12px; font-weight: 950; }
  .sidebar-link.active .sidebar-ic { background: rgba(255,255,255,.22); }
  .sidebar-foot { display: grid; gap: 8px; border-top: 1px solid rgba(255,255,255,.1); padding-top: 12px; }
  .sidebar-foot .nav-link { background: rgba(255,255,255,.08); color: #e2e8f0; border-color: rgba(255,255,255,.14); }
  .sidebar-badge { font-size: 11px; color: #94a3b8; font-weight: 800; }
  .portal-fullbleed { width: 100%; }
  .app-main { min-width: 0; background: linear-gradient(180deg, #faf8ff, #f4f2fb); }
  .app-main-inner { max-width: 1400px; margin: 0 auto; display: grid; align-content: start; gap: 22px; padding: 34px 48px 56px; min-height: 100vh; }
  .app-topbar { display: flex; align-items: flex-end; justify-content: space-between; gap: 16px; flex-wrap: wrap; padding-bottom: 20px; border-bottom: 1px solid var(--line); margin-bottom: 4px; }
  .app-topbar h2 { font-size: 26px; letter-spacing: -.02em; }
  .app-topbar .section-title p { font-size: 14px; }
  .admin-page-view { display: none; }
  .admin-page-view.active { display: grid; gap: 22px; animation: gaFadeIn .35s ease both; }
  .admin-page-view .kpi-grid { gap: 18px; }
  .admin-page-view .card.panel { padding: 26px; }
  .settings-form-grid { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 14px; }
  @media (max-width: 1100px) {
    .app-main-inner { padding: 28px 30px 44px; }
  }
  @media (max-width: 900px) {
    .admin-login-card { grid-template-columns: 1fr; }
    .admin-login-brand { display: none; }
    .app-shell { grid-template-columns: 1fr; }
    .app-sidebar { position: static; height: auto; grid-template-rows: none; }
    .sidebar-nav { grid-auto-flow: row; }
    .settings-form-grid { grid-template-columns: 1fr; }
  }
  @media (max-width: 640px) {
    .app-main-inner { padding: 20px 16px 36px; }
    .app-topbar h2 { font-size: 22px; }
  }

  /* ---- Premium polish layer + animations ---- */
  @keyframes gaFadeUp { from { opacity: 0; transform: translateY(12px); } to { opacity: 1; transform: translateY(0); } }
  @keyframes gaFadeIn { from { opacity: 0; } to { opacity: 1; } }
  @keyframes gaScaleIn { from { opacity: 0; transform: scale(.97); } to { opacity: 1; transform: scale(1); } }
  @keyframes gaFloat { 0%,100% { transform: translate3d(0,0,0); } 50% { transform: translate3d(0,-8px,0); } }
  @keyframes gaShimmer { 0% { background-position: -200% 0; } 100% { background-position: 200% 0; } }
  .anim-fade-up { animation: gaFadeUp .5s cubic-bezier(.22,.61,.36,1) both; }
  .anim-fade-in { animation: gaFadeIn .5s ease both; }
  .anim-scale-in { animation: gaScaleIn .45s cubic-bezier(.22,.61,.36,1) both; }
  .anim-delay-1 { animation-delay: .06s; }
  .anim-delay-2 { animation-delay: .12s; }
  .anim-delay-3 { animation-delay: .18s; }
  .anim-delay-4 { animation-delay: .24s; }
  @media (prefers-reduced-motion: reduce) {
    .anim-fade-up, .anim-fade-in, .anim-scale-in { animation: none; }
  }

  /* Smoother card + button + input feel across all pages */
  .card { transition: box-shadow .2s ease, transform .2s ease, border-color .2s ease; }
  .plan-card, .kpi, .quick-action, .license-card, .metric { transition: transform .16s ease, box-shadow .18s ease, border-color .16s ease; }
  button, a.button, .nav-link, .sidebar-link, .portal-tab, .row-icon-btn { transition: background .16s ease, color .16s ease, border-color .16s ease, transform .12s ease, box-shadow .16s ease, filter .16s ease; }
  button.primary:hover, a.button.primary:hover { box-shadow: 0 10px 22px rgba(124,58,237,.28); transform: translateY(-1px); }
  button:active, a.button:active { transform: translateY(0); }
  input, select, textarea { transition: border-color .16s ease, box-shadow .16s ease, background .16s ease; }

  /* Brand mark refinements + optional GA billing wordmark */
  .brand-mark { position: relative; letter-spacing: .5px; }
  .brand-wordmark { display: grid; gap: 1px; }
  .brand-wordmark strong { font-size: 18px; font-weight: 950; letter-spacing: -.01em; color: var(--ink); }
  .brand-wordmark span { font-size: 11px; font-weight: 850; color: var(--muted); letter-spacing: .06em; text-transform: uppercase; }

  /* Professional clients table (replaces stacked boxes) */
  .client-table { display: grid; gap: 0; border: 1px solid var(--line); border-radius: 14px; overflow: hidden; background: var(--surface); box-shadow: 0 12px 30px rgba(15,23,42,.06); }
  .client-table-head, .client-row {
    display: grid;
    grid-template-columns: minmax(0, 1.5fr) minmax(0, 1.1fr) 120px 130px 96px;
    gap: 14px;
    align-items: center;
    padding: 14px 18px;
  }
  .client-table-head { background: #f8fafc; border-bottom: 1px solid var(--line); color: var(--muted); font-size: 11px; font-weight: 950; text-transform: uppercase; letter-spacing: .04em; }
  .client-row { border-bottom: 1px solid #eef2f7; cursor: pointer; }
  .client-row:last-child { border-bottom: 0; }
  .client-row:hover { background: #f8fbfc; }
  .client-row.open { background: #f1f8f9; }
  .client-row.suspended { background: #fff8f9; }
  .client-cell-main { display: flex; align-items: center; gap: 12px; min-width: 0; }
  .client-avatar { width: 40px; height: 40px; flex: 0 0 auto; display: grid; place-items: center; border-radius: 11px; background: linear-gradient(135deg, #8b5cf6, #7c3aed); color: #fff; font-weight: 950; font-size: 15px; }
  .client-name { min-width: 0; display: grid; gap: 2px; }
  .client-name strong { font-size: 15px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
  .client-name small { color: var(--muted); font-size: 12px; font-weight: 800; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
  .client-contact { min-width: 0; display: grid; gap: 2px; font-weight: 800; }
  .client-contact span { color: var(--muted); font-size: 12px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
  .client-plan-cell { font-weight: 900; font-size: 13px; }
  .client-caret { justify-self: end; width: 28px; height: 28px; display: grid; place-items: center; border-radius: 8px; background: #eef2f7; color: #475569; font-weight: 950; transition: transform .18s ease; }
  .client-row.open .client-caret { transform: rotate(180deg); }
  .client-drawer { display: none; padding: 0 18px 18px; border-bottom: 1px solid #eef2f7; background: #fbfdfe; animation: gaFadeIn .25s ease both; }
  .client-drawer.open { display: block; }
  .client-drawer-inner { padding-top: 16px; display: grid; gap: 14px; }
  .client-detail-grid { display: grid; grid-template-columns: repeat(3, minmax(0,1fr)); gap: 12px; }
  .client-detail { padding: 12px 14px; border: 1px solid var(--line); border-radius: 10px; background: #fff; display: grid; gap: 4px; }
  .client-detail span { color: var(--muted); font-size: 11px; font-weight: 900; text-transform: uppercase; letter-spacing: .03em; }
  .client-detail strong { font-size: 14px; }
  .client-empty-search { padding: 34px; text-align: center; color: var(--muted); font-weight: 900; }
  @media (max-width: 900px) {
    .client-table-head { display: none; }
    .client-row { grid-template-columns: 1fr auto; gap: 10px; }
    .client-row .client-contact, .client-row .client-plan-cell, .client-row .client-status-cell { display: none; }
    .client-detail-grid { grid-template-columns: 1fr; }
  }

  /* Signup (GA Billing) premium onboarding */
  .signup-page { min-height: 100vh; display: grid; grid-template-columns: 1fr 1fr; }
  .signup-brand-side {
    position: relative; overflow: hidden; padding: 56px 52px;
    color: #fff; display: grid; align-content: space-between; gap: 34px;
    background: linear-gradient(150deg, #0b1220 0%, #4c1d95 45%, #7c3aed 100%);
  }
  .signup-brand-side::before, .signup-brand-side::after {
    content: ""; position: absolute; border-radius: 50%; filter: blur(2px); animation: gaFloat 9s ease-in-out infinite;
  }
  .signup-brand-side::before { width: 320px; height: 320px; right: -120px; top: -80px; background: radial-gradient(circle, rgba(139,92,246,.4), transparent 70%); }
  .signup-brand-side::after { width: 280px; height: 280px; left: -110px; bottom: -90px; background: radial-gradient(circle, rgba(124,58,237,.45), transparent 70%); animation-delay: 1.5s; }
  .signup-brand-content { position: relative; z-index: 1; display: grid; gap: 22px; }
  .signup-brand-content h1 { font-size: 40px; line-height: 1.05; letter-spacing: -.02em; }
  .signup-brand-content p { color: rgba(255,255,255,.85); font-size: 15px; line-height: 1.55; }
  .signup-logo { display: flex; align-items: center; gap: 14px; position: relative; z-index: 1; }
  .signup-logo .ga-mark {
    width: 56px; height: 56px; display: grid; place-items: center; border-radius: 16px;
    background: linear-gradient(135deg, #8b5cf6, #7c3aed); font-weight: 950; font-size: 22px;
    box-shadow: 0 14px 34px rgba(0,0,0,.35);
  }
  .signup-logo .ga-logo-img {
    width: 56px; height: 56px; flex: 0 0 auto; border-radius: 16px; padding: 9px;
    object-fit: contain; background: #0b1220; box-shadow: 0 14px 34px rgba(0,0,0,.35);
  }
  .signup-logo-text strong { display: block; font-size: 20px; font-weight: 950; letter-spacing: -.01em; }
  .signup-logo-text span { font-size: 12px; color: rgba(255,255,255,.75); font-weight: 850; letter-spacing: .12em; text-transform: uppercase; }
  .signup-feature-list { position: relative; z-index: 1; display: grid; gap: 12px; }
  .signup-feature { display: flex; align-items: center; gap: 12px; font-weight: 850; }
  .signup-feature .tick { width: 28px; height: 28px; flex: 0 0 auto; display: grid; place-items: center; border-radius: 999px; background: rgba(255,255,255,.16); border: 1px solid rgba(255,255,255,.28); font-weight: 950; }
  .signup-form-side { padding: 48px 52px; display: grid; align-content: center; gap: 18px; overflow-y: auto; }
  .signup-form-card { width: 100%; max-width: 460px; margin: 0 auto; display: grid; gap: 16px; }
  .signup-field-grid { display: grid; grid-template-columns: 1fr 1fr; gap: 14px; }
  .signup-field-grid .full { grid-column: 1 / -1; }
  .signup-plan-strip { display: flex; gap: 8px; flex-wrap: wrap; }
  .signup-plan-chip { padding: 8px 12px; border: 1px solid var(--line); border-radius: 999px; font-weight: 900; font-size: 12px; color: var(--muted); background: var(--soft); }
  .signup-brand-footer { position: relative; z-index: 1; font-size: 13px; font-weight: 800; color: rgba(255,255,255,.7); }
  .signup-form-head h2 { font-size: 26px; letter-spacing: -.02em; margin-top: 10px; }
  .signup-form-head p { color: var(--muted); font-weight: 700; margin-top: 6px; font-size: 14px; }
  .signup-form-card label { display: grid; gap: 7px; font-weight: 850; font-size: 13px; color: #0b1220; }
  .signup-form-card input {
    height: 48px; padding: 0 15px; border-radius: 12px; border: 1.5px solid var(--line);
    background: #fff; font-size: 14px; font-weight: 650; transition: border-color .18s, box-shadow .18s, transform .18s;
  }
  .signup-form-card input:focus { outline: none; border-color: var(--brand); box-shadow: 0 0 0 4px rgba(124,58,237,.14); }
  .signup-submit { height: 52px; font-size: 15px; margin-top: 4px; }
  .signup-submit:hover { transform: translateY(-2px); }
  .signup-links a { transition: color .18s; }
  @media (max-width: 860px) {
    .signup-page { grid-template-columns: 1fr; }
    .signup-brand-side { padding: 40px 28px; }
    .signup-form-side { padding: 32px 22px; }
    .signup-field-grid { grid-template-columns: 1fr; }
  }
`;

function createConnectHtml(request) {
  const config = getPublicConfig(request);
  const updateBadgeClass = config.updates.ready ? 'good' : 'warn';
  const updateBadgeText = config.updates.ready
    ? `Windows update ready${config.updates.version ? ` / v${config.updates.version}` : ''}`
    : 'Windows update files missing';
  const endpointCards = [
    ['Cloud URL for app', config.desktop.cloudUrl],
    ['Client signup', config.endpoints.signup],
    ['Client portal', config.endpoints.portal],
    ['GI admin panel', config.endpoints.admin],
    ['Windows download', config.endpoints.downloadWindows || config.desktop.downloadWindows],
    ['Updater feed', config.endpoints.latestYml],
  ];

  return `<!doctype html>
<html>
<head>
  <meta charset="utf-8">
  <meta name="viewport" content="width=device-width, initial-scale=1">
  <title>GI POS - Cloud Connection</title>
  <link rel="icon" href="${SITE_LOGO_URL}">
  <style>${BASE_STYLES}</style>
</head>
<body>
  <header>
    <a class="brand-lockup" href="/" style="text-decoration:none;color:inherit">
      <img class="brand-logo-img" src="${SITE_LOGO_URL}" alt="GI POS" onerror="this.style.display='none';this.nextElementSibling.style.display='grid'">
      <div class="brand-mark" style="display:none">GI</div>
      <div>
        <h1>GI POS Cloud Connect</h1>
        <p>Domain, download, portal, and update check</p>
      </div>
    </a>
    <div class="nav-actions">
      <a class="nav-link primary" href="/portal">Client Portal</a>
      <a class="nav-link" href="/admin">Admin Panel</a>
      <a class="nav-link" href="/signup">Signup</a>
    </div>
  </header>
  <main>
    <div class="layout">
      <section class="card panel">
        <div class="section-head">
          <div class="stack">
            <span class="eyebrow">Connection Ready</span>
            <h2>${escapeHtml(config.baseUrl)}</h2>
            <p>Use this exact domain as the desktop app Cloud URL. Local IP addresses are only for LAN/local-server testing.</p>
          </div>
          <span class="badge good">Cloud API online</span>
        </div>
        <div class="status ok">Health check passed from this server page.</div>
      </section>

      <section class="three-grid">
        <article class="card panel">
          <span class="eyebrow">Desktop App</span>
          <h3>Cloud URL</h3>
          <div class="status">${escapeHtml(config.desktop.cloudUrl)}</div>
          <p>Enter this on first login, reinstall restore, or account change.</p>
        </article>
        <article class="card panel">
          <span class="eyebrow">Auto Update</span>
          <h3>Windows Update</h3>
          <span class="badge ${updateBadgeClass}">${escapeHtml(updateBadgeText)}</span>
          <p>${escapeHtml(config.updates.setup || 'Upload latest.yml, setup exe, and exe.blockmap from admin panel.')}</p>
        </article>
        <article class="card panel">
          <span class="eyebrow">Plans</span>
          <h3>Four subscription plans</h3>
          <p>Premium, Gold, and Offline serve Windows POS. Android is for the native mobile billing app.</p>
        </article>
      </section>

      <section class="card panel">
        <div class="section-head">
          <div>
            <span class="eyebrow">Important URLs</span>
            <h2>Open these links to confirm setup</h2>
          </div>
          <a class="button" href="/api/v1/public/config" target="_blank" rel="noreferrer">JSON Config</a>
        </div>
        <div class="grid">
          ${endpointCards
            .map(
              ([label, href]) => `
                <a class="button" href="${escapeHtml(href)}" target="_blank" rel="noreferrer">
                  <span>${escapeHtml(label)}</span>
                </a>
              `,
            )
            .join('')}
        </div>
      </section>

      <section class="card panel">
        <span class="eyebrow">Recommended Flow</span>
        <div class="step-list" style="color:#08111f">
          <div style="background:#f8fafc;border-color:#dbe3ee">1. Client signs up from the public signup page.</div>
          <div style="background:#f8fafc;border-color:#dbe3ee">2. GI admin approves plan and expiry.</div>
          <div style="background:#f8fafc;border-color:#dbe3ee">3. The approved Windows or Android app signs in and activates.</div>
          <div style="background:#f8fafc;border-color:#dbe3ee">4. Enable Auto Sync after first successful restore.</div>
        </div>
      </section>
    </div>
  </main>
</body>
</html>`;
}

const SITE_LOGO_URL = 'https://image.gihostings.com/i/566b05f14736ebf2.png';
// Product screenshots for the hero device mockups. Set these to public image URLs
// (like the logo above). When empty, a styled CSS mockup is shown as a fallback.
const SITE_SHOT_DESKTOP = 'https://image.gihostings.com/i/d9a30614020f9dec.png';
const SITE_SHOT_MOBILE = 'https://image.gihostings.com/i/75406c7175c305fb.png';
const SITE_BRAND = 'GI POS';
const SITE_CONTACT = {
  phone: '+91 95672 27005',
  phoneHref: '+919567227005',
  email: 'gihostings@gmail.com',
  sales: 'gihostings@gmail.com',
  whatsapp: '+919567227005',
  hours: 'Mon-Sat, 9:00 AM - 8:00 PM IST',
  address: 'GI Hostings, India',
};

const SITE_STYLES = `
  :root {
    --brand-teal: #7c3aed;
    --brand-teal-dark: #6d28d9;
    --brand-red: #7c3aed;
    --brand-ink: #1e1b2e;
    --brand-grad: linear-gradient(120deg, #8b5cf6 0%, #6d28d9 100%);
  }
  html { scroll-behavior: smooth; }
  .site-body { background: #ffffff; color: var(--ink); }
  .site-nav {
    position: sticky; top: 0; z-index: 60;
    display: flex; align-items: center; justify-content: space-between; gap: 18px;
    padding: 14px 44px; background: rgba(255,255,255,.86); backdrop-filter: blur(16px);
    border-bottom: 1px solid var(--line);
  }
  .site-logo { display: flex; align-items: center; gap: 11px; }
  .site-logo img { height: 38px; width: auto; display: block; }
  .site-logo .logo-fallback { width: 42px; height: 42px; display: grid; place-items: center; border-radius: 11px; background: var(--brand-grad); color: #fff; font-weight: 950; font-size: 16px; }
  .site-logo .logo-text { display: grid; gap: 1px; }
  .site-logo .logo-text strong { font-size: 17px; font-weight: 950; letter-spacing: -.01em; line-height: 1; }
  .site-logo .logo-text span { font-size: 10px; color: var(--muted); font-weight: 850; letter-spacing: .12em; text-transform: uppercase; }
  .site-nav-links { display: flex; align-items: center; gap: 4px; }
  .site-nav-links a { padding: 9px 13px; border-radius: 8px; color: #334155; font-weight: 850; font-size: 14px; }
  .site-nav-links a:hover { background: #f1f5f9; color: var(--brand-teal-dark); }
  .site-nav-links a.active { color: var(--brand-teal-dark); background: rgba(124,58,237,.1); }
  .site-nav-actions { display: flex; align-items: center; gap: 10px; }
  .site-nav-toggle { display: none; width: 42px; height: 42px; place-items: center; border-radius: 9px; border: 1px solid var(--line); background: #fff; font-size: 18px; font-weight: 950; cursor: pointer; }
  .btn { height: 44px; display: inline-flex; align-items: center; justify-content: center; gap: 8px; padding: 0 18px; border-radius: 10px; font-weight: 900; font-size: 14px; border: 1px solid var(--line); background: #fff; color: var(--ink); cursor: pointer; transition: transform .14s ease, box-shadow .16s ease, filter .16s ease, background .16s ease; }
  .btn:hover { transform: translateY(-1px); }
  .btn-primary { background: var(--brand-grad); color: #fff; border-color: transparent; box-shadow: 0 10px 24px rgba(124,58,237,.24); }
  .btn-primary:hover { filter: brightness(1.05); box-shadow: 0 14px 30px rgba(124,58,237,.32); }
  .btn-ghost { background: transparent; }
  .btn-lg { height: 54px; padding: 0 28px; font-size: 15.5px; }
  .site-main { display: block; }
  .sect { padding: 88px 44px; }
  .sect.tint { background: linear-gradient(180deg, #faf8ff, #f1ecfe); border-top: 1px solid var(--line); border-bottom: 1px solid var(--line); }
  .wrap { max-width: 1180px; margin: 0 auto; }
  .sect-head { max-width: 720px; margin: 0 auto 52px; text-align: center; display: grid; gap: 14px; justify-items: center; }
  .kicker { display: inline-flex; align-items: center; gap: 7px; padding: 7px 13px; border-radius: 999px; background: rgba(124,58,237,.1); border: 1px solid rgba(124,58,237,.24); color: var(--brand-teal-dark); font-weight: 900; font-size: 12.5px; letter-spacing: .02em; }
  .sect-head h2 { font-size: 38px; letter-spacing: -.025em; line-height: 1.08; }
  .sect-head p { font-size: 16.5px; color: var(--muted); font-weight: 700; line-height: 1.6; }
  .grad-text { background: var(--brand-grad); -webkit-background-clip: text; background-clip: text; -webkit-text-fill-color: transparent; }

  /* Hero */
  .hero { position: relative; overflow: hidden; padding: 76px 44px 80px; background:
      radial-gradient(circle at 12% 0%, rgba(139,92,246,.08), transparent 38%),
      radial-gradient(circle at 92% 8%, rgba(124,58,237,.12), transparent 44%),
      linear-gradient(180deg, #faf8ff, #efeafe); }
  .hero-grid { max-width: 1220px; margin: 0 auto; display: grid; grid-template-columns: 1fr 1fr; gap: 40px; align-items: center; }
  .hero-copy { display: grid; gap: 22px; }
  .hero-copy h1 { font-size: 54px; line-height: 1.03; letter-spacing: -.03em; }
  .hero-copy p.lead { font-size: 18px; color: var(--muted); font-weight: 700; line-height: 1.6; max-width: 540px; }
  .hero-cta { display: flex; gap: 12px; flex-wrap: wrap; }
  .hero-points { display: flex; gap: 18px; flex-wrap: wrap; color: var(--muted); font-weight: 850; font-size: 13.5px; }
  .hero-points span { display: inline-flex; align-items: center; gap: 7px; }
  .hero-points span::before { content: ""; width: 8px; height: 8px; border-radius: 999px; background: var(--brand-teal); }

  /* Device mockups - desktop + phone only */
  .hero-devices { position: relative; height: 440px; }
  .dv { position: absolute; box-shadow: 0 44px 100px rgba(15,23,42,.28); backface-visibility: hidden; }
  .dv img.shot {
    display: block; width: 100%; height: auto;
    object-fit: cover; object-position: top center;
    image-rendering: auto;
  }
  .dv-desktop {
    top: 30px; left: 0; width: 100%; max-width: 600px;
    border: 12px solid #0b1220; border-radius: 16px; background: #0b1220; overflow: hidden;
    transform: translateZ(0);
    animation: gaFloat 7s ease-in-out infinite;
  }
  .dv-desktop .scr { aspect-ratio: 16/10; background: #f4f7fb; display: grid; grid-template-columns: 58px 1fr 150px; }
  .dv-desktop .base { position: absolute; left: 50%; bottom: -14px; transform: translateX(-50%); width: 116%; height: 14px; border-radius: 0 0 14px 14px; background: linear-gradient(180deg,#cbd5e1,#94a3b8); }
  .dv-phone {
    right: 0; bottom: -8px; width: 180px; z-index: 3;
    border: 10px solid #0b1220; border-radius: 30px; background: #0b1220; overflow: hidden;
    transform: translateZ(0);
    animation: gaFloat 7s ease-in-out infinite; animation-delay: .8s;
  }
  .dv-phone .scr { aspect-ratio: 9/19; background: #f4f7fb; display: grid; grid-template-rows: 30px 1fr 40px; }
  .m-rail { background: #0e1626; display: grid; gap: 7px; padding: 10px 7px; align-content: start; }
  .m-rail i { height: 26px; border-radius: 6px; background: rgba(255,255,255,.09); }
  .m-rail i.on { background: var(--brand-grad); }
  .m-top { background: #fff; border-bottom: 1px solid #e2e8f0; }
  .m-body { padding: 12px; display: grid; gap: 9px; align-content: start; }
  .m-line { height: 10px; border-radius: 5px; background: #e2e8f0; }
  .m-line.sm { width: 42%; } .m-line.md { width: 66%; } .m-line.lg { width: 88%; }
  .m-tiles { display: grid; grid-template-columns: repeat(3,1fr); gap: 7px; }
  .m-tiles.two { grid-template-columns: repeat(2,1fr); }
  .m-tile { height: 40px; border-radius: 7px; background: #fff; border: 1px solid #e6ebf1; box-shadow: 0 2px 5px rgba(15,23,42,.05); }
  .m-tile.hot { background: linear-gradient(135deg, rgba(139,92,246,.12), rgba(124,58,237,.14)); border-color: rgba(124,58,237,.3); }
  .m-cart { background: #fff; border-left: 1px solid #e2e8f0; padding: 11px; display: grid; gap: 7px; align-content: start; }
  .m-nav { background: #fff; border-top: 1px solid #e2e8f0; display: grid; grid-template-columns: repeat(4,1fr); gap: 6px; padding: 8px; }
  .m-nav i { height: 24px; border-radius: 7px; background: #eef2f7; }
  .m-nav i:first-child { background: var(--brand-grad); }
  .m-phonetop, .m-tablettop { background: #0e1626; }

  /* Feature / card grids */
  .grid-3 { display: grid; grid-template-columns: repeat(3, minmax(0,1fr)); gap: 20px; }
  .grid-2 { display: grid; grid-template-columns: repeat(2, minmax(0,1fr)); gap: 20px; }
  .feat { padding: 26px; border: 1px solid var(--line); border-radius: 16px; background: #fff; display: grid; gap: 11px; box-shadow: 0 14px 34px rgba(15,23,42,.05); }
  .feat .ic { width: 48px; height: 48px; display: grid; place-items: center; border-radius: 13px; background: linear-gradient(135deg, rgba(139,92,246,.12), rgba(124,58,237,.16)); color: var(--brand-teal-dark); font-weight: 950; font-size: 18px; }
  .feat h3 { font-size: 17.5px; }
  .feat p { font-size: 14px; color: var(--muted); font-weight: 700; line-height: 1.6; }
  .stat-grid { display: grid; grid-template-columns: repeat(4, minmax(0,1fr)); gap: 18px; }
  .stat { padding: 22px; border: 1px solid var(--line); border-radius: 16px; background: #fff; text-align: center; box-shadow: 0 12px 30px rgba(15,23,42,.05); }
  .stat strong { display: block; font-size: 34px; font-weight: 950; letter-spacing: -.02em; background: var(--brand-grad); -webkit-background-clip: text; background-clip: text; -webkit-text-fill-color: transparent; }
  .stat span { color: var(--muted); font-weight: 850; font-size: 13px; }
  .split { display: grid; grid-template-columns: 1fr 1fr; gap: 44px; align-items: center; }
  .why-list { display: grid; gap: 14px; }
  .why-item { display: flex; gap: 14px; align-items: flex-start; }
  .why-item .ck { width: 30px; height: 30px; flex: 0 0 auto; display: grid; place-items: center; border-radius: 999px; background: rgba(124,58,237,.12); color: var(--brand-teal-dark); font-weight: 950; }
  .why-item h4 { font-size: 15.5px; margin-bottom: 2px; }
  .why-item p { font-size: 13.5px; color: var(--muted); font-weight: 700; line-height: 1.55; }

  /* Pricing */
  .price-grid { display: grid; grid-template-columns: repeat(4, minmax(0,1fr)); gap: 22px; align-items: stretch; }
  .price {
    position: relative; padding: 30px 26px; border: 1px solid var(--line); border-radius: 22px; background: #fff;
    display: flex; flex-direction: column; gap: 18px; box-shadow: 0 14px 34px rgba(15,23,42,.05);
    transition: transform .28s cubic-bezier(.22,.61,.36,1), box-shadow .28s, border-color .28s;
  }
  .price:hover { transform: translateY(-8px); box-shadow: 0 30px 60px rgba(15,23,42,.13); border-color: rgba(124,58,237,.35); }
  .price.featured { border-color: rgba(124,58,237,.55); box-shadow: 0 26px 58px rgba(124,58,237,.2); transform: translateY(-8px); }
  .price.featured::before { content: ""; position: absolute; inset: 0; border-radius: 22px; padding: 1.5px; background: var(--brand-grad); -webkit-mask: linear-gradient(#fff 0 0) content-box, linear-gradient(#fff 0 0); -webkit-mask-composite: xor; mask-composite: exclude; pointer-events: none; }
  .price.featured:hover { transform: translateY(-12px); }
  .price .price-icon { width: 52px; height: 52px; border-radius: 15px; display: grid; place-items: center; background: linear-gradient(135deg, rgba(139,92,246,.16), rgba(124,58,237,.12)); border: 1px solid rgba(124,58,237,.16); box-shadow: 0 8px 20px rgba(124,58,237,.1); }
  .price .price-icon svg { display: block; }
  .price .tier { font-size: 22px; font-weight: 950; letter-spacing: -.01em; }
  .price .tier-sub { color: var(--muted); font-size: 13px; font-weight: 800; margin-top: -8px; }
  .price .tier-amt { font-size: 15px; font-weight: 900; color: var(--brand); }
  .price ul { margin: 0; padding: 0; list-style: none; display: grid; gap: 11px; color: #475569; font-weight: 700; font-size: 13.5px; }
  .price ul li { position: relative; padding-left: 26px; line-height: 1.45; }
  .price ul li::before { content: "\\2713"; position: absolute; left: 0; top: 0; width: 18px; height: 18px; display: grid; place-items: center; border-radius: 999px; background: rgba(124,58,237,.12); color: var(--brand); font-size: 11px; font-weight: 950; }
  .price .btn { margin-top: auto; }
  .price .ribbon { position: absolute; top: 18px; right: 18px; padding: 5px 12px; border-radius: 999px; background: var(--brand-grad); color: #fff; font-size: 11px; font-weight: 950; box-shadow: 0 8px 20px rgba(124,58,237,.35); }

  /* FAQ */
  .faq-list { max-width: 820px; margin: 0 auto; display: grid; gap: 12px; }
  .faq { border: 1px solid var(--line); border-radius: 12px; background: #fff; overflow: hidden; }
  .faq summary { list-style: none; cursor: pointer; padding: 18px 20px; font-weight: 900; font-size: 15px; display: flex; justify-content: space-between; gap: 12px; align-items: center; }
  .faq summary::-webkit-details-marker { display: none; }
  .faq summary::after { content: "+"; font-size: 20px; color: var(--brand-teal); font-weight: 950; }
  .faq[open] summary::after { content: "-"; }
  .faq p { padding: 0 20px 18px; color: var(--muted); font-weight: 700; line-height: 1.6; font-size: 14px; }

  /* CTA band */
  .cta-band { margin: 0 44px; border-radius: 22px; padding: 56px; text-align: center; color: #fff; background: linear-gradient(135deg, #0b1220 0%, #4c1d95 45%, #7c3aed 100%); display: grid; gap: 18px; justify-items: center; }
  .cta-band h2 { font-size: 34px; letter-spacing: -.02em; }
  .cta-band p { color: rgba(255,255,255,.85); font-weight: 700; max-width: 560px; }

  /* Page hero (inner pages) */
  .page-hero { padding: 70px 44px 40px; text-align: center; background: radial-gradient(circle at 80% 0%, rgba(124,58,237,.1), transparent 45%), linear-gradient(180deg,#faf8ff,#f1ecfe); }
  .page-hero .wrap { display: grid; gap: 14px; justify-items: center; }
  .page-hero h1 { font-size: 44px; letter-spacing: -.025em; }
  .page-hero p { font-size: 17px; color: var(--muted); font-weight: 700; max-width: 640px; }

  /* Forms */
  .form-wrap { max-width: 620px; margin: 0 auto; }
  .form-card { padding: 30px; border: 1px solid var(--line); border-radius: 18px; background: #fff; display: grid; gap: 16px; box-shadow: 0 18px 44px rgba(15,23,42,.08); }
  .form-2 { display: grid; grid-template-columns: 1fr 1fr; gap: 14px; }
  .form-2 .full { grid-column: 1 / -1; }
  .form-card label { display: grid; gap: 6px; color: #4a5568; font-size: 12.5px; font-weight: 900; }
  .form-card textarea { width: 100%; padding: 12px; border: 1px solid #cbd5e1; border-radius: 8px; font: inherit; font-weight: 700; min-height: 110px; outline: none; }
  .form-card textarea:focus { border-color: var(--brand-teal); box-shadow: 0 0 0 3px rgba(124,58,237,.16); }

  /* Contact */
  .contact-grid { display: grid; grid-template-columns: 1fr 1fr; gap: 20px; }
  .contact-card { padding: 24px; border: 1px solid var(--line); border-radius: 16px; background: #fff; display: grid; gap: 8px; box-shadow: 0 12px 30px rgba(15,23,42,.05); }
  .contact-card .ic { width: 46px; height: 46px; display: grid; place-items: center; border-radius: 12px; background: linear-gradient(135deg, rgba(139,92,246,.12), rgba(124,58,237,.16)); color: var(--brand-teal-dark); font-weight: 950; font-size: 18px; }
  .contact-card a, .contact-card strong { font-size: 16px; }

  /* Support chat widget */
  /* GI POS Assistant - CTA banner */
  .assist-cta { display: grid; grid-template-columns: 1.3fr .7fr; gap: 30px; align-items: center; padding: 40px; border-radius: 24px; background: linear-gradient(135deg, #0b1220 0%, #4c1d95 55%, #7c3aed 100%); color: #fff; box-shadow: 0 26px 60px rgba(76,29,149,.28); overflow: hidden; }
  .assist-cta .kicker { color: #fff; }
  .assist-cta h2 { font-size: 30px; letter-spacing: -.02em; margin: 8px 0 10px; }
  .assist-cta p { color: rgba(255,255,255,.86); font-weight: 700; line-height: 1.6; max-width: 560px; margin-bottom: 22px; }
  .assist-cta-art { display: grid; place-items: center; }
  .assist-orb { width: 130px; height: 130px; border-radius: 50%; display: grid; place-items: center; color: #fff; font-weight: 950; background: radial-gradient(circle at 35% 30%, rgba(255,255,255,.35), rgba(139,92,246,.4) 60%, rgba(124,58,237,.2)); box-shadow: 0 0 0 12px rgba(255,255,255,.06), 0 20px 46px rgba(0,0,0,.35); animation: gaFloat 6s ease-in-out infinite; overflow: hidden; }
  .assist-orb img { width: 84px; height: 84px; object-fit: contain; border-radius: 22px; padding: 12px; background: #0b1220; box-shadow: 0 10px 28px rgba(0,0,0,.35); }
  .magic-open { display: inline-flex; align-items: center; gap: 10px; }
  .magic-open-logo { width: 24px; height: 24px; object-fit: contain; border-radius: 7px; padding: 3px; background: rgba(255,255,255,.9); }
  .magic-spark { display: inline-block; animation: gaSpark 2.4s ease-in-out infinite; }
  @keyframes gaSpark { 0%,100% { transform: scale(1) rotate(0); opacity: 1; } 50% { transform: scale(1.25) rotate(12deg); opacity: .8; } }

  /* Floating Magic Button (logo) + welcome bubble */
  .magic-dock { position: fixed; right: 26px; bottom: 26px; z-index: 60; display: flex; align-items: center; gap: 12px; }
  .magic-fab { position: relative; width: 64px; height: 64px; border-radius: 50%; border: none; cursor: pointer; padding: 0; background: #fff; box-shadow: 0 16px 38px rgba(124,58,237,.4); display: grid; place-items: center; transition: transform .25s cubic-bezier(.22,.61,.36,1), box-shadow .25s; }
  .magic-fab::before { content: ""; position: absolute; inset: -3px; border-radius: 50%; background: var(--brand-grad); z-index: -1; }
  .magic-fab::after { content: ""; position: absolute; inset: -3px; border-radius: 50%; box-shadow: 0 0 0 0 rgba(124,58,237,.5); animation: gaPulse 2.6s ease-out infinite; }
  .magic-fab:hover { transform: translateY(-4px) scale(1.06); box-shadow: 0 22px 48px rgba(124,58,237,.55); }
  .magic-fab-logo { width: 58px; height: 58px; border-radius: 50%; object-fit: contain; padding: 9px; background: #0b1220; display: block; }
  .magic-fab-fallback { width: 58px; height: 58px; border-radius: 50%; display: grid; place-items: center; background: var(--brand-grad); color: #fff; font-weight: 950; font-size: 20px; }
  .magic-dot { position: absolute; top: 2px; right: 2px; width: 15px; height: 15px; border-radius: 50%; background: #22c55e; border: 2.5px solid #fff; box-shadow: 0 0 0 0 rgba(34,197,94,.6); animation: gaPulseDot 2s ease-out infinite; }
  @keyframes gaPulse { 0% { box-shadow: 0 0 0 0 rgba(124,58,237,.5); } 70% { box-shadow: 0 0 0 16px rgba(124,58,237,0); } 100% { box-shadow: 0 0 0 0 rgba(124,58,237,0); } }
  @keyframes gaPulseDot { 0% { box-shadow: 0 0 0 0 rgba(34,197,94,.6); } 70% { box-shadow: 0 0 0 9px rgba(34,197,94,0); } 100% { box-shadow: 0 0 0 0 rgba(34,197,94,0); } }
  .magic-hello { display: flex; align-items: center; gap: 8px; padding: 11px 12px 11px 16px; border-radius: 16px 16px 4px 16px; background: #fff; color: #0b1220; font-weight: 900; font-size: 13.5px; box-shadow: 0 16px 36px rgba(15,23,42,.16); border: 1px solid var(--line); opacity: 0; transform: translateY(8px) scale(.9); transform-origin: bottom right; pointer-events: none; transition: opacity .4s, transform .4s; }
  .magic-hello.show { opacity: 1; transform: translateY(0) scale(1); pointer-events: auto; animation: gaHelloBob 3s ease-in-out .6s infinite; }
  .magic-hello-close { border: none; background: rgba(124,58,237,.1); color: #7c3aed; width: 22px; height: 22px; border-radius: 50%; font-size: 16px; line-height: 1; cursor: pointer; flex: 0 0 auto; }
  @keyframes gaHelloBob { 0%,100% { transform: translateY(0); } 50% { transform: translateY(-4px); } }
  /* Floating WhatsApp logo button */
  .wa-fab { position: fixed; right: 30px; bottom: 104px; z-index: 60; width: 56px; height: 56px; border-radius: 50%; display: grid; place-items: center; text-decoration: none; background: #25d366; box-shadow: 0 14px 34px rgba(37,211,102,.5); transition: transform .25s, box-shadow .25s; }
  .wa-fab::after { content: ""; position: absolute; inset: 0; border-radius: 50%; box-shadow: 0 0 0 0 rgba(37,211,102,.5); animation: gaPulseWa 2.8s ease-out infinite; }
  .wa-fab:hover { transform: translateY(-3px) scale(1.06); box-shadow: 0 20px 44px rgba(37,211,102,.6); }
  @keyframes gaPulseWa { 0% { box-shadow: 0 0 0 0 rgba(37,211,102,.5); } 70% { box-shadow: 0 0 0 14px rgba(37,211,102,0); } 100% { box-shadow: 0 0 0 0 rgba(37,211,102,0); } }
  @media (max-width: 520px) { .magic-hello { display: none; } }

  /* Slide-in assistant panel */
  .assist-overlay { position: fixed; inset: 0; z-index: 70; background: rgba(11,18,32,.5); backdrop-filter: blur(3px); opacity: 0; visibility: hidden; transition: opacity .3s, visibility .3s; }
  .assist-overlay.show { opacity: 1; visibility: visible; }
  .assist-panel { position: fixed; top: 0; right: 0; z-index: 80; height: 100dvh; width: 400px; max-width: 92vw; background: #fff; box-shadow: -22px 0 60px rgba(15,23,42,.28); display: grid; grid-template-rows: auto 1fr auto auto; transform: translateX(105%); transition: transform .38s cubic-bezier(.22,.61,.36,1); }
  .assist-panel.open { transform: translateX(0); }
  .assist-head { padding: 18px 20px; color: #fff; background: linear-gradient(135deg, #0b1220, #7c3aed); display: flex; align-items: center; justify-content: space-between; gap: 12px; }
  .assist-id { display: flex; align-items: center; gap: 12px; }
  .assist-avatar { width: 42px; height: 42px; display: grid; place-items: center; border-radius: 12px; background: #0b1220; overflow: hidden; color: #fff; font-weight: 950; font-size: 16px; }
  .assist-avatar img { width: 100%; height: 100%; object-fit: contain; padding: 7px; display: block; }
  .assist-head strong { font-size: 15px; display: block; } .assist-head span { font-size: 12px; color: rgba(255,255,255,.8); font-weight: 800; }
  .assist-close { width: 34px; height: 34px; border-radius: 10px; border: none; background: rgba(255,255,255,.16); color: #fff; font-size: 22px; line-height: 1; cursor: pointer; }
  .assist-close:hover { background: rgba(255,255,255,.28); }
  .assist-body { padding: 18px; overflow-y: auto; display: grid; gap: 10px; align-content: start; background: #f8fafc; }
  .chat-msg { max-width: 88%; padding: 11px 14px; border-radius: 14px; font-weight: 700; font-size: 14px; line-height: 1.5; }
  .chat-msg.bot { background: #fff; border: 1px solid var(--line); border-bottom-left-radius: 4px; justify-self: start; }
  .chat-msg.user { background: var(--brand-grad); color: #fff; border-bottom-right-radius: 4px; justify-self: end; }
  .chat-suggest { display: flex; gap: 8px; flex-wrap: wrap; padding: 12px 18px; background: #f8fafc; border-top: 1px solid var(--line); }
  .chat-suggest button { height: 34px; padding: 0 12px; border-radius: 999px; border: 1px solid var(--line); background: #fff; font-weight: 850; font-size: 12.5px; cursor: pointer; color: var(--brand-teal-dark); }
  .chat-suggest button:hover { border-color: var(--brand); color: var(--brand); }
  .chat-input { display: flex; gap: 10px; padding: 14px 16px; border-top: 1px solid var(--line); background: #fff; }
  .chat-input input { flex: 1; height: 44px; padding: 0 14px; border-radius: 11px; border: 1.5px solid var(--line); font-weight: 650; }
  .chat-input input:focus { outline: none; border-color: var(--brand); box-shadow: 0 0 0 3px rgba(124,58,237,.14); }
  @media (max-width: 720px) { .assist-cta { grid-template-columns: 1fr; text-align: center; } .assist-cta p { margin-left: auto; margin-right: auto; } .assist-cta-art { display: none; } }

  /* Footer */
  .site-footer { padding: 56px 44px 30px; background: #0b1220; color: #cbd5e1; }
  .footer-grid { max-width: 1180px; margin: 0 auto; display: grid; grid-template-columns: 1.4fr 1fr 1fr 1fr; gap: 30px; }
  .site-footer h4 { color: #fff; font-size: 14px; margin-bottom: 14px; text-transform: uppercase; letter-spacing: .05em; }
  .site-footer a { display: block; color: #94a3b8; font-weight: 700; padding: 5px 0; font-size: 14px; }
  .site-footer a:hover { color: #fff; }
  .footer-brand p { color: #94a3b8; font-weight: 700; line-height: 1.6; font-size: 14px; margin-top: 12px; max-width: 300px; }
  .footer-bottom { max-width: 1180px; margin: 30px auto 0; padding-top: 22px; border-top: 1px solid rgba(255,255,255,.1); display: flex; justify-content: space-between; gap: 12px; flex-wrap: wrap; color: #64748b; font-weight: 800; font-size: 13px; }
  .powered-by { color: #94a3b8; }
  .powered-by a { display: inline; color: #a78bfa; font-weight: 900; letter-spacing: .02em; }
  .powered-by a:hover { color: #c4b5fd; text-decoration: underline; }

  /* Cloud status page */
  .status-overall { display: flex; align-items: center; gap: 16px; padding: 22px 26px; border-radius: 18px; border: 1px solid var(--line); background: #fff; box-shadow: 0 14px 34px rgba(15,23,42,.05); margin-bottom: 24px; }
  .status-overall .status-overall-dot { width: 16px; height: 16px; border-radius: 50%; background: #22c55e; flex: 0 0 auto; box-shadow: 0 0 0 6px rgba(34,197,94,.15); }
  .status-overall.degraded .status-overall-dot { background: #f59e0b; box-shadow: 0 0 0 6px rgba(245,158,11,.15); }
  .status-overall.outage .status-overall-dot { background: #ef4444; box-shadow: 0 0 0 6px rgba(239,68,68,.15); }
  .status-overall > div { flex: 1; display: grid; gap: 2px; }
  .status-overall strong { font-size: 19px; font-weight: 950; }
  .status-overall span { font-size: 13px; color: var(--muted); font-weight: 800; }
  .status-list { display: grid; gap: 12px; }
  .status-row { display: flex; align-items: center; justify-content: space-between; gap: 14px; padding: 16px 20px; border: 1px solid var(--line); border-radius: 14px; background: #fff; flex-wrap: wrap; }
  .status-row-main { display: flex; align-items: center; gap: 13px; }
  .status-row-main strong { display: block; font-size: 15px; font-weight: 900; }
  .status-row-main span { font-size: 12.5px; color: var(--muted); font-weight: 700; }
  .dot { width: 12px; height: 12px; border-radius: 50%; flex: 0 0 auto; display: inline-block; background: #94a3b8; }
  .dot.operational { background: #22c55e; } .dot.degraded { background: #f59e0b; } .dot.outage { background: #ef4444; }
  .status-tag { font-size: 12.5px; font-weight: 900; padding: 6px 12px; border-radius: 999px; }
  .status-tag.operational { background: rgba(34,197,94,.12); color: #15803d; }
  .status-tag.degraded { background: rgba(245,158,11,.14); color: #b45309; }
  .status-tag.outage { background: rgba(239,68,68,.12); color: #b91c1c; }
  .status-legend { display: flex; gap: 22px; justify-content: center; margin-top: 26px; color: var(--muted); font-weight: 800; font-size: 13px; flex-wrap: wrap; }
  .status-legend span { display: inline-flex; align-items: center; gap: 8px; }

  .reveal { opacity: 0; transform: translateY(24px); transition: opacity .7s cubic-bezier(.22,.61,.36,1), transform .7s cubic-bezier(.22,.61,.36,1); }
  .reveal.in { opacity: 1; transform: none; }

  @media (max-width: 1000px) {
    .hero-grid, .split, .support-grid, .contact-grid { grid-template-columns: 1fr; }
    .hero-devices { height: 400px; margin-top: 20px; }
    .grid-3 { grid-template-columns: repeat(2, minmax(0,1fr)); }
    .price-grid, .stat-grid { grid-template-columns: repeat(2, minmax(0,1fr)); }
    .price.featured { transform: none; }
    .footer-grid { grid-template-columns: 1fr 1fr; }
  }
  @media (max-width: 720px) {
    .site-nav { padding: 12px 18px; }
    .site-nav-links { position: absolute; top: 68px; left: 0; right: 0; flex-direction: column; align-items: stretch; gap: 2px; padding: 10px 18px; background: #fff; border-bottom: 1px solid var(--line); display: none; }
    .site-nav-links.open { display: flex; }
    .site-nav-toggle { display: grid; }
    .sect, .hero { padding: 52px 20px; }
    .hero-copy h1 { font-size: 36px; }
    .grid-3, .grid-2, .price-grid, .stat-grid, .form-2 { grid-template-columns: 1fr; }
    .cta-band { margin: 0 18px; padding: 36px 22px; }
    .footer-grid { grid-template-columns: 1fr; }
  }

  /* Premium entry splash */
  .site-splash { position: fixed; inset: 0; z-index: 9999; display: grid; place-items: center; overflow: hidden; background: radial-gradient(900px 620px at 50% 34%, #ffffff 0%, #f6f2ff 46%, #efe8ff 100%); transition: opacity .8s cubic-bezier(.4,0,.2,1), visibility .8s; }
  .site-splash.hide { opacity: 0; visibility: hidden; }
  .site-splash::before, .site-splash::after { content: ""; position: absolute; border-radius: 50%; filter: blur(60px); opacity: .55; animation: splashAura 7s ease-in-out infinite; }
  .site-splash::before { width: 460px; height: 460px; top: -140px; right: -120px; background: radial-gradient(circle, rgba(139,92,246,.5), transparent 70%); }
  .site-splash::after { width: 420px; height: 420px; bottom: -150px; left: -110px; background: radial-gradient(circle, rgba(124,58,237,.42), transparent 70%); animation-delay: 1.4s; }
  .splash-inner { position: relative; z-index: 1; display: flex; flex-direction: column; align-items: center; gap: 26px; }
  .splash-badge { position: relative; width: 132px; height: 132px; display: grid; place-items: center; opacity: 0; transform: scale(.72); animation: splashPop 1s cubic-bezier(.22,1.35,.36,1) .1s forwards; }
  .splash-badge::before { content: ""; position: absolute; inset: -9px; border-radius: 34px; background: conic-gradient(from 0deg, #8b5cf6, #c4b5fd, #6d28d9, #8b5cf6); filter: blur(2px); opacity: .9; animation: splashSpin 3.6s linear infinite; }
  .splash-logo { position: relative; width: 116px; height: 116px; border-radius: 28px; padding: 20px; object-fit: contain; background: #0b1220; box-shadow: 0 26px 60px rgba(76,29,149,.32), 0 0 0 1px rgba(255,255,255,.5) inset; }
  .splash-fallback { position: relative; width: 116px; height: 116px; border-radius: 28px; display: none; place-items: center; background: linear-gradient(135deg, #8b5cf6, #6d28d9); color: #fff; font-weight: 950; font-size: 44px; box-shadow: 0 26px 60px rgba(76,29,149,.32); }
  .splash-word { display: flex; gap: 3px; font-size: 40px; font-weight: 950; letter-spacing: -.01em; color: #0b1220; }
  .splash-word span { opacity: 0; transform: translateY(22px); filter: blur(8px); display: inline-block; animation: splashLetter .7s cubic-bezier(.22,.61,.36,1) forwards; }
  .splash-word span:nth-child(1){ animation-delay: .55s; } .splash-word span:nth-child(2){ animation-delay: .64s; } .splash-word span:nth-child(3){ animation-delay: .73s; } .splash-word span:nth-child(4){ animation-delay: .86s; } .splash-word span:nth-child(5){ animation-delay: .95s; }
  .splash-word .grad { background: linear-gradient(135deg, #8b5cf6, #6d28d9); -webkit-background-clip: text; background-clip: text; -webkit-text-fill-color: transparent; }
  .splash-tag { font-size: 12px; font-weight: 900; letter-spacing: .38em; text-transform: uppercase; color: #7c3aed; opacity: 0; transform: translateY(8px); animation: splashTag .7s ease 1.2s forwards; }
  .splash-line { position: relative; width: 176px; height: 3px; border-radius: 999px; overflow: hidden; background: rgba(124,58,237,.14); }
  .splash-line i { position: absolute; inset: 0; width: 45%; border-radius: 999px; background: linear-gradient(90deg, transparent, #8b5cf6, #6d28d9, transparent); animation: splashSweep 1.5s cubic-bezier(.65,0,.35,1) infinite; }
  @keyframes splashPop { to { opacity: 1; transform: scale(1); } }
  @keyframes splashSpin { to { transform: rotate(360deg); } }
  @keyframes splashLetter { to { opacity: 1; transform: translateY(0); filter: blur(0); } }
  @keyframes splashTag { to { opacity: 1; transform: translateY(0); } }
  @keyframes splashSweep { 0% { transform: translateX(-120%); } 100% { transform: translateX(320%); } }
  @keyframes splashAura { 0%,100% { transform: translate3d(0,0,0) scale(1); } 50% { transform: translate3d(0,-18px,0) scale(1.08); } }
  @media (prefers-reduced-motion: reduce) {
    .splash-badge, .splash-word span, .splash-tag { animation: none; opacity: 1; transform: none; filter: none; }
    .splash-badge::before, .splash-line i, .site-splash::before, .site-splash::after { animation: none; }
  }
`;

const SITE_SPLASH_HTML =
  '<div class="site-splash" id="siteSplash">' +
    '<div class="splash-inner">' +
      '<div class="splash-badge">' +
        '<img class="splash-logo" src="' + SITE_LOGO_URL + '" alt="' + SITE_BRAND + '" onerror="this.style.display=&quot;none&quot;;this.nextElementSibling.style.display=&quot;grid&quot;">' +
        '<div class="splash-fallback">GI</div>' +
      '</div>' +
      '<div class="splash-word"><span>G</span><span class="grad">I</span><span>&nbsp;</span><span>P</span><span>OS</span></div>' +
      '<div class="splash-tag">Cloud POS Platform</div>' +
      '<div class="splash-line"><i></i></div>' +
    '</div>' +
  '</div>' +
  '<script>(function(){var s=document.getElementById("siteSplash");if(!s)return;' +
    'var seen=false;try{seen=sessionStorage.getItem("giposSplashSeen")==="1";}catch(e){}' +
    'if(seen){if(s.parentNode)s.parentNode.removeChild(s);return;}' +
    'try{sessionStorage.setItem("giposSplashSeen","1");}catch(e){}' +
    'function done(){s.classList.add("hide");setTimeout(function(){if(s&&s.parentNode)s.parentNode.removeChild(s);},900);}' +
    'window.addEventListener("load",function(){setTimeout(done,1600);});setTimeout(done,3400);})();</script>';

function siteLogoHtml() {
  return '<a class="site-logo" href="/">' +
    '<img src="' + SITE_LOGO_URL + '" alt="' + SITE_BRAND + '" onerror="this.style.display=&quot;none&quot;;this.nextElementSibling.style.display=&quot;grid&quot;">' +
    '<span class="logo-fallback" style="display:none">GI</span>' +
    '<span class="logo-text"><strong>' + SITE_BRAND + '</strong><span>Cloud POS Platform</span></span>' +
  '</a>';
}

function buildSiteNav(active) {
  const links = [
    ['/', 'Home'], ['/about', 'About'], ['/features', 'Features'],
    ['/pricing', 'Pricing'], ['/status', 'Status'], ['/support', 'Support'],
  ];
  const linkHtml = links.map(function (item) {
    return '<a href="' + item[0] + '"' + (item[0] === active ? ' class="active"' : '') + '>' + item[1] + '</a>';
  }).join('');
  return '<nav class="site-nav">' +
    siteLogoHtml() +
    '<div class="site-nav-links" id="siteNavLinks">' + linkHtml + '</div>' +
    '<div class="site-nav-actions">' +
      '<a class="btn btn-ghost" href="/portal">Sign In</a>' +
      '<a class="btn btn-primary" href="/signup">Create Account</a>' +
      '<button class="site-nav-toggle" type="button" onclick="document.getElementById(&quot;siteNavLinks&quot;).classList.toggle(&quot;open&quot;)">=</button>' +
    '</div>' +
  '</nav>';
}

function buildSiteFooter() {
  return '<footer class="site-footer"><div class="footer-grid">' +
    '<div class="footer-brand">' + siteLogoHtml().replace('site-logo', 'site-logo').replace('logo-text"><strong>' + SITE_BRAND, 'logo-text"><strong style="color:#fff">' + SITE_BRAND) +
      '<p>Modern cloud POS and restaurant billing software for Windows and Android - fast billing, cloud backup, and reporting for every counter.</p></div>' +
    '<div><h4>Product</h4><a href="/features">Features</a><a href="/pricing">Pricing</a><a href="/demo">Book a Demo</a><a href="/signup">Create Business Account</a></div>' +
    '<div><h4>Company</h4><a href="/about">About Us</a><a href="/support">Support &amp; Contact</a><a href="/status">Cloud Status</a><a href="/portal">Client Portal</a></div>' +
    '<div><h4>Contact</h4><a href="tel:' + SITE_CONTACT.phoneHref + '">' + SITE_CONTACT.phone + '</a><a href="mailto:' + SITE_CONTACT.email + '">' + SITE_CONTACT.email + '</a><a href="/support">Live Chat &amp; Support</a></div>' +
    '</div><div class="footer-bottom"><span>&copy; ' + new Date().getFullYear() + ' ' + SITE_BRAND + '. All rights reserved.</span>' +
    '<span class="powered-by">Powered by <a href="https://gihostings.com" target="_blank" rel="noopener">GI HOSTINGS</a></span></div></footer>';
}

/* Compact "Powered by GI HOSTINGS" bar for the portal & admin app templates (they use BASE_STYLES only). */
const APP_POWERED_BY_HTML =
  '<footer style="text-align:center;padding:18px 20px;font-size:12.5px;font-weight:800;color:#64748b;border-top:1px solid var(--line,#e2e8f0);background:#fff">' +
    'Powered by <a href="https://gihostings.com" target="_blank" rel="noopener" style="color:#7c3aed;font-weight:900;text-decoration:none">GI HOSTINGS</a>' +
  '</footer>';

const SITE_REVEAL_SCRIPT =
  '<script>' +
  '(function(){' +
  'var links=document.getElementById("siteNavLinks");' +
  'var io=new IntersectionObserver(function(es){es.forEach(function(e){if(e.isIntersecting){e.target.classList.add("in");io.unobserve(e.target);}});},{threshold:.12});' +
  'document.querySelectorAll(".reveal").forEach(function(el){io.observe(el);});' +
  '})();' +
  '</script>';

function buildSitePage(opts) {
  return '<!doctype html><html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1">' +
    '<link rel="icon" href="' + SITE_LOGO_URL + '">' +
    '<title>' + opts.title + '</title><style>' + BASE_STYLES + SITE_STYLES + '</style></head>' +
    '<body class="site-body">' + (opts.splash ? SITE_SPLASH_HTML : '') + buildSiteNav(opts.active || '/') +
    '<main class="site-main">' + opts.body + '</main>' +
    buildSiteFooter() + SITE_REVEAL_SCRIPT + (opts.script || '') +
    '</body></html>';
}

function deviceMockupsHtml() {
  // Desktop frame: screenshot if provided, else CSS mockup fallback.
  var desktopInner = SITE_SHOT_DESKTOP
    ? '<img class="shot" src="' + SITE_SHOT_DESKTOP + '" alt="' + SITE_BRAND + ' Windows software" loading="lazy" onerror="this.remove()">'
    : '<div class="scr">' +
        '<div class="m-rail"><i class="on"></i><i></i><i></i><i></i></div>' +
        '<div class="m-body"><div class="m-top" style="height:26px;border-radius:7px"></div>' +
          '<div class="m-tiles"><div class="m-tile hot"></div><div class="m-tile"></div><div class="m-tile"></div><div class="m-tile"></div><div class="m-tile hot"></div><div class="m-tile"></div></div></div>' +
        '<div class="m-cart"><div class="m-line lg"></div><div class="m-line md"></div><div class="m-line sm"></div><div class="m-tile hot" style="height:34px;margin-top:auto"></div></div>' +
      '</div>';
  var phoneInner = SITE_SHOT_MOBILE
    ? '<img class="shot" src="' + SITE_SHOT_MOBILE + '" alt="' + SITE_BRAND + ' Android app" loading="lazy" onerror="this.remove()">'
    : '<div class="scr">' +
        '<div class="m-phonetop"></div>' +
        '<div class="m-body"><div class="m-line md"></div><div class="m-tiles two"><div class="m-tile hot"></div><div class="m-tile"></div></div><div class="m-line sm"></div></div>' +
        '<div class="m-nav"><i></i><i></i><i></i><i></i></div>' +
      '</div>';
  return '<div class="hero-devices reveal">' +
    '<div class="dv dv-desktop">' + desktopInner + '<div class="base"></div></div>' +
    '<div class="dv dv-phone">' + phoneInner + '</div>' +
  '</div>';
}

const HOME_HTML = buildSitePage({
  title: SITE_BRAND + ' - Cloud POS & Restaurant Billing Software',
  active: '/',
  splash: true,
  body:
    '<header class="hero"><div class="hero-grid">' +
      '<div class="hero-copy">' +
        '<span class="kicker anim-fade-up">Cloud POS - Windows &amp; Android</span>' +
        '<h1 class="anim-fade-up anim-delay-1">Premium billing software for restaurants, <span class="grad-text">built for every device.</span></h1>' +
        '<p class="lead anim-fade-up anim-delay-2">' + SITE_BRAND + ' powers your counters online and offline - fast KOT and billing, live table view, cloud backup, and rich reports across Windows desktop, Android phone, and tablet.</p>' +
        '<div class="hero-cta anim-fade-up anim-delay-3"><a class="btn btn-primary btn-lg" href="/signup">Create Business Account</a><a class="btn btn-lg" href="/demo">Book a Demo</a></div>' +
        '<div class="hero-points anim-fade-up anim-delay-4"><span>Offline-first billing</span><span>Cloud backup &amp; sync</span><span>Windows, Android &amp; tablet</span></div>' +
      '</div>' +
      deviceMockupsHtml() +
    '</div></header>' +

    '<section class="sect"><div class="wrap"><div class="stat-grid">' +
      '<div class="stat reveal"><strong>4</strong><span>Flexible plans</span></div>' +
      '<div class="stat reveal"><strong>2</strong><span>Platforms - Win &amp; Android</span></div>' +
      '<div class="stat reveal"><strong>24/7</strong><span>Cloud backup</span></div>' +
      '<div class="stat reveal"><strong>100%</strong><span>Offline-capable billing</span></div>' +
    '</div></div></section>' +

    '<section class="sect tint" id="about"><div class="wrap"><div class="split">' +
      '<div class="reveal"><span class="kicker">About Us</span><h2 style="font-size:34px;margin:14px 0">Software built for real restaurant floors.</h2>' +
        '<p style="color:var(--muted);font-weight:700;line-height:1.7;font-size:16px">' + SITE_BRAND + ' is a cloud POS and billing platform crafted for cafes, restaurants, and food businesses. We combine offline-first reliability with cloud backup and multi-device sync, so your counters never stop - even when the internet does.</p>' +
        '<div style="margin-top:20px"><a class="btn btn-primary" href="/about">Learn more about us</a></div></div>' +
      '<div class="why-list reveal">' +
        '<div class="why-item"><span class="ck">1</span><div><h4>Offline-first architecture</h4><p>Keep billing even without internet; sync to the cloud automatically when back online.</p></div></div>' +
        '<div class="why-item"><span class="ck">2</span><div><h4>Multi-device &amp; LAN</h4><p>Run a main PC as a local server and bill from other PCs, phones, and tablets on your network.</p></div></div>' +
        '<div class="why-item"><span class="ck">3</span><div><h4>Secure &amp; role-based</h4><p>Per-user PINs, permissions, and audit logs keep your data and access controlled.</p></div></div>' +
      '</div>' +
    '</div></div></section>' +

    '<section class="sect" id="features"><div class="wrap"><div class="sect-head reveal"><span class="kicker">Features</span><h2>Advanced features that keep counters moving</h2><p>From lightning-fast KOT to cloud analytics, ' + SITE_BRAND + ' covers the full restaurant workflow.</p></div>' +
      '<div class="grid-3">' + siteFeatureCards(6) + '</div>' +
      '<div style="text-align:center;margin-top:32px"><a class="btn btn-primary" href="/features">See all features</a></div>' +
    '</div></section>' +

    '<section class="sect tint"><div class="wrap"><div class="sect-head reveal"><span class="kicker">Products &amp; Services</span><h2>One platform, every counter</h2><p>Pick the setup that fits how you bill - all backed by the same cloud.</p></div>' +
      '<div class="grid-3">' +
        '<div class="feat reveal"><div class="ic">W</div><h3>Windows POS</h3><p>Full-featured desktop billing with KOT routing, table view, and thermal printing.</p></div>' +
        '<div class="feat reveal"><div class="ic">A</div><h3>Android App</h3><p>Native mobile billing on phone or tablet with Bluetooth and network printing.</p></div>' +
        '<div class="feat reveal"><div class="ic">C</div><h3>Cloud &amp; Portal</h3><p>Cloud backup, multi-device sync, and a client portal for licenses and staff PIN resets.</p></div>' +
      '</div></div></section>' +

    '<section class="sect"><div class="wrap"><div class="sect-head reveal"><span class="kicker">Why Choose Us</span><h2>Benefits your business will feel</h2></div>' +
      '<div class="grid-3">' +
        '<div class="feat reveal"><div class="ic">+</div><h3>Faster service</h3><p>Speed up billing and KOT so tables turn over quicker during rush hours.</p></div>' +
        '<div class="feat reveal"><div class="ic">$</div><h3>No lost sales</h3><p>Offline-first billing means you keep selling even during internet outages.</p></div>' +
        '<div class="feat reveal"><div class="ic">^</div><h3>Grow with data</h3><p>Daily reports, item performance, and payment breakdowns to make better decisions.</p></div>' +
      '</div></div></section>' +

    '<section class="sect tint"><div class="wrap"><div class="sect-head reveal"><span class="kicker">Packages</span><h2>Simple plans for every counter</h2><p>Yearly subscriptions with custom expiry. Full details on the pricing page.</p></div>' +
      '<div class="price-grid">' + sitePricingCards() + '</div>' +
      '<div style="text-align:center;margin-top:30px"><a class="btn btn-primary" href="/pricing">Compare all plans</a></div>' +
    '</div></section>' +

    '<section class="sect"><div class="wrap"><div class="sect-head reveal"><span class="kicker">FAQ</span><h2>Frequently asked questions</h2></div>' + siteFaqHtml() + '</div></section>' +

    '<section style="padding:20px 0 88px"><div class="cta-band reveal"><h2>Ready to modernize your billing?</h2><p>Create your business account in minutes, or book a live demo with our team.</p>' +
      '<div class="hero-cta" style="justify-content:center"><a class="btn btn-primary btn-lg" href="/signup">Create Business Account</a><a class="btn btn-lg" style="background:rgba(255,255,255,.14);color:#fff;border-color:rgba(255,255,255,.3)" href="/demo">Book a Demo</a></div></div></section>',
});

function siteFeatureCards(count) {
  const cards = [
    ['B', 'Fast Billing &amp; KOT', 'Send kitchen tickets instantly, route to stations, and keep tables color-coded live.'],
    ['T', 'Live Table View', 'See running, KOT-sent, billed, and held tables at a glance with one-tap settlement.'],
    ['C', 'Cloud Backup &amp; Sync', 'Every bill is safe in the cloud and synced across devices - restore instantly on any PC.'],
    ['L', 'Local POS Server', 'Turn your main PC into a LAN server so extra counters and mobiles bill through it.'],
    ['R', 'Reports &amp; Analytics', 'Daily sales, payment breakdowns, item performance, and shift close - export to PDF/Excel.'],
    ['M', 'Menu &amp; Customers', 'Manage categories, variants, prices, images, dues, and customer profiles with ease.'],
    ['P', 'Thermal Printing', 'Network and Bluetooth thermal printing tuned for 80mm and 58mm rolls.'],
    ['Q', 'QR Ordering', 'Let guests place table orders from a QR code that flow straight into your POS.'],
    ['S', 'Staff &amp; Security', 'Per-user PINs, permissions, audit logs, and portal-based staff PIN reset.'],
  ];
  const list = count ? cards.slice(0, count) : cards;
  return list.map(function (c) {
    return '<article class="feat reveal"><div class="ic">' + c[0] + '</div><h3>' + c[1] + '</h3><p>' + c[2] + '</p></article>';
  }).join('');
}

function planIconSvg(kind) {
  var open = '<span class="price-icon"><svg viewBox="0 0 24 24" width="26" height="26" fill="none" stroke="url(#giPlanGrad)" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round">';
  var close = '</svg></span>';
  var paths = {
    // Desktop monitor - Premium
    premium: '<rect x="2.5" y="3.5" width="19" height="13" rx="2"/><path d="M8 20.5h8M12 16.5v4"/>',
    // Stacked servers - Gold
    gold: '<rect x="3" y="3.5" width="18" height="6" rx="1.5"/><rect x="3" y="12" width="18" height="6" rx="1.5"/><path d="M6.5 6.5h.01M6.5 15h.01"/>',
    // Power plug - Offline
    offline: '<path d="M9 2.5v5M15 2.5v5"/><path d="M6 7.5h12v3a6 6 0 0 1-12 0z"/><path d="M12 16.5v5"/>',
    // Phone - Android
    android: '<rect x="6.5" y="2.5" width="11" height="19" rx="2.5"/><path d="M10.5 18.5h3"/>',
  };
  return open + (paths[kind] || paths.premium) + close;
}

function sitePricingCards() {
  var grad = '<svg width="0" height="0" style="position:absolute" aria-hidden="true"><defs><linearGradient id="giPlanGrad" x1="0" y1="0" x2="1" y2="1"><stop offset="0" stop-color="#8b5cf6"/><stop offset="1" stop-color="#6d28d9"/></linearGradient></defs></svg>';
  return grad +
    '<article class="price reveal">' + planIconSvg('premium') + '<div class="tier">Premium</div><div class="tier-sub">Single counter cloud POS</div><ul><li>One Windows billing counter</li><li>Cloud backup and restore</li><li>Client portal &amp; app download</li><li>Staff PIN reset</li></ul><a class="btn btn-primary" href="/signup">Get Started</a></article>' +
    '<article class="price featured reveal"><span class="ribbon">Most Popular</span>' + planIconSvg('gold') + '<div class="tier">Gold</div><div class="tier-sub">Local POS server plus cloud</div><ul><li>Main PC local POS server</li><li>Other PCs &amp; mobiles via LAN</li><li>Cloud backup &amp; QR ordering</li><li>Client portal &amp; staff PIN reset</li></ul><a class="btn btn-primary" href="/signup">Get Started</a></article>' +
    '<article class="price reveal">' + planIconSvg('offline') + '<div class="tier">Offline</div><div class="tier-sub">Single PC local POS</div><ul><li>One Windows billing counter</li><li>One-time cloud activation</li><li>Local SQLite billing</li><li>No cloud backup / QR</li></ul><a class="btn" href="/signup">Get Started</a></article>' +
    '<article class="price reveal">' + planIconSvg('android') + '<div class="tier">Android</div><div class="tier-sub">Native mobile POS</div><ul><li>One Android phone or tablet</li><li>Offline-first billing</li><li>Bluetooth &amp; network printing</li><li>Cloud backup &amp; sync</li></ul><a class="btn" href="/signup">Get Started</a></article>';
}

function siteFaqHtml() {
  const faqs = [
    ['Does it work without internet?', 'Yes. ' + SITE_BRAND + ' is offline-first - you can keep billing during outages, and everything syncs to the cloud automatically once you are back online.'],
    ['Which devices are supported?', 'Windows desktops and laptops, plus Android phones and tablets. The Gold plan also lets other devices bill through a main PC on your local network.'],
    ['How does activation work?', 'Create a business account, our team approves your subscription, then your compatible Windows or Android app activates with your phone/email and password.'],
    ['Can I move my POS to another PC?', 'Yes. Generate a one-time transfer code from the client portal to move the main app to another PC safely.'],
    ['Do you provide support?', 'Yes - reach us by live chat, phone, email, or WhatsApp. See the Support page for all options.'],
  ];
  return '<div class="faq-list">' + faqs.map(function (f) {
    return '<details class="faq reveal"><summary>' + f[0] + '</summary><p>' + f[1] + '</p></details>';
  }).join('') + '</div>';
}

const ABOUT_HTML = buildSitePage({
  title: 'About - ' + SITE_BRAND,
  active: '/about',
  body:
    '<section class="page-hero"><div class="wrap"><span class="kicker">About Us</span><h1>Built by hospitality-tech people, for restaurants.</h1><p>' + SITE_BRAND + ' is a cloud POS and billing platform focused on speed, reliability, and simplicity for food businesses of every size.</p></div></section>' +
    '<section class="sect"><div class="wrap"><div class="split">' +
      '<div class="reveal"><h2 style="font-size:30px">Our mission</h2><p style="color:var(--muted);font-weight:700;line-height:1.7;margin-top:12px">We believe billing software should be fast, dependable, and work everywhere - counter, kitchen, phone, or tablet. We build offline-first tools backed by the cloud so restaurants never lose a sale and always have their data safe.</p>' +
        '<div class="stat-grid" style="grid-template-columns:repeat(2,1fr);margin-top:22px"><div class="stat"><strong>Cloud</strong><span>Backup &amp; sync</span></div><div class="stat"><strong>Offline</strong><span>Always billing</span></div></div></div>' +
      '<div class="why-list reveal">' +
        '<div class="why-item"><span class="ck">V</span><div><h4>Reliability first</h4><p>Offline-first billing that keeps counters running through any outage.</p></div></div>' +
        '<div class="why-item"><span class="ck">S</span><div><h4>Simple to use</h4><p>Clean, modern interfaces staff can learn in minutes.</p></div></div>' +
        '<div class="why-item"><span class="ck">G</span><div><h4>Built to grow</h4><p>From a single counter to multi-device LAN setups.</p></div></div>' +
        '<div class="why-item"><span class="ck">H</span><div><h4>Real support</h4><p>Live chat, phone, email, and WhatsApp - real people who know POS.</p></div></div>' +
      '</div>' +
    '</div></div></section>' +
    '<section class="sect tint"><div class="wrap"><div class="sect-head reveal"><span class="kicker">Why Choose Us</span><h2>What sets ' + SITE_BRAND + ' apart</h2></div><div class="grid-3">' +
      '<div class="feat reveal"><div class="ic">1</div><h3>Windows + Android</h3><p>One account, both platforms, the same clean experience.</p></div>' +
      '<div class="feat reveal"><div class="ic">2</div><h3>Cloud + Local</h3><p>Cloud backup with a local POS server option for zero-latency LAN billing.</p></div>' +
      '<div class="feat reveal"><div class="ic">3</div><h3>Transparent plans</h3><p>Clear yearly plans with custom expiry and manual, no-surprise billing.</p></div>' +
    '</div></div></section>' +
    '<section style="padding:40px 0 88px"><div class="cta-band reveal"><h2>See it in action</h2><p>Book a live demo or start your business account today.</p><div class="hero-cta" style="justify-content:center"><a class="btn btn-primary btn-lg" href="/demo">Book a Demo</a><a class="btn btn-lg" style="background:rgba(255,255,255,.14);color:#fff;border-color:rgba(255,255,255,.3)" href="/signup">Create Business Account</a></div></div></section>',
});

const FEATURES_HTML = buildSitePage({
  title: 'Features - ' + SITE_BRAND,
  active: '/features',
  body:
    '<section class="page-hero"><div class="wrap"><span class="kicker">Features</span><h1>Everything you need to run the floor.</h1><p>A complete restaurant billing toolkit - from KOT and tables to cloud reporting and printing.</p></div></section>' +
    '<section class="sect"><div class="wrap"><div class="grid-3">' + siteFeatureCards(0) + '</div></div></section>' +
    '<section class="sect tint"><div class="wrap"><div class="split">' +
      '<div class="reveal"><span class="kicker">Benefits</span><h2 style="font-size:32px;margin:14px 0">Designed to save time and money</h2>' +
        '<div class="why-list"><div class="why-item"><span class="ck">+</span><div><h4>Faster tables</h4><p>Quicker KOT and settlement during peak hours.</p></div></div>' +
        '<div class="why-item"><span class="ck">$</span><div><h4>No downtime sales loss</h4><p>Bill offline and sync later - never stop selling.</p></div></div>' +
        '<div class="why-item"><span class="ck">^</span><div><h4>Smarter decisions</h4><p>Live reports and analytics at your fingertips.</p></div></div></div></div>' +
      deviceMockupsHtml() +
    '</div></div></section>' +
    '<section style="padding:40px 0 88px"><div class="cta-band reveal"><h2>Ready to try every feature?</h2><p>Create your account or book a guided demo.</p><div class="hero-cta" style="justify-content:center"><a class="btn btn-primary btn-lg" href="/signup">Create Business Account</a><a class="btn btn-lg" style="background:rgba(255,255,255,.14);color:#fff;border-color:rgba(255,255,255,.3)" href="/demo">Book a Demo</a></div></div></section>',
});

const PRICING_HTML = buildSitePage({
  title: 'Pricing - ' + SITE_BRAND,
  active: '/pricing',
  body:
    '<section class="page-hero"><div class="wrap"><span class="kicker">Packages</span><h1>Simple, transparent pricing.</h1><p>Yearly subscriptions with custom expiry. Choose the plan that matches how you bill.</p></div></section>' +
    '<section class="sect"><div class="wrap"><div class="price-grid">' + sitePricingCards() + '</div>' +
      '<p style="text-align:center;color:var(--muted);font-weight:800;margin-top:26px">All plans include cloud account, client portal, and updates. Subscription payment is handled manually by our team after approval.</p></div></section>' +
    '<section class="sect tint"><div class="wrap"><div class="sect-head reveal"><span class="kicker">FAQ</span><h2>Pricing questions</h2></div>' + siteFaqHtml() + '</div></section>' +
    '<section style="padding:40px 0 88px"><div class="cta-band reveal"><h2>Not sure which plan fits?</h2><p>Book a demo and we will recommend the right setup.</p><div class="hero-cta" style="justify-content:center"><a class="btn btn-primary btn-lg" href="/demo">Book a Demo</a><a class="btn btn-lg" style="background:rgba(255,255,255,.14);color:#fff;border-color:rgba(255,255,255,.3)" href="/signup">Create Business Account</a></div></div></section>',
});

const SUPPORT_HTML = buildSitePage({
  title: 'Support - ' + SITE_BRAND,
  active: '/support',
  body:
    '<section class="page-hero"><div class="wrap"><span class="kicker">Support &amp; Contact</span><h1>We are here to help.</h1><p>Get instant answers from the ' + SITE_BRAND + ' Assistant, or reach a real person by phone, email, or WhatsApp. Everything you need in one place.</p></div></section>' +
    '<section class="sect"><div class="wrap">' +
      '<div class="sect-head reveal"><span class="kicker">Contact</span><h2>Reach our team</h2><p>Choose whatever is fastest for you - we usually reply within one business day.</p></div>' +
      '<div class="contact-grid">' +
        '<div class="contact-card reveal"><div class="ic">&#128222;</div><span class="eyebrow">Call Support</span><a href="tel:' + SITE_CONTACT.phoneHref + '">' + SITE_CONTACT.phone + '</a><p style="color:var(--muted);font-weight:700">' + SITE_CONTACT.hours + '</p></div>' +
        '<div class="contact-card reveal"><div class="ic">&#128172;</div><span class="eyebrow">WhatsApp</span><a href="https://wa.me/' + SITE_CONTACT.whatsapp.replace(/[^0-9]/g, "") + '" target="_blank" rel="noopener">Chat on WhatsApp</a><p style="color:var(--muted);font-weight:700">Fastest response</p></div>' +
        '<div class="contact-card reveal"><div class="ic">&#9993;</div><span class="eyebrow">Email</span><a href="mailto:' + SITE_CONTACT.email + '">' + SITE_CONTACT.email + '</a><p style="color:var(--muted);font-weight:700">Sales: ' + SITE_CONTACT.sales + '</p></div>' +
        '<div class="contact-card reveal"><div class="ic">&#128205;</div><span class="eyebrow">Office</span><strong>' + SITE_CONTACT.address + '</strong><p style="color:var(--muted);font-weight:700">Remote setup available nationwide</p></div>' +
      '</div>' +
    '</div></section>' +
    '<section class="sect tint"><div class="wrap"><div class="assist-cta reveal">' +
      '<div class="assist-cta-copy"><span class="kicker">GI POS Assistant</span><h2>Have a quick question?</h2><p>Tap the Magic Button to open the ' + SITE_BRAND + ' Assistant. Get instant answers about pricing, activation, downloads, and offline use - any time.</p>' +
        '<button class="btn btn-primary btn-lg magic-open" id="assistOpen"><img class="magic-open-logo" src="' + SITE_LOGO_URL + '" alt="" onerror="this.remove()"> Ask the Assistant</button>' +
      '</div>' +
      '<div class="assist-cta-art" aria-hidden="true"><div class="assist-orb"><img src="' + SITE_LOGO_URL + '" alt="' + SITE_BRAND + '" onerror="this.style.display=&quot;none&quot;;this.nextElementSibling.style.display=&quot;block&quot;"><span style="display:none;font-size:54px">GI</span></div></div>' +
    '</div></div></section>' +
    '<section class="sect" id="message"><div class="wrap"><div class="form-wrap"><div class="form-card reveal"><div><span class="kicker">Message</span><h2 style="font-size:24px;margin-top:12px">Send Me a Message</h2><p style="color:var(--muted);font-weight:700;margin-top:6px">Send a message straight to our team - it lands in our admin inbox and we will reach out.</p></div>' +
      '<div class="form-2"><label>Name <input id="cName" placeholder="Your name"></label><label>Phone <input id="cPhone" placeholder="+91XXXXXXXXXX"></label>' +
      '<label class="full">Email <input id="cEmail" type="email" placeholder="you@business.com"></label>' +
      '<label class="full">Message <textarea id="cMsg" placeholder="How can we help?"></textarea></label></div>' +
      '<button class="btn btn-primary" id="cSendBtn" style="height:48px">Send Message</button>' +
      '<div class="status" id="cStatus">We usually reply within one business day.</div></div></div>' +
    '</div></section>' +
    /* Floating Magic Button (logo) with welcome bubble + slide-in assistant panel */
    '<div class="magic-dock">' +
      '<div class="magic-hello" id="magicHello"><span>&#128075; Chat with me</span><button class="magic-hello-close" id="magicHelloClose" aria-label="Dismiss">&times;</button></div>' +
      '<button class="magic-fab" id="magicFab" aria-label="Open GI POS Assistant">' +
        '<img class="magic-fab-logo" src="' + SITE_LOGO_URL + '" alt="GI POS" onerror="this.style.display=&quot;none&quot;;this.nextElementSibling.style.display=&quot;grid&quot;">' +
        '<span class="magic-fab-fallback" style="display:none">GI</span>' +
        '<span class="magic-dot"></span>' +
      '</button>' +
    '</div>' +
    '<a class="wa-fab" id="waFab" href="https://wa.me/' + SITE_CONTACT.whatsapp.replace(/[^0-9]/g, "") + '" target="_blank" rel="noopener" aria-label="Chat on WhatsApp"><svg viewBox="0 0 32 32" width="30" height="30" fill="#fff"><path d="M16.04 3C9.4 3 4 8.4 4 15.04c0 2.13.56 4.2 1.62 6.03L4 29l8.13-1.6a12 12 0 0 0 3.9.65h.01C22.68 28.05 28.08 22.65 28.08 16 28.08 8.4 22.68 3 16.04 3zm7.05 17.1c-.3.83-1.72 1.58-2.37 1.64-.6.06-1.37.08-2.22-.14-.51-.13-1.17-.39-2.02-.75-3.55-1.53-5.86-5.1-6.04-5.34-.18-.24-1.45-1.92-1.45-3.66 0-1.74.91-2.6 1.24-2.95.33-.35.71-.44.95-.44.24 0 .47 0 .68.01.22.01.51-.08.8.61.3.7 1 2.44 1.08 2.62.09.18.15.39.03.62-.11.24-.17.38-.34.59-.17.2-.36.45-.51.6-.17.18-.35.37-.15.72.2.35.87 1.44 1.88 2.34 1.29 1.15 2.38 1.5 2.72 1.68.34.17.54.14.74-.09.2-.23.86-1 1.09-1.35.23-.35.46-.29.78-.17.32.12 2.03.96 2.38 1.13.35.17.58.26.67.41.09.15.09.83-.21 1.66z"/></svg></a>' +
    '<div class="assist-overlay" id="assistOverlay"></div>' +
    '<aside class="assist-panel" id="assistPanel" aria-hidden="true">' +
      '<div class="assist-head"><div class="assist-id"><span class="assist-avatar"><img src="' + SITE_LOGO_URL + '" alt="' + SITE_BRAND + '" onerror="this.style.display=&quot;none&quot;;this.parentNode.textContent=&quot;GI&quot;"></span><div><strong>' + SITE_BRAND + ' Assistant</strong><span>Online now</span></div></div><button class="assist-close" id="assistClose" aria-label="Close">&times;</button></div>' +
      '<div class="assist-body" id="chatBody"><div class="chat-msg bot">Hi! I am the ' + SITE_BRAND + ' assistant. Ask me about pricing, activation, downloads, or support - or pick a topic below.</div></div>' +
      '<div class="chat-suggest" id="chatSuggest"><button data-q="pricing">Pricing</button><button data-q="activation">Activation</button><button data-q="download">Download</button><button data-q="offline">Offline use</button><button data-q="human">Talk to a human</button></div>' +
      '<div class="chat-input"><input id="chatInput" placeholder="Type your question..."><button class="btn btn-primary" id="chatSend">Send</button></div>' +
    '</aside>',
  script:
    '<script>(function(){' +
    'var b=document.getElementById("cSendBtn");if(b){b.addEventListener("click",async function(){' +
    'var s=document.getElementById("cStatus");var n=document.getElementById("cName").value.trim();' +
    'var p=document.getElementById("cPhone").value.trim();var e=document.getElementById("cEmail").value.trim();' +
    'if(!n||(!p&&!e)){s.className="status error";s.textContent="Enter your name and a phone or email.";return;}' +
    's.className="status";s.textContent="Sending your message...";b.disabled=true;' +
    'try{var resp=await fetch("/api/v1/support/message",{method:"POST",headers:{"content-type":"application/json"},body:JSON.stringify({name:n,phone:p,email:e,message:document.getElementById("cMsg").value,source:"support"})});' +
    'var r=await resp.json().catch(function(){return {};});' +
    'if(!resp.ok||r.ok===false)throw new Error(r.error||"Could not send message");' +
    's.className="status ok";s.textContent=r.message||("Thanks "+n+"! Your message was received.");b.textContent="Message Sent";}' +
    'catch(err){s.className="status error";s.textContent=err.message||"Could not send message";b.disabled=false;}});}' +
    /* Assistant panel open/close */
    'var panel=document.getElementById("assistPanel");var overlay=document.getElementById("assistOverlay");var fab=document.getElementById("magicFab");' +
    'var hello=document.getElementById("magicHello");var dot=document.querySelector(".magic-dot");' +
    'function hideHello(){if(hello)hello.classList.remove("show");}' +
    'function openPanel(){panel.classList.add("open");overlay.classList.add("show");panel.setAttribute("aria-hidden","false");hideHello();if(dot)dot.style.display="none";setTimeout(function(){var i=document.getElementById("chatInput");if(i)i.focus();},260);}' +
    'function closePanel(){panel.classList.remove("open");overlay.classList.remove("show");panel.setAttribute("aria-hidden","true");}' +
    'if(hello){setTimeout(function(){hello.classList.add("show");},1400);setTimeout(hideHello,9000);}' +
    'var hc=document.getElementById("magicHelloClose");if(hc)hc.addEventListener("click",function(ev){ev.stopPropagation();hideHello();});' +
    'if(fab)fab.addEventListener("click",openPanel);' +
    'var ao=document.getElementById("assistOpen");if(ao)ao.addEventListener("click",openPanel);' +
    'var ac=document.getElementById("assistClose");if(ac)ac.addEventListener("click",closePanel);' +
    'if(overlay)overlay.addEventListener("click",closePanel);' +
    'document.addEventListener("keydown",function(e){if(e.key==="Escape")closePanel();});' +
    /* Assistant replies */
    'var body=document.getElementById("chatBody");var input=document.getElementById("chatInput");' +
    'var answers={' +
    'pricing:"We offer four plans - Premium, Gold, Offline, and Android. See full details on our Pricing page. Payment is handled manually after approval.",' +
    'activation:"Create a business account, our team approves your subscription, then your Windows or Android app activates with your phone/email and password.",' +
    'download:"After approval, sign in to the Client Portal to download the Windows setup or Android APK.",' +
    'offline:"Yes! Billing works offline and syncs to the cloud automatically once you are back online.",' +
    'human:"You can reach a human on ' + SITE_CONTACT.phone + ', WhatsApp, or email ' + SITE_CONTACT.email + '. Our hours are ' + SITE_CONTACT.hours + '."' +
    '};' +
    'function add(text,who){var d=document.createElement("div");d.className="chat-msg "+who;d.textContent=text;body.appendChild(d);body.scrollTop=body.scrollHeight;}' +
    'function reply(q){var key=String(q||"").toLowerCase();var ans=null;' +
    'if(answers[key]){ans=answers[key];}' +
    'else if(/price|cost|plan|package/.test(key)){ans=answers.pricing;}' +
    'else if(/activ|approve|licen/.test(key)){ans=answers.activation;}' +
    'else if(/download|install|apk|setup/.test(key)){ans=answers.download;}' +
    'else if(/offline|internet|network/.test(key)){ans=answers.offline;}' +
    'else if(/human|call|phone|contact|support|agent/.test(key)){ans=answers.human;}' +
    'else{ans="Thanks for your question! For anything specific, reach our team on ' + SITE_CONTACT.phone + ' or ' + SITE_CONTACT.email + ", or ask about pricing, activation, download, or offline use.\";}" +
    'setTimeout(function(){add(ans,"bot");},350);}' +
    'function send(){var v=input.value.trim();if(!v)return;add(v,"user");input.value="";reply(v);}' +
    'var cs=document.getElementById("chatSend");if(cs)cs.addEventListener("click",send);' +
    'if(input)input.addEventListener("keydown",function(e){if(e.key==="Enter")send();});' +
    'var sug=document.getElementById("chatSuggest");if(sug)sug.querySelectorAll("button").forEach(function(btn){btn.addEventListener("click",function(){var q=btn.getAttribute("data-q");add(btn.textContent,"user");reply(q);});});' +
    '})();</script>',
});

const STATUS_HTML = buildSitePage({
  title: 'Cloud Status - ' + SITE_BRAND,
  active: '/status',
  body:
    '<section class="page-hero"><div class="wrap"><span class="kicker">System Status</span><h1>' + SITE_BRAND + ' Cloud Status</h1><p>Live status of our cloud services. This page refreshes automatically.</p></div></section>' +
    '<section class="sect"><div class="wrap">' +
      '<div class="status-overall" id="statusOverall"><div class="status-overall-dot"></div><div><strong id="statusOverallText">Checking services...</strong><span id="statusCheckedAt">Loading live status</span></div><button class="btn" id="statusRefreshBtn">Refresh</button></div>' +
      '<div class="status-list" id="statusList"><div class="empty-state" style="border-color:var(--line)">Loading service status...</div></div>' +
      '<div class="status-legend"><span><i class="dot operational"></i> Operational</span><span><i class="dot degraded"></i> Degraded</span><span><i class="dot outage"></i> Outage</span></div>' +
    '</div></section>',
  script:
    '<script>(function(){' +
    'var listEl=document.getElementById("statusList");var overallEl=document.getElementById("statusOverall");' +
    'var overallText=document.getElementById("statusOverallText");var checkedEl=document.getElementById("statusCheckedAt");' +
    'function esc(v){return String(v==null?"":v).replace(/[&<>\\"]/g,function(c){return ({"&":"&amp;","<":"&lt;",">":"&gt;","\\"":"&quot;"})[c];});}' +
    'function label(s){return s==="operational"?"Operational":(s==="degraded"?"Degraded":(s==="outage"?"Outage":"Unknown"));}' +
    'function render(data){' +
      'var overall=data.overall||"operational";' +
      'overallEl.className="status-overall "+overall;' +
      'overallText.textContent=overall==="operational"?"All Systems Operational":(overall==="degraded"?"Partial Degradation":"Service Outage");' +
      'checkedEl.textContent="Last checked "+new Date(data.checkedAt||Date.now()).toLocaleTimeString()+" ("+(data.responseMs||0)+" ms)";' +
      'listEl.innerHTML=(data.services||[]).map(function(s){' +
        'return "<div class=\\"status-row \"+s.status+"\\">"+' +
          '"<div class=\\"status-row-main\\"><i class=\\"dot \"+s.status+"\\"></i><div><strong>"+esc(s.name)+"</strong><span>"+esc(s.description||"")+"</span></div></div>"+' +
          '"<span class=\\"status-tag \"+s.status+"\\">"+label(s.status)+(s.detail?" &middot; "+esc(s.detail):"")+"</span>"+' +
        '"</div>";' +
      '}).join("");' +
    '}' +
    'async function load(){try{var r=await fetch("/api/v1/public/status");var d=await r.json();render(d);}catch(e){overallEl.className="status-overall outage";overallText.textContent="Unable to reach status service";checkedEl.textContent="Retrying...";listEl.innerHTML="<div class=\\"empty-state\\">Could not load status. Retrying shortly.</div>";}}' +
    'document.getElementById("statusRefreshBtn").addEventListener("click",load);' +
    'load();setInterval(load,20000);' +
    '})();</script>',
});

const DEMO_HTML = buildSitePage({
  title: 'Book a Demo - ' + SITE_BRAND,
  active: '/support',
  body:
    '<section class="page-hero"><div class="wrap"><span class="kicker">Book a Demo</span><h1>See ' + SITE_BRAND + ' live.</h1><p>Pick a time and our team will give you a guided walkthrough tailored to your restaurant.</p></div></section>' +
    '<section class="sect"><div class="form-wrap"><div class="form-card reveal">' +
      '<div class="form-2"><label class="full">Your Name <input id="demoName" placeholder="Full name"></label>' +
      '<label>Phone <input id="demoPhone" placeholder="+91XXXXXXXXXX"></label><label>Email <input id="demoEmail" type="email" placeholder="you@business.com"></label>' +
      '<label class="full">Business Name <input id="demoBiz" placeholder="Your restaurant"></label>' +
      '<label class="full">Preferred Date &amp; Time <input id="demoWhen" type="datetime-local"></label>' +
      '<label class="full">Notes <textarea id="demoNotes" placeholder="Tell us about your setup and needs"></textarea></label></div>' +
      '<button class="btn btn-primary" id="demoBtn" style="height:50px">Request Demo</button>' +
      '<div class="status" id="demoStatus">We will confirm your demo by phone or email.</div>' +
    '</div></div></section>',
  script:
    '<script>(function(){var b=document.getElementById("demoBtn");if(!b)return;b.addEventListener("click",function(){' +
    'var s=document.getElementById("demoStatus");var n=document.getElementById("demoName").value.trim();' +
    'var p=document.getElementById("demoPhone").value.trim();var e=document.getElementById("demoEmail").value.trim();' +
    'if(!n||(!p&&!e)){s.className="status error";s.textContent="Enter your name and a phone or email.";return;}' +
    'try{var l=JSON.parse(localStorage.getItem("gaDemoRequests")||"[]");l.unshift({n:n,p:p,e:e,biz:document.getElementById("demoBiz").value,when:document.getElementById("demoWhen").value,notes:document.getElementById("demoNotes").value,at:new Date().toISOString()});localStorage.setItem("gaDemoRequests",JSON.stringify(l.slice(0,50)));}catch(x){}' +
    's.className="status ok";s.textContent="Demo request received for "+n+". Our team will reach out shortly to confirm.";b.textContent="Request Sent";});})();</script>',
});

const SIGNUP_HTML = buildSitePage({
  title: 'Create Business Account - ' + SITE_BRAND,
  active: '/signup',
  body:
    '<section class="signup-page">' +
      '<aside class="signup-brand-side">' +
        '<div class="signup-logo anim-fade-up">' +
          '<img class="ga-logo-img" src="' + SITE_LOGO_URL + '" alt="GI POS" onerror="this.style.display=&quot;none&quot;;this.nextElementSibling.style.display=&quot;grid&quot;">' +
          '<div class="ga-mark" style="display:none">GI</div>' +
          '<div class="signup-logo-text"><strong>' + SITE_BRAND + '</strong><span>Cloud POS Platform</span></div>' +
        '</div>' +
        '<div class="signup-brand-content anim-fade-up" style="animation-delay:.08s">' +
          '<span class="eyebrow" style="color:#fff">Create Business Account</span>' +
          '<h1>Run your restaurant on a platform built for growth.</h1>' +
          '<p>Register your business in minutes. Once our team approves your subscription, your Windows or Android POS activates instantly with your phone or email and password.</p>' +
        '</div>' +
        '<div class="signup-feature-list anim-fade-up" style="animation-delay:.16s">' +
          '<div class="signup-feature"><span class="tick">&#10003;</span> Works fully offline with automatic cloud sync</div>' +
          '<div class="signup-feature"><span class="tick">&#10003;</span> Windows desktop &amp; Android POS in one account</div>' +
          '<div class="signup-feature"><span class="tick">&#10003;</span> Real-time reports, KOT, and device management</div>' +
          '<div class="signup-feature"><span class="tick">&#10003;</span> Priority support from a real team</div>' +
        '</div>' +
        '<div class="signup-brand-footer anim-fade-up" style="animation-delay:.24s">Trusted by growing restaurants &amp; retail counters.</div>' +
      '</aside>' +
      '<div class="signup-form-side">' +
        '<div class="signup-form-card anim-scale-in">' +
          '<div class="signup-form-head"><span class="kicker">Business Registration</span><h2>Your business details</h2><p>Use real billing details - the same information powers your business profile and support.</p></div>' +
          '<div class="signup-field-grid">' +
            '<label class="full">Business Name <input id="businessName" autocomplete="organization" placeholder="e.g. Golden Sea Restaurant"></label>' +
            '<label>Owner Name <input id="ownerName" autocomplete="name" placeholder="Full name"></label>' +
            '<label>Phone <input id="phone" autocomplete="tel" placeholder="+91XXXXXXXXXX"></label>' +
            '<label>Email <input id="email" type="email" autocomplete="email" placeholder="you@business.com"></label>' +
            '<label class="full">Password <input id="password" type="password" autocomplete="new-password" placeholder="Min 6 characters"></label>' +
          '</div>' +
          '<button class="btn btn-primary signup-submit" id="signupBtn">Create Business Account</button>' +
          '<div class="status" id="status">Fill in your details to get started.</div>' +
          '<div class="row" style="gap:8px;flex-wrap:wrap"><span class="signup-plan-chip">Premium</span><span class="signup-plan-chip">Gold</span><span class="signup-plan-chip">Offline</span><span class="signup-plan-chip">Android</span></div>' +
          '<div class="row signup-links" style="justify-content:space-between"><a href="/portal" style="font-weight:900">Already approved? Sign in</a><a href="/demo" style="font-weight:900;color:var(--muted)">Prefer a demo first?</a></div>' +
        '</div>' +
      '</div>' +
    '</section>',
  script:
    '<script>(function(){' +
    'var statusEl=document.getElementById("status");' +
    'function setStatus(t,k){statusEl.className="status "+(k||"");statusEl.textContent=t;}' +
    'document.getElementById("signupBtn").addEventListener("click",async function(){' +
    'setStatus("Submitting your account...","");' +
    'var body={businessName:document.getElementById("businessName").value,ownerName:document.getElementById("ownerName").value,phone:document.getElementById("phone").value,email:document.getElementById("email").value,password:document.getElementById("password").value};' +
    'try{var response=await fetch("/api/v1/signup",{method:"POST",headers:{"content-type":"application/json"},body:JSON.stringify(body)});' +
    'var result=await response.json().catch(function(){return {};});' +
    'if(!response.ok||result.ok===false)throw new Error(result.error||"Signup failed");' +
    'setStatus("Account created! Approval is pending. You can sign in from the portal once approved.","ok");}' +
    'catch(error){setStatus(error.message||"Signup failed","error");}' +
    '});})();</script>',
});

const PORTAL_HTML = `<!doctype html>
<html>
<head>
  <meta charset="utf-8">
  <meta name="viewport" content="width=device-width, initial-scale=1">
  <title>GI POS - Client Portal</title>
  <link rel="icon" href="${SITE_LOGO_URL}">
  <style>${BASE_STYLES}</style>
</head>
<body>
  <header id="portalHeader">
    <a class="brand-lockup" href="/" style="text-decoration:none;color:inherit">
      <img class="brand-logo-img" src="${SITE_LOGO_URL}" alt="GI POS" onerror="this.style.display='none';this.nextElementSibling.style.display='grid'">
      <div class="brand-mark" style="display:none">GI</div>
      <div>
        <h1>GI POS Portal</h1>
        <p>Subscription, downloads, and account security</p>
      </div>
    </a>
    <div class="nav-actions">
      <a class="nav-link" href="/signup">Signup</a>
      <button id="logoutBtn" style="display:none">Logout</button>
    </div>
  </header>
  <main id="portalMain">
    <div class="auth-shell" id="portalLoginShell">
      <section class="card hero-panel anim-fade-up" id="portalIntro">
        <div class="stack">
          <div class="signup-logo" style="margin-bottom:6px">
            <img class="ga-logo-img" src="${SITE_LOGO_URL}" alt="GI POS" onerror="this.style.display='none';this.nextElementSibling.style.display='grid'">
            <div class="ga-mark" style="display:none;box-shadow:0 12px 30px rgba(0,0,0,.28)">GI</div>
            <div class="signup-logo-text"><strong>GI POS</strong><span>Client Portal</span></div>
          </div>
          <span class="eyebrow" style="color:#fff">Your Account</span>
          <h2>Manage your subscription, licenses, and downloads in one place.</h2>
          <p>Check license validity and renewal, download the POS app, generate transfer codes, and reset staff PINs - all without touching the counter.</p>
        </div>
        <div class="step-list">
          <div>Sign in with your signup phone/email and password</div>
          <div>Track license status, expiry, and pending renewals</div>
          <div>Download updates and manage staff PINs after cloud sync</div>
        </div>
      </section>

      <section class="card form-panel anim-scale-in" id="loginCard">
        <div class="stack">
          <span class="eyebrow">Secure Login</span>
          <h2>Client Login</h2>
          <p>Use the phone/email and password submitted during signup.</p>
        </div>
        <div class="grid">
          <label>Phone or Email <input id="clientLogin" autocomplete="username"></label>
          <label>Password <input id="clientPassword" type="password" autocomplete="current-password"></label>
        </div>
        <div class="row">
          <button class="primary" id="loginBtn">Login</button>
          <button id="forgotBtn">Forgot Password?</button>
        </div>
        <div class="status" id="loginStatus">Waiting for login.</div>
        <div class="status" id="forgotPanel" style="display:none">
          <strong>Reset Password</strong>
          <div class="grid" style="margin-top:12px">
            <label>Phone or Email <input id="resetLogin" autocomplete="username"></label>
            <label>Recovery Code <input id="resetRecoveryCode" placeholder="XXXX-XXXX-XXXX"></label>
            <label>New Password <input id="resetPassword" type="password" autocomplete="new-password"></label>
          </div>
          <button class="primary" id="resetPasswordBtn" style="margin-top:12px">Reset Password</button>
        </div>
      </section>
    </div>

    <div id="portalShell" class="portal-fullbleed" style="display:none">
      <div class="app-shell">
        <aside class="app-sidebar">
          <a class="brand-lockup" href="/" style="text-decoration:none;color:inherit"><img class="brand-logo-img sidebar-logo-img" src="${SITE_LOGO_URL}" alt="GI POS" onerror="this.style.display='none';this.nextElementSibling.style.display='grid'"><div class="brand-mark" style="display:none">GI</div><div><h1>GI POS Portal</h1><p id="sidebarAccountName">Client account</p></div></a>
          <nav class="sidebar-nav">
            <div class="sidebar-section-label">Overview</div>
            <button class="sidebar-link active" type="button" data-portal="dashboard"><span class="sidebar-ic">D</span> Dashboard</button>
            <button class="sidebar-link" type="button" data-portal="reports"><span class="sidebar-ic">R</span> Reports</button>
            <button class="sidebar-link" type="button" data-portal="business"><span class="sidebar-ic">B</span> Business List</button>
            <button class="sidebar-link" type="button" data-portal="plan"><span class="sidebar-ic">P</span> Current Plan</button>
            <div class="sidebar-section-label">Manage</div>
            <button class="sidebar-link" type="button" data-portal="users"><span class="sidebar-ic">U</span> Users &amp; Staff</button>
            <button class="sidebar-link" type="button" data-portal="devices"><span class="sidebar-ic">V</span> Signed Devices</button>
            <button class="sidebar-link" type="button" data-portal="downloads"><span class="sidebar-ic">S</span> Software Downloads</button>
            <button class="sidebar-link" type="button" data-portal="pin"><span class="sidebar-ic">U</span> Desktop User PIN</button>
            <button class="sidebar-link" type="button" data-portal="security"><span class="sidebar-ic">L</span> Account Security</button>
          </nav>
          <div class="sidebar-foot">
            <span class="sidebar-badge" id="sidebarBadge">Loading...</span>
            <button class="primary" id="refreshBtn">Refresh Data</button>
            <button id="logoutBtn2">Sign Out</button>
          </div>
        </aside>

        <main class="app-main"><div class="app-main-inner">
          <!-- DASHBOARD -->
          <section class="admin-page-view active" data-portal-view="dashboard">
            <div class="app-topbar">
              <div class="section-title"><span class="eyebrow">Account</span><h2 id="accountTitle">Account</h2><p id="accountSubtitle">Subscription and devices</p></div>
              <div class="topbar-actions"><button class="btn btn-primary btn-sm" id="dashRefreshBtn" type="button">Refresh</button></div>
            </div>

            <div class="portal-hero" id="portalHero"></div>

            <div class="kpi-grid" id="portalKpis"></div>

            <div class="dash-grid">
              <section class="card panel">
                <div class="section-title"><span class="eyebrow">Licenses</span><h2>License, Validity &amp; Renewals</h2><p>Subscription status, expiry, and renewal for each business</p></div>
                <div class="stack" id="portalLicenses"></div>
              </section>
              <div class="dash-side">
                <section class="card panel">
                  <div class="section-title"><span class="eyebrow">Shortcuts</span><h2 style="font-size:18px">Quick Actions</h2></div>
                  <div class="quick-actions" id="portalQuickActions"></div>
                </section>
                <section class="card panel">
                  <div class="section-title"><span class="eyebrow">Account</span><h2 style="font-size:18px">Account Overview</h2></div>
                  <div class="stack" id="portalAccountOverview"></div>
                </section>
              </div>
            </div>

            <section class="card panel">
              <div class="section-title"><span class="eyebrow">Devices</span><h2>Registered Devices</h2><p>Active counters and mobiles connected to your businesses</p></div>
              <div class="stack" id="portalDevices"></div>
            </section>
            <div class="status" id="portalStatus">Loaded portal.</div>
          </section>

          <!-- REPORTS -->
          <section class="admin-page-view" data-portal-view="reports">
            <div class="app-topbar">
              <div class="section-title"><span class="eyebrow">Analytics</span><h2>Sales Reports</h2><p>Settled bills synced from your POS. Filter by date, type, payment method, and category.</p></div>
              <div class="topbar-actions"><button class="btn btn-primary btn-sm" id="reportRunBtn" type="button">Apply Filters</button></div>
            </div>

            <section class="card panel report-filter-card">
              <div class="report-filter-grid">
                <label>Business <select id="reportRestaurant"></select></label>
                <label>From <input id="reportFrom" type="date"></label>
                <label>To <input id="reportTo" type="date"></label>
                <label>Report <select id="reportCategoryView">
                  <option value="overview">Overview</option>
                  <option value="payment">By Payment Method</option>
                  <option value="type">By Order Type</option>
                  <option value="category">By Category</option>
                  <option value="items">Top Items</option>
                  <option value="orders">Bill List</option>
                </select></label>
                <label>Order Type <select id="reportOrderType"><option value="all">All types</option></select></label>
                <label>Payment <select id="reportPayment"><option value="all">All methods</option></select></label>
                <label>Category <select id="reportCategory"><option value="all">All categories</option></select></label>
              </div>
              <div class="report-quick-ranges" id="reportQuickRanges">
                <button class="chip" type="button" data-range="today">Today</button>
                <button class="chip" type="button" data-range="7">Last 7 days</button>
                <button class="chip" type="button" data-range="30">Last 30 days</button>
                <button class="chip" type="button" data-range="month">This month</button>
              </div>
            </section>

            <div class="kpi-grid" id="reportKpis"></div>

            <section class="card panel">
              <div class="section-title"><span class="eyebrow" id="reportViewEyebrow">Overview</span><h2 id="reportViewTitle">Report</h2><p id="reportViewSubtitle">Filtered results</p></div>
              <div id="reportBody"><div class="empty-state">Select a business and apply filters to view reports.</div></div>
              <div class="status" id="reportStatus" style="margin-top:14px">Reports read from your latest cloud sync.</div>
            </section>
          </section>

          <!-- BUSINESS LIST -->
          <section class="admin-page-view" data-portal-view="business">
            <div class="app-topbar"><div class="section-title"><span class="eyebrow">Business List</span><h2>Your Businesses</h2><p>Approved restaurants, licenses, and connected devices</p></div></div>
            <div class="restaurant-list" id="clientRestaurantRows"></div>
            <section class="card panel">
              <div class="section-title"><span class="eyebrow">Device Control</span><h2>Main App Transfer Code</h2><p>Use this when moving the main POS app to another PC. Existing cloud connections for the selected restaurant will be logged out.</p></div>
              <div class="grid"><label>Restaurant <select id="transferRestaurant"></select></label></div>
              <button class="btn btn-primary" id="generateTransferCodeBtn" style="justify-self:start">Generate Transfer Code</button>
              <div class="code-box" id="transferCode">------</div>
              <div class="status" id="transferStatus">No transfer code generated.</div>
            </section>
          </section>

          <!-- CURRENT PLAN -->
          <section class="admin-page-view" data-portal-view="plan">
            <div class="app-topbar"><div class="section-title"><span class="eyebrow">Subscription</span><h2>Current Plan &amp; Details</h2><p>Your active plans and everything available</p></div></div>
            <div class="stack" id="portalCurrentPlan"></div>
            <section class="card panel">
              <div class="section-title"><span class="eyebrow">Plans</span><h2>All Plans</h2><p>Premium, Gold, and Offline are Windows plans. Android provides native mobile billing.</p></div>
              <div class="plan-grid" id="portalPlanRows"></div>
            </section>
          </section>

          <!-- USERS & STAFF -->
          <section class="admin-page-view" data-portal-view="users">
            <div class="app-topbar">
              <div class="section-title"><span class="eyebrow">Team</span><h2>Users &amp; Staff</h2><p>Staff accounts are now created and managed in the GI POS Windows software under User Manage. This page is view-only.</p></div>
            </div>
            <section class="card panel staff-toolbar">
              <label>Business <select id="staffRestaurant"></select></label>
              <div class="staff-capacity" id="staffCapacity">Select a business to manage staff.</div>
            </section>
            <section class="card panel">
              <div class="section-title"><span class="eyebrow">Team Members</span><h3 style="font-size:18px">Staff Accounts</h3><p>View full details with QR &amp; barcode, edit permissions, or remove staff.</p></div>
              <div class="staff-list" id="staffList"><div class="empty-state">Select a business to view staff.</div></div>
              <div class="status" id="staffFormStatus" style="margin-top:14px">Staff are managed in the GI POS Windows software (User Manage). This list is view-only.</div>
            </section>
          </section>

          <!-- SIGNED DEVICES -->
          <section class="admin-page-view" data-portal-view="devices">
            <div class="app-topbar">
              <div class="section-title"><span class="eyebrow">Security</span><h2>Signed Devices</h2><p>Every device signed in or linked to your account. Disable to block a device, or terminate to log it out immediately.</p></div>
              <div class="topbar-actions"><button class="btn btn-danger btn-sm" id="devicesDeleteAllBtn" type="button">Delete all devices</button><button class="btn btn-primary btn-sm" id="devicesRefreshBtn" type="button">Refresh</button></div>
            </div>
            <section class="card panel">
              <div class="section-title"><span class="eyebrow">Linked Devices</span><h3 style="font-size:18px">Active Device Sessions</h3><p>Only devices currently signed in are shown. Name, serial number, platform and last activity for each.</p></div>
              <div class="stack" id="signedDeviceList"><div class="empty-state">Loading devices...</div></div>
              <div class="status" id="signedDeviceStatus" style="margin-top:14px">Terminate logs a device out immediately and ends its session.</div>
            </section>
          </section>

          <!-- SOFTWARE DOWNLOADS -->
          <section class="admin-page-view" data-portal-view="downloads">
            <div class="app-topbar"><div class="section-title"><span class="eyebrow">Software Downloads</span><h2>Download GI POS</h2><p>Install the application that matches your active plan</p></div></div>
            <div class="grid-2">
              <section class="card panel">
                <div class="section-title"><span class="eyebrow">Windows</span><h3 style="font-size:18px">Desktop POS Software</h3><p>Full-featured Windows billing counter with KOT, tables, and printing.</p></div>
                <div class="row"><a class="btn btn-primary" href="/download/windows">Download Setup (.exe)</a><a class="btn" href="/updates/win/latest.yml" target="_blank" rel="noreferrer">Version Info</a></div>
              </section>
              <section class="card panel">
                <div class="section-title"><span class="eyebrow">Android</span><h3 style="font-size:18px">GI POS Mobile (GPS) App</h3><p>Native Android billing for phone and tablet with offline support.</p></div>
                <div class="row"><a class="btn btn-primary" href="/download/android">Download Android APK</a></div>
              </section>
            </div>
            <div class="status">Use the same approved client account to activate the compatible app. Android plans cannot activate Windows, and Windows plans cannot activate Android.</div>
          </section>

          <!-- DESKTOP USER PIN RESET -->
          <section class="admin-page-view" data-portal-view="pin">
            <div class="app-topbar"><div class="section-title"><span class="eyebrow">Desktop Users</span><h2>Desktop User PIN Reset</h2><p>Reset a Windows app user PIN. The desktop app applies it on the next cloud sync.</p></div></div>
            <section class="card panel" style="max-width:760px">
              <div class="form-2">
                <label class="full">Restaurant <select id="pinResetRestaurant"></select></label>
                <label class="full">App User <select id="pinResetUser"></select></label>
                <label>New PIN <input id="pinResetNewPin" type="password" inputmode="numeric" autocomplete="new-password"></label>
                <label>Confirm PIN <input id="pinResetConfirmPin" type="password" inputmode="numeric" autocomplete="new-password"></label>
              </div>
              <button class="btn btn-primary" id="resetStaffPinBtn" style="justify-self:start">Send PIN Reset</button>
              <div class="status" id="staffPinStatus">Run cloud sync once from the desktop app to publish app users here.</div>
            </section>
          </section>

          <!-- ACCOUNT SECURITY -->
          <section class="admin-page-view" data-portal-view="security">
            <div class="app-topbar"><div class="section-title"><span class="eyebrow">Security</span><h2>Account Security</h2><p>Change your portal password and generate a recovery code</p></div></div>
            <section class="card panel" style="max-width:760px">
              <div class="form-2">
                <label>Current Password <input id="currentPassword" type="password" autocomplete="current-password"></label>
                <label>New Password <input id="newPassword" type="password" autocomplete="new-password"></label>
              </div>
              <div class="row"><button class="btn btn-primary" id="changePasswordBtn">Change Password</button><button class="btn" id="generateRecoveryBtn">Generate Recovery Code</button></div>
              <div class="code-box" id="portalRecoveryCode">----</div>
              <div class="status" id="securityStatus">Generate a recovery code before you forget the portal password. It is shown only once.</div>
            </section>
          </section>
        </div></main>
      </div>
    </div>
  </main>

  <!-- STAFF FORM MODAL -->
  <div class="pmodal-overlay" id="staffModal" aria-hidden="true">
    <div class="pmodal" role="dialog" aria-modal="true" aria-labelledby="staffModalTitle">
      <div class="pmodal-head">
        <div><span class="eyebrow">Staff Account</span><h3 id="staffModalTitle">Add Staff Member</h3></div>
        <button class="pmodal-close" id="staffModalClose" aria-label="Close">&times;</button>
      </div>
      <div class="pmodal-body">
        <div class="pmodal-steps" id="staffSteps">
          <button type="button" class="pmodal-step active" data-step="basic">1. Basic Info</button>
          <button type="button" class="pmodal-step" data-step="perms">2. Permissions</button>
          <button type="button" class="pmodal-step" data-step="tables">3. Table Access</button>
        </div>

        <section class="pmodal-section active" data-step-view="basic">
          <h4 class="pmodal-section-title">Basic Information</h4>
          <p class="pmodal-section-sub">Core details used to identify this staff member.</p>
          <div class="form-2">
            <label class="full">Staff Name <input id="staffName" placeholder="Full name"></label>
            <label>Mobile Number <input id="staffMobile" inputmode="tel" placeholder="+91XXXXXXXXXX"></label>
            <label>Role
              <select id="staffRoleSelect">
                <option value="Captain">Captain Users</option>
                <option value="Admin">Admin</option>
                <option value="Billing 1">Billing 1</option>
                <option value="Billing 2">Billing 2</option>
                <option value="Counter Staff">Counter Staff</option>
                <option value="Cashier" selected>Cashier</option>
                <option value="__custom">Custom role...</option>
              </select>
            </label>
            <label class="full" id="staffCustomRoleWrap" style="display:none">Custom Role <input id="staffCustomRole" placeholder="e.g. Kitchen Manager"></label>
            <label class="full">Password <input id="staffPassword" type="password" autocomplete="new-password" placeholder="Optional login password, min 4 characters"></label>
          </div>
        </section>

        <section class="pmodal-section" data-step-view="perms">
          <h4 class="pmodal-section-title">Permissions</h4>
          <p class="pmodal-section-sub">Grant access exactly where this staff member needs it.</p>
          <div class="pmodal-check-actions"><button type="button" class="btn btn-sm" id="permAll">Select all</button><button type="button" class="btn btn-sm" id="permNone">Clear all</button></div>
          <div class="check-grid" id="staffPermGrid"></div>
        </section>

        <section class="pmodal-section" data-step-view="tables">
          <h4 class="pmodal-section-title">Table &amp; Area Access</h4>
          <p class="pmodal-section-sub">Choose which tables or areas this staff member can serve.</p>
          <div class="pmodal-check-actions"><button type="button" class="btn btn-sm" id="tableAll">Select all</button><button type="button" class="btn btn-sm" id="tableNone">Clear all</button></div>
          <div class="check-grid" id="staffTableGrid"></div>
        </section>
      </div>
      <div class="pmodal-foot">
        <div class="status" id="staffModalStatus" style="margin:0;flex:1">Fill in the staff details across the sections.</div>
        <button class="btn" id="staffModalCancel">Cancel</button>
        <button class="btn btn-primary" id="staffModalSave">Create Staff</button>
      </div>
    </div>
  </div>

  <!-- STAFF DETAILS MODAL -->
  <div class="pmodal-overlay" id="staffDetailsModal" aria-hidden="true">
    <div class="pmodal pmodal-sm" role="dialog" aria-modal="true">
      <div class="pmodal-head">
        <div><span class="eyebrow">Staff Details</span><h3 id="staffDetailsName">Staff</h3></div>
        <button class="pmodal-close" id="staffDetailsClose" aria-label="Close">&times;</button>
      </div>
      <div class="pmodal-body" id="staffDetailsBody"></div>
      <div class="pmodal-foot">
        <button class="btn" id="staffDetailsCloseBtn">Close</button>
      </div>
    </div>
  </div>

  ${APP_POWERED_BY_HTML}
  <script>
    const portalLoginShell = document.getElementById('portalLoginShell');
    const portalShell = document.getElementById('portalShell');
    const loginCard = document.getElementById('loginCard');
    const portalKpisEl = document.getElementById('portalKpis');
    const portalLicensesEl = document.getElementById('portalLicenses');
    const portalQuickActionsEl = document.getElementById('portalQuickActions');
    const portalDevicesEl = document.getElementById('portalDevices');
    const portalCurrentPlanEl = document.getElementById('portalCurrentPlan');
    const sidebarBadgeEl = document.getElementById('sidebarBadge');
    const sidebarAccountNameEl = document.getElementById('sidebarAccountName');
    const loginStatus = document.getElementById('loginStatus');
    const portalStatus = document.getElementById('portalStatus');
    const rowsEl = document.getElementById('clientRestaurantRows');
    const transferRestaurantEl = document.getElementById('transferRestaurant');
    const transferCodeEl = document.getElementById('transferCode');
    const transferStatusEl = document.getElementById('transferStatus');
    const securityStatusEl = document.getElementById('securityStatus');
    const recoveryCodeEl = document.getElementById('portalRecoveryCode');
    const staffPinStatusEl = document.getElementById('staffPinStatus');
    const pinResetRestaurantEl = document.getElementById('pinResetRestaurant');
    const pinResetUserEl = document.getElementById('pinResetUser');
    const forgotPanel = document.getElementById('forgotPanel');
    const logoutBtn = document.getElementById('logoutBtn');
    const portalPlanRows = document.getElementById('portalPlanRows');
    const planCatalog = ${PLAN_CATALOG_JSON};
    let clientToken = localStorage.getItem('giClientToken') || '';
    let portalRestaurants = [];

    function esc(value) {
      return String(value || '').replace(/[&<>"']/g, function (ch) {
        return ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#039;' })[ch];
      });
    }
    function setStatus(element, text, kind) {
      element.className = 'status ' + (kind || '');
      element.textContent = text;
    }
    async function clientApi(path, options) {
      const headers = Object.assign(
        { 'content-type': 'application/json', 'x-client-token': clientToken },
        (options && options.headers) || {}
      );
      const response = await fetch(path, Object.assign({}, options || {}, { headers }));
      const result = await response.json().catch(function () { return {}; });
      if (!response.ok || result.ok === false) throw new Error(result.error || 'Request failed');
      return result;
    }
    function showPortal(show) {
      portalLoginShell.style.display = show ? 'none' : '';
      portalShell.style.display = show ? '' : 'none';
      loginCard.style.display = show ? 'none' : '';
      logoutBtn.style.display = show ? '' : 'none';
      var header = document.getElementById('portalHeader');
      var main = document.getElementById('portalMain');
      if (header) header.style.display = show ? 'none' : '';
      if (main) main.style.padding = show ? '0' : '';
    }
    function goToPortalPage(pageId) {
      var links = document.querySelectorAll('.sidebar-link[data-portal]');
      for (var i = 0; i < links.length; i++) {
        links[i].classList.toggle('active', links[i].getAttribute('data-portal') === pageId);
      }
      var views = document.querySelectorAll('[data-portal-view]');
      for (var j = 0; j < views.length; j++) {
        views[j].classList.toggle('active', views[j].getAttribute('data-portal-view') === pageId);
      }
    }
    function subscriptionText(restaurant) {
      if (!restaurant.subscription_id) return 'No subscription';
      return restaurant.plan_name + ' / ' + restaurant.subscription_status + ' / ' + new Date(restaurant.expires_at).toLocaleDateString();
    }
    function getPlan(planName) {
      const value = String(planName || '').toLowerCase();
      return planCatalog.find(function (plan) { return value.indexOf(plan.id) >= 0 || value.indexOf(String(plan.name).toLowerCase()) >= 0; }) || null;
    }
    function planFeaturesHtml(plan) {
      return '<ul>' + (plan.features || []).map(function (feature) { return '<li>' + esc(feature) + '</li>'; }).join('') + '</ul>';
    }
    function portalDaysUntil(value) {
      if (!value) return null;
      var ms = new Date(value).getTime() - Date.now();
      if (!isFinite(ms)) return null;
      return Math.ceil(ms / (24 * 60 * 60 * 1000));
    }
    function renderPortalDashboard(account, restaurants) {
      var approved = restaurants.filter(function (r) { return r.status === 'approved'; });
      var activeSubs = approved.filter(function (r) {
        return r.subscription_status === 'active' || r.subscription_status === 'trial';
      });
      var totalDevices = restaurants.reduce(function (sum, r) { return sum + Number(r.active_devices || 0); }, 0);
      var soonest = null;
      activeSubs.forEach(function (r) {
        var d = portalDaysUntil(r.expires_at);
        if (d != null && (soonest == null || d < soonest)) soonest = d;
      });
      var nextRenewalText = soonest == null ? 'No active plan' : (soonest < 0 ? 'Expired' : soonest + ' days');

      if (portalKpisEl) {
        portalKpisEl.innerHTML =
          '<div class="kpi"><div class="kpi-top"><span class="kpi-label">Businesses</span><span class="kpi-icon">B</span></div><div class="kpi-value">' + restaurants.length + '</div><span class="kpi-hint">' + approved.length + ' approved</span></div>' +
          '<div class="kpi kpi-green"><div class="kpi-top"><span class="kpi-label">Active Licenses</span><span class="kpi-icon">L</span></div><div class="kpi-value">' + activeSubs.length + '</div><span class="kpi-hint">Subscription running</span></div>' +
          '<div class="kpi kpi-slate"><div class="kpi-top"><span class="kpi-label">Active Devices</span><span class="kpi-icon">D</span></div><div class="kpi-value">' + totalDevices + '</div><span class="kpi-hint">Connected counters</span></div>' +
          '<div class="kpi ' + (soonest != null && soonest <= 14 ? 'kpi-amber' : 'kpi') + '"><div class="kpi-top"><span class="kpi-label">Next Renewal</span><span class="kpi-icon">R</span></div><div class="kpi-value" style="font-size:22px">' + esc(nextRenewalText) + '</div><span class="kpi-hint">Soonest expiry</span></div>';
      }

      if (portalLicensesEl) {
        if (!approved.length) {
          portalLicensesEl.innerHTML = '<div class="empty-state">No approved license yet. GI admin approval is required.</div>';
        } else {
          portalLicensesEl.innerHTML = approved.map(function (r) {
            var days = portalDaysUntil(r.expires_at);
            var plan = getPlan(r.plan_name);
            var cls = days == null ? '' : (days < 0 ? 'expired' : (days <= 14 ? 'expiring' : ''));
            var statusBadge = days == null ? '<span class="badge warn">No expiry</span>'
              : days < 0 ? '<span class="badge bad">Expired</span>'
              : (days <= 14 ? '<span class="badge warn">' + days + 'd left</span>' : '<span class="badge good">Active</span>');
            var fillCls = days == null ? '' : (days < 0 ? 'danger' : (days <= 14 ? 'warn' : ''));
            var pct = days == null ? 0 : Math.max(4, Math.min(100, Math.round((days / 365) * 100)));
            return '<div class="license-card ' + cls + '">' +
              '<div class="license-card-head"><strong>' + esc(r.name) + '</strong>' + statusBadge + '</div>' +
              '<div class="info-line"><span>Plan</span><strong>' + esc(plan ? plan.name : r.plan_name || 'Pending') + '</strong></div>' +
              '<div class="info-line"><span>Status</span><strong>' + esc(r.subscription_status || 'none') + '</strong></div>' +
              '<div class="info-line"><span>Valid Until</span><strong>' + esc(r.expires_at ? new Date(r.expires_at).toLocaleDateString() : 'Not set') + '</strong></div>' +
              '<div class="info-line"><span>Devices</span><strong>' + Number(r.active_devices || 0) + ' active</strong></div>' +
              '<div class="validity-bar"><div class="validity-fill ' + fillCls + '" style="width:' + pct + '%"></div></div>' +
            '</div>';
          }).join('');
        }
      }

      if (portalQuickActionsEl) {
        portalQuickActionsEl.innerHTML =
          '<button class="quick-action" data-goto="downloads"><strong>Download Software</strong><span>Windows &amp; Android setup</span></button>' +
          '<button class="quick-action" data-goto="business"><strong>Transfer Code</strong><span>Move POS to another PC</span></button>' +
          '<button class="quick-action" data-goto="pin"><strong>Reset Staff PIN</strong><span>Desktop app users</span></button>' +
          '<button class="quick-action" data-goto="security"><strong>Account Security</strong><span>Password &amp; recovery code</span></button>' +
          '<button class="quick-action" data-goto="plan"><strong>Current Plan</strong><span>Subscription details</span></button>' +
          '<button class="quick-action" data-goto="business"><strong>Business List</strong><span>Licenses &amp; devices</span></button>';
        Array.prototype.forEach.call(portalQuickActionsEl.querySelectorAll('[data-goto]'), function (btn) {
          btn.addEventListener('click', function () { goToPortalPage(btn.getAttribute('data-goto')); });
        });
      }

      // Account details + sidebar labels
      if (sidebarAccountNameEl) sidebarAccountNameEl.textContent = (account && (account.ownerName || account.phone || account.email)) || 'Client account';
      if (sidebarBadgeEl) sidebarBadgeEl.textContent = restaurants.length + ' business(es)';

      // Registered devices (across all businesses)
      if (portalDevicesEl) {
        var deviceRows = [];
        restaurants.forEach(function (r) {
          (r.devices || []).forEach(function (d) {
            var platform = d.platform === 'android' ? 'Android' : 'Windows';
            var seen = d.last_seen_at ? new Date(d.last_seen_at).toLocaleString() : 'Never';
            deviceRows.push('<div class="info-line"><span>' + esc(d.name || 'Device') + ' / ' + esc(r.name) + '</span><strong>' + platform + ' / ' + (d.active ? 'Active' : 'Disabled') + ' / ' + esc(seen) + '</strong></div>');
          });
        });
        portalDevicesEl.innerHTML = deviceRows.length ? deviceRows.join('') : '<div class="empty-state">No devices connected yet.</div>';
      }

      // Current plan page
      if (portalCurrentPlanEl) {
        if (!approved.length) {
          portalCurrentPlanEl.innerHTML = '<div class="empty-state">No active plan yet. Approval is required.</div>';
        } else {
          portalCurrentPlanEl.innerHTML = approved.map(function (r) {
            var plan = getPlan(r.plan_name);
            var days = portalDaysUntil(r.expires_at);
            var feats = (plan && plan.features ? plan.features : []).map(function (f) { return '<li>' + esc(f) + '</li>'; }).join('');
            return '<div class="license-card">' +
              '<div class="license-card-head"><strong>' + esc(r.name) + ' - ' + esc(plan ? plan.name : (r.plan_name || 'Plan')) + '</strong>' +
                (days != null && days < 0 ? '<span class="badge bad">Expired</span>' : '<span class="badge good">' + esc(r.subscription_status || 'active') + '</span>') + '</div>' +
              '<div class="info-line"><span>Valid Until</span><strong>' + esc(r.expires_at ? new Date(r.expires_at).toLocaleDateString() : 'Not set') + '</strong></div>' +
              '<div class="info-line"><span>Devices Allowed</span><strong>' + esc(plan ? plan.counterLabel : '-') + '</strong></div>' +
              (feats ? '<ul style="margin:8px 0 0;padding-left:18px;color:#475569;font-weight:700;line-height:1.7;font-size:13.5px">' + feats + '</ul>' : '') +
            '</div>';
          }).join('');
        }
      }

      // Hero welcome strip
      var heroEl = document.getElementById('portalHero');
      if (heroEl) {
        var displayName = (account && (account.ownerName || account.phone || account.email)) || 'there';
        var statusLabel = soonest == null ? 'No active plan' : (soonest < 0 ? 'Renewal overdue' : (soonest <= 14 ? soonest + ' days to renewal' : 'Subscription active'));
        var statusCls = soonest == null ? 'warn' : (soonest < 0 ? 'bad' : (soonest <= 14 ? 'warn' : 'good'));
        heroEl.innerHTML =
          '<div class="portal-hero-main">' +
            '<span class="eyebrow" style="color:#fff">Welcome back</span>' +
            '<h2>' + esc(displayName) + '</h2>' +
            '<p>' + approved.length + ' active business' + (approved.length === 1 ? '' : 'es') + ' &middot; ' + totalDevices + ' connected device' + (totalDevices === 1 ? '' : 's') + '</p>' +
          '</div>' +
          '<div class="portal-hero-badge"><span class="badge ' + statusCls + '">' + esc(statusLabel) + '</span></div>';
      }

      // Account overview card
      var overviewEl = document.getElementById('portalAccountOverview');
      if (overviewEl) {
        var contact = (account && (account.phone || account.email)) || 'Not set';
        var accStatus = (account && account.status) || 'unknown';
        overviewEl.innerHTML =
          '<div class="info-line"><span>Owner</span><strong>' + esc((account && account.ownerName) || 'Client') + '</strong></div>' +
          '<div class="info-line"><span>Contact</span><strong>' + esc(contact) + '</strong></div>' +
          '<div class="info-line"><span>Account Status</span><strong>' + esc(accStatus) + '</strong></div>' +
          '<div class="info-line"><span>Businesses</span><strong>' + restaurants.length + '</strong></div>' +
          '<div class="info-line"><span>Recovery Code</span><strong>' + (account && account.recoveryCodeSetAt ? 'Set' : 'Not generated') + '</strong></div>';
      }
    }
    function renderPlanCards(restaurants) {
      const activePlanIds = new Set(restaurants.map(function (restaurant) {
        const plan = getPlan(restaurant.plan_name);
        return plan && plan.id;
      }).filter(Boolean));
      portalPlanRows.innerHTML = planCatalog.map(function (plan) {
        const active = activePlanIds.has(plan.id);
        return '<article class="plan-card ' + (active ? 'current-plan-card' : '') + '">' +
          '<h3>' + esc(plan.name) + '<span class="badge ' + (active ? 'good' : '') + '">' + (active ? 'Current' : 'Available') + '</span></h3>' +
          '<p>' + esc(plan.subtitle) + '</p>' +
          '<div class="plan-meta"><span class="badge">' + esc(plan.counterLabel) + '</span><span class="badge">' + esc((plan.platforms || []).map(function (platform) { return platform === 'android' ? 'Android' : 'Windows'; }).join(' + ')) + '</span><span class="badge ' + (plan.localPos ? 'good' : 'warn') + '">' + (plan.localPos ? 'Local POS included' : 'Local POS not included') + '</span></div>' +
          planFeaturesHtml(plan) +
        '</article>';
      }).join('');
    }
    function renderRestaurants(restaurants) {
      if (!restaurants.length) {
        rowsEl.innerHTML = '<div class="empty-state">No restaurant approved for this account yet.</div>';
        return;
      }

      rowsEl.innerHTML = restaurants.map(function (restaurant) {
        const approved = restaurant.status === 'approved';
        const subActive = restaurant.subscription_status === 'active' || restaurant.subscription_status === 'trial';
        const canPair = approved && subActive;
        const badgeClass = approved ? 'good' : restaurant.status === 'suspended' ? 'bad' : '';
        const cardClass = restaurant.status === 'suspended' ? 'suspended' : approved ? '' : 'pending';
        const devices = String(restaurant.active_devices || 0);
        const plan = getPlan(restaurant.plan_name);
        const activeUsers = Number(restaurant.active_users || 1);
        const userLimit = restaurant.max_users == null ? 'Unlimited' : String(restaurant.max_users);
        const deviceList = (restaurant.devices || []).map(function (device) {
          const platform = device.platform === 'android' ? 'Android' : 'Windows';
          return esc(device.name) + ' / ' + platform + (device.active ? '' : ' (disabled)');
        }).join('');
        return '<article class="restaurant-card ' + cardClass + '">' +
          '<div class="restaurant-main">' +
            '<div class="restaurant-title"><strong>' + esc(restaurant.name) + '</strong><small>' + esc(restaurant.id) + '</small><span class="badge ' + badgeClass + '">' + esc(restaurant.status) + '</span></div>' +
            '<div><span class="eyebrow">Plan & Access</span><p>' + esc(subscriptionText(restaurant)) + '</p><div class="plan-meta"><span class="badge good">' + activeUsers + ' / ' + esc(userLimit) + ' users</span><span class="badge ' + (canPair ? 'good' : 'warn') + '">' + (canPair ? 'Activation ready' : 'Approval required') + '</span><span class="badge">' + esc(plan ? plan.counterLabel : 'Plan pending') + '</span></div></div>' +
            '<div><span class="eyebrow">Connected Devices</span><p>' + esc(devices) + ' active</p><div class="device-list">' + (deviceList || 'No devices connected') + '</div></div>' +
          '</div>' +
        '</article>';
      }).join('');

    }
    function renderTransferOptions() {
      const previousRestaurantId = transferRestaurantEl.value;
      const readyRestaurants = portalRestaurants.filter(function (restaurant) {
        const approved = restaurant.status === 'approved';
        const subActive = restaurant.subscription_status === 'active' || restaurant.subscription_status === 'trial';
        return approved && subActive;
      });

      transferRestaurantEl.innerHTML = readyRestaurants.map(function (restaurant) {
        return '<option value="' + esc(restaurant.id) + '">' + esc(restaurant.name) + '</option>';
      }).join('');

      if (readyRestaurants.some(function (restaurant) { return restaurant.id === previousRestaurantId; })) {
        transferRestaurantEl.value = previousRestaurantId;
      }

      if (!readyRestaurants.length) {
        setStatus(transferStatusEl, 'No approved active restaurant found for transfer code.', 'error');
        return;
      }

      setStatus(transferStatusEl, 'Ready to generate a one-time transfer code.', 'ok');
    }
    function getStaffUsers(restaurant) {
      return Array.isArray(restaurant && restaurant.staffUsers) ? restaurant.staffUsers : [];
    }
    function renderPinResetOptions() {
      const previousRestaurantId = pinResetRestaurantEl.value;
      const restaurantsWithUsers = portalRestaurants.filter(function (restaurant) {
        return getStaffUsers(restaurant).length > 0;
      });

      pinResetRestaurantEl.innerHTML = restaurantsWithUsers.map(function (restaurant) {
        return '<option value="' + esc(restaurant.id) + '">' + esc(restaurant.name) + '</option>';
      }).join('');

      if (restaurantsWithUsers.some(function (restaurant) { return restaurant.id === previousRestaurantId; })) {
        pinResetRestaurantEl.value = previousRestaurantId;
      }

      const selectedRestaurant = restaurantsWithUsers.find(function (restaurant) {
        return restaurant.id === pinResetRestaurantEl.value;
      }) || restaurantsWithUsers[0];
      const staffUsers = getStaffUsers(selectedRestaurant);
      pinResetUserEl.innerHTML = staffUsers.map(function (user) {
        return '<option value="' + esc(user.id) + '">' + esc(user.name) + (user.active === false ? ' (Disabled)' : '') + '</option>';
      }).join('');

      if (!restaurantsWithUsers.length) {
        setStatus(staffPinStatusEl, 'No app users found yet. Run cloud sync once from the desktop app, then refresh portal.', '');
        return;
      }

      setStatus(staffPinStatusEl, 'Choose app user and send a new PIN. Desktop app must sync to apply it.', 'ok');
    }
    async function loadPortal() {
      if (!clientToken) {
        showPortal(false);
        return;
      }

      const result = await clientApi('/api/v1/client/me');
      document.getElementById('accountTitle').textContent = result.account.ownerName || 'Client Account';
      document.getElementById('accountSubtitle').textContent = (result.account.phone || result.account.email || '') + ' / ' + result.account.status;
      portalRestaurants = result.restaurants || [];
      renderPortalDashboard(result.account, portalRestaurants);
      renderPlanCards(portalRestaurants);
      renderRestaurants(portalRestaurants);
      renderTransferOptions();
      renderPinResetOptions();
      initReportsPage(portalRestaurants);
      setStatus(
        portalStatus,
        result.account.recoveryCodeSetAt
          ? 'Portal loaded. Recovery code already generated earlier; generate again if you need a new one.'
          : 'Portal loaded. Generate a recovery code from Account Security.',
        'ok'
      );
      showPortal(true);
    }
    async function login() {
      setStatus(loginStatus, 'Logging in...', '');
      const result = await fetch('/api/v1/client/login', {
        method: 'POST',
        headers: { 'content-type': 'application/json' },
        body: JSON.stringify({
          login: document.getElementById('clientLogin').value,
          password: document.getElementById('clientPassword').value
        })
      }).then(async function (response) {
        const body = await response.json().catch(function () { return {}; });
        if (!response.ok || body.ok === false) throw new Error(body.error || 'Login failed');
        return body;
      });
      clientToken = result.token;
      localStorage.setItem('giClientToken', clientToken);
      setStatus(loginStatus, 'Login success.', 'ok');
      await loadPortal();
    }
    async function resetPassword() {
      setStatus(loginStatus, 'Resetting password...', '');
      const response = await fetch('/api/v1/client/password/reset', {
        method: 'POST',
        headers: { 'content-type': 'application/json' },
        body: JSON.stringify({
          login: document.getElementById('resetLogin').value || document.getElementById('clientLogin').value,
          recoveryCode: document.getElementById('resetRecoveryCode').value,
          newPassword: document.getElementById('resetPassword').value
        })
      });
      const result = await response.json().catch(function () { return {}; });
      if (!response.ok || result.ok === false) throw new Error(result.error || 'Password reset failed');
      setStatus(loginStatus, result.message || 'Password reset successfully.', 'ok');
      forgotPanel.style.display = 'none';
    }
    async function changePassword() {
      setStatus(securityStatusEl, 'Changing password...', '');
      const result = await clientApi('/api/v1/client/password/change', {
        method: 'POST',
        body: JSON.stringify({
          currentPassword: document.getElementById('currentPassword').value,
          newPassword: document.getElementById('newPassword').value
        })
      });
      document.getElementById('currentPassword').value = '';
      document.getElementById('newPassword').value = '';
      setStatus(securityStatusEl, result.message || 'Password changed successfully.', 'ok');
    }
    async function generateRecoveryCode() {
      setStatus(securityStatusEl, 'Generating recovery code...', '');
      const result = await clientApi('/api/v1/client/recovery-code', { method: 'POST' });
      recoveryCodeEl.textContent = result.code;
      setStatus(securityStatusEl, result.message || 'Recovery code generated.', 'ok');
    }
    async function generateTransferCode() {
      const restaurantId = transferRestaurantEl.value;
      if (!restaurantId) {
        setStatus(transferStatusEl, 'Choose restaurant first.', 'error');
        return;
      }

      setStatus(transferStatusEl, 'Generating transfer code...', '');
      const result = await clientApi('/api/v1/client/restaurants/' + encodeURIComponent(restaurantId) + '/pairing-codes', {
        method: 'POST',
        body: JSON.stringify({ deviceName: '${MAIN_APP_DEVICE_NAME}' })
      });
      transferCodeEl.textContent = result.transferCode || result.code || '------';
      const expiresAt = result.pairingCode && result.pairingCode.expires_at
        ? new Date(result.pairingCode.expires_at).toLocaleString()
        : '';
      setStatus(
        transferStatusEl,
        'Use this code in the desktop app Transfer Code field' + (expiresAt ? '. Expires: ' + expiresAt : '') + '.',
        'ok'
      );
    }
    async function resetStaffPin() {
      const restaurantId = pinResetRestaurantEl.value;
      const staffUserId = pinResetUserEl.value;
      const newPin = document.getElementById('pinResetNewPin').value;
      const confirmPin = document.getElementById('pinResetConfirmPin').value;

      if (!restaurantId || !staffUserId) {
        setStatus(staffPinStatusEl, 'Choose restaurant and app user.', 'error');
        return;
      }

      if (!/^\\d{4,8}$/.test(newPin)) {
        setStatus(staffPinStatusEl, 'PIN must be 4 to 8 digits.', 'error');
        return;
      }

      if (newPin !== confirmPin) {
        setStatus(staffPinStatusEl, 'PIN confirmation does not match.', 'error');
        return;
      }

      setStatus(staffPinStatusEl, 'Sending PIN reset...', '');
      const result = await clientApi('/api/v1/client/restaurants/' + encodeURIComponent(restaurantId) + '/staff-pin-reset', {
        method: 'POST',
        body: JSON.stringify({ staffUserId: staffUserId, newPin: newPin })
      });
      document.getElementById('pinResetNewPin').value = '';
      document.getElementById('pinResetConfirmPin').value = '';
      setStatus(staffPinStatusEl, result.message || 'PIN reset sent. Run cloud sync in desktop app.', 'ok');
    }
    document.getElementById('loginBtn').addEventListener('click', function () {
      login().catch(function (error) { setStatus(loginStatus, error.message, 'error'); });
    });
    document.getElementById('forgotBtn').addEventListener('click', function () {
      forgotPanel.style.display = forgotPanel.style.display === 'none' ? '' : 'none';
    });
    document.getElementById('resetPasswordBtn').addEventListener('click', function () {
      resetPassword().catch(function (error) { setStatus(loginStatus, error.message, 'error'); });
    });
    document.getElementById('changePasswordBtn').addEventListener('click', function () {
      changePassword().catch(function (error) { setStatus(securityStatusEl, error.message, 'error'); });
    });
    document.getElementById('generateRecoveryBtn').addEventListener('click', function () {
      generateRecoveryCode().catch(function (error) { setStatus(securityStatusEl, error.message, 'error'); });
    });
    document.getElementById('generateTransferCodeBtn').addEventListener('click', function () {
      generateTransferCode().catch(function (error) { setStatus(transferStatusEl, error.message, 'error'); });
    });
    pinResetRestaurantEl.addEventListener('change', renderPinResetOptions);
    document.getElementById('resetStaffPinBtn').addEventListener('click', function () {
      resetStaffPin().catch(function (error) { setStatus(staffPinStatusEl, error.message, 'error'); });
    });
    document.getElementById('refreshBtn').addEventListener('click', function () {
      loadPortal().catch(function (error) { setStatus(portalStatus, error.message, 'error'); });
    });
    var dashRefreshBtn = document.getElementById('dashRefreshBtn');
    if (dashRefreshBtn) dashRefreshBtn.addEventListener('click', function () {
      loadPortal().catch(function (error) { setStatus(portalStatus, error.message, 'error'); });
    });
    Array.prototype.forEach.call(document.querySelectorAll('.sidebar-link[data-portal]'), function (link) {
      link.addEventListener('click', function () {
        var page = link.getAttribute('data-portal');
        goToPortalPage(page);
        if (page === 'users') initStaffPage();
        if (page === 'reports') runReport();
        if (page === 'devices') loadSignedDevices();
      });
    });

    // ---- Signed Devices page ----
    var signedDeviceListEl = document.getElementById('signedDeviceList');
    var signedDeviceStatusEl = document.getElementById('signedDeviceStatus');
    var devicesRefreshBtn = document.getElementById('devicesRefreshBtn');
    function deviceStatusBadge(device) {
      var cls = device.active ? 'good' : 'bad';
      var label = device.active ? 'Active' : 'Disabled';
      return '<span class="badge ' + cls + '">' + label + '</span>';
    }
    function renderSignedDevices(devices) {
      if (!signedDeviceListEl) return;
      if (!devices || !devices.length) {
        signedDeviceListEl.innerHTML = '<div class="empty-state">No active devices are signed in to this account.</div>';
        return;
      }
      var html = '';
      for (var i = 0; i < devices.length; i++) {
        var d = devices[i];
        var platform = d.platform === 'android' ? 'Android' : 'Windows';
        var seen = d.lastSeenAt ? new Date(d.lastSeenAt).toLocaleString() : 'Never';
        var added = d.createdAt ? new Date(d.createdAt).toLocaleDateString() : '';
        var actions =
          '<button class="btn btn-sm btn-danger" type="button" data-device-action="terminate" data-device-id="' + esc(d.id) + '">Terminate</button>';
        html +=
          '<div class="card panel" style="padding:16px;gap:10px">' +
            '<div class="section-head" style="align-items:center">' +
              '<div class="stack" style="gap:4px">' +
                '<strong style="font-size:16px">' + esc(d.name || 'Device') + '</strong>' +
                '<span style="color:var(--muted);font-size:12px">' + esc(d.restaurantName || '') + '</span>' +
              '</div>' + deviceStatusBadge(d) +
            '</div>' +
            '<div class="info-line"><span>Serial number</span><strong style="font-family:monospace;font-size:12px">' + esc(d.serial || d.id) + '</strong></div>' +
            '<div class="info-line"><span>Platform</span><strong>' + platform + '</strong></div>' +
            '<div class="info-line"><span>Last active</span><strong>' + esc(seen) + '</strong></div>' +
            (added ? '<div class="info-line"><span>Linked on</span><strong>' + esc(added) + '</strong></div>' : '') +
            '<div class="row" style="gap:8px;flex-wrap:wrap;margin-top:4px">' + actions + '</div>' +
          '</div>';
      }
      signedDeviceListEl.innerHTML = html;
      Array.prototype.forEach.call(signedDeviceListEl.querySelectorAll('[data-device-action]'), function (btn) {
        btn.addEventListener('click', function () {
          runDeviceAction(btn.getAttribute('data-device-id'), btn.getAttribute('data-device-action'), btn);
        });
      });
    }
    async function loadSignedDevices() {
      if (!signedDeviceListEl) return;
      signedDeviceListEl.innerHTML = '<div class="empty-state">Loading devices...</div>';
      try {
        var result = await clientApi('/api/v1/client/devices');
        renderSignedDevices(result.devices || []);
        setStatus(signedDeviceStatusEl, 'Showing ' + (result.devices ? result.devices.length : 0) + ' device(s). Disable blocks a device; terminate logs it out immediately.', 'ok');
      } catch (error) {
        signedDeviceListEl.innerHTML = '<div class="empty-state">Could not load devices.</div>';
        setStatus(signedDeviceStatusEl, error.message || 'Could not load devices.', 'error');
      }
    }
    async function runDeviceAction(deviceId, action, btn) {
      if (!deviceId || !action) return;
      if (!window.confirm('Terminate this device? It will be logged out immediately and its session will end.')) return;
      if (btn) { btn.disabled = true; }
      try {
        await clientApi('/api/v1/client/devices/' + encodeURIComponent(deviceId) + '/' + action, { method: 'POST' });
        setStatus(signedDeviceStatusEl, 'Device terminated and logged out.', 'ok');
        await loadSignedDevices();
      } catch (error) {
        if (btn) { btn.disabled = false; }
        setStatus(signedDeviceStatusEl, error.message || 'Action failed.', 'error');
      }
    }
    if (devicesRefreshBtn) devicesRefreshBtn.addEventListener('click', function () { loadSignedDevices(); });
    var devicesDeleteAllBtn = document.getElementById('devicesDeleteAllBtn');
    if (devicesDeleteAllBtn) devicesDeleteAllBtn.addEventListener('click', async function () {
      if (!window.confirm('Delete ALL devices? Every listed device will be logged out and removed immediately.')) return;
      devicesDeleteAllBtn.disabled = true;
      try {
        var result = await clientApi('/api/v1/client/devices/terminate-all', { method: 'POST' });
        setStatus(signedDeviceStatusEl, (result.terminated || 0) + ' device(s) terminated and removed.', 'ok');
        await loadSignedDevices();
      } catch (error) {
        setStatus(signedDeviceStatusEl, error.message || 'Could not delete devices.', 'error');
      } finally {
        devicesDeleteAllBtn.disabled = false;
      }
    });

    // ---- Reports page ----
    var reportState = { options: null, initialised: false };
    function reportEl(id) { return document.getElementById(id); }
    function initReportsPage(restaurants) {
      var select = reportEl('reportRestaurant');
      if (!select) return;
      var approved = restaurants.filter(function (r) { return r.status === 'approved'; });
      var prev = select.value;
      select.innerHTML = approved.map(function (r) {
        return '<option value="' + esc(r.id) + '">' + esc(r.name) + '</option>';
      }).join('');
      if (approved.some(function (r) { return r.id === prev; })) select.value = prev;
      if (reportState.initialised) return;
      reportState.initialised = true;
      // Default range: this month.
      var now = new Date();
      var first = new Date(now.getFullYear(), now.getMonth(), 1);
      reportEl('reportFrom').value = first.toISOString().slice(0, 10);
      reportEl('reportTo').value = now.toISOString().slice(0, 10);
      reportEl('reportRunBtn').addEventListener('click', function () { runReport(); });
      reportEl('reportRestaurant').addEventListener('change', function () { runReport(); });
      reportEl('reportCategoryView').addEventListener('change', renderReport);
      ['reportOrderType', 'reportPayment', 'reportCategory'].forEach(function (id) {
        reportEl(id).addEventListener('change', function () { runReport(); });
      });
      Array.prototype.forEach.call(document.querySelectorAll('#reportQuickRanges .chip'), function (chip) {
        chip.addEventListener('click', function () {
          applyQuickRange(chip.getAttribute('data-range'));
          runReport();
        });
      });
    }
    function applyQuickRange(range) {
      var now = new Date();
      var to = new Date(now);
      var from = new Date(now);
      if (range === 'today') { /* from = today */ }
      else if (range === '7') { from.setDate(now.getDate() - 6); }
      else if (range === '30') { from.setDate(now.getDate() - 29); }
      else if (range === 'month') { from = new Date(now.getFullYear(), now.getMonth(), 1); }
      reportEl('reportFrom').value = from.toISOString().slice(0, 10);
      reportEl('reportTo').value = to.toISOString().slice(0, 10);
    }
    function fillReportOptionSelect(id, values, keepValue) {
      var select = reportEl(id);
      if (!select) return;
      var current = keepValue ? select.value : 'all';
      var head = select.querySelector('option[value="all"]');
      var allLabel = head ? head.textContent : 'All';
      select.innerHTML = '<option value="all">' + esc(allLabel) + '</option>' +
        values.map(function (v) { return '<option value="' + esc(v) + '">' + esc(v) + '</option>'; }).join('');
      if (values.indexOf(current) >= 0) select.value = current;
    }
    async function runReport() {
      var select = reportEl('reportRestaurant');
      if (!select || !select.value) {
        reportEl('reportBody').innerHTML = '<div class="empty-state">No approved business available for reports.</div>';
        return;
      }
      var params = new URLSearchParams();
      params.set('from', reportEl('reportFrom').value || '');
      params.set('to', reportEl('reportTo').value || '');
      params.set('orderType', reportEl('reportOrderType').value || 'all');
      params.set('payment', reportEl('reportPayment').value || 'all');
      params.set('category', reportEl('reportCategory').value || 'all');
      setStatus(reportEl('reportStatus'), 'Loading report...', '');
      try {
        var result = await clientApi('/api/v1/client/restaurants/' + encodeURIComponent(select.value) + '/reports?' + params.toString());
        reportState.data = result;
        if (result.options) {
          fillReportOptionSelect('reportOrderType', result.options.types || [], true);
          fillReportOptionSelect('reportPayment', result.options.payments || [], true);
          fillReportOptionSelect('reportCategory', result.options.categories || [], true);
        }
        renderReport();
        var syncedTxt = result.syncedAt ? 'Synced ' + new Date(result.syncedAt).toLocaleString() : 'Waiting for first cloud sync from the POS.';
        setStatus(reportEl('reportStatus'), result.hasData ? syncedTxt : 'No sales synced yet. Run a cloud sync from the POS.', result.hasData ? 'ok' : '');
      } catch (error) {
        setStatus(reportEl('reportStatus'), error.message || 'Could not load report.', 'error');
      }
    }
    function money(n) { return '\\u20b9' + (Math.round((Number(n) || 0) * 100) / 100).toLocaleString('en-IN', { minimumFractionDigits: 2, maximumFractionDigits: 2 }); }
    function renderReportBars(rows, labelKey, valueKey, unit) {
      if (!rows || !rows.length) return '<div class="empty-state">No data for this filter.</div>';
      var max = rows.reduce(function (m, r) { return Math.max(m, Number(r[valueKey]) || 0); }, 0) || 1;
      return '<div class="analytics-bars">' + rows.map(function (r) {
        var val = Number(r[valueKey]) || 0;
        var pct = Math.max(3, Math.round((val / max) * 100));
        var valText = unit === 'money' ? money(val) : String(val);
        return '<div class="analytics-bar"><div class="analytics-bar-head"><span>' + esc(r[labelKey]) + '</span><span>' + valText + '</span></div>' +
          '<div class="analytics-track"><div class="analytics-fill" style="width:' + pct + '%"></div></div></div>';
      }).join('') + '</div>';
    }
    function renderReport() {
      var data = reportState.data;
      var bodyEl = reportEl('reportBody');
      if (!data) { bodyEl.innerHTML = '<div class="empty-state">Apply filters to view reports.</div>'; return; }
      var view = reportEl('reportCategoryView').value;
      var s = data.summary || {};
      reportEl('reportKpis').innerHTML =
        '<div class="kpi kpi-green"><div class="kpi-top"><span class="kpi-label">Total Sales</span><span class="kpi-icon">S</span></div><div class="kpi-value" style="font-size:24px">' + money(s.totalSales) + '</div><span class="kpi-hint">Settled bills</span></div>' +
        '<div class="kpi"><div class="kpi-top"><span class="kpi-label">Bills</span><span class="kpi-icon">B</span></div><div class="kpi-value">' + (s.billCount || 0) + '</div><span class="kpi-hint">Paid count</span></div>' +
        '<div class="kpi kpi-slate"><div class="kpi-top"><span class="kpi-label">Avg. Bill</span><span class="kpi-icon">A</span></div><div class="kpi-value" style="font-size:24px">' + money(s.avgBill) + '</div><span class="kpi-hint">Per bill</span></div>' +
        '<div class="kpi kpi-amber"><div class="kpi-top"><span class="kpi-label">Items Sold</span><span class="kpi-icon">I</span></div><div class="kpi-value">' + (s.itemsSold || 0) + '</div><span class="kpi-hint">Units</span></div>';

      var titles = {
        overview: ['Overview', 'Sales at a glance'],
        payment: ['By Payment Method', 'Collected by each method'],
        type: ['By Order Type', 'Dine-in, delivery, takeaway'],
        category: ['By Category', 'Sales grouped by menu category'],
        items: ['Top Items', 'Best-selling items by value'],
        orders: ['Bill List', 'Individual settled bills']
      };
      var t = titles[view] || titles.overview;
      reportEl('reportViewEyebrow').textContent = t[0];
      reportEl('reportViewTitle').textContent = t[0];
      reportEl('reportViewSubtitle').textContent = t[1];

      if (!data.hasData) { bodyEl.innerHTML = '<div class="empty-state">No sales data synced yet.</div>'; return; }

      if (view === 'payment') { bodyEl.innerHTML = renderReportBars(data.byPayment, 'method', 'amount', 'money'); return; }
      if (view === 'type') { bodyEl.innerHTML = renderReportBars(data.byType, 'type', 'amount', 'money'); return; }
      if (view === 'category') { bodyEl.innerHTML = renderReportBars(data.byCategory, 'category', 'amount', 'money'); return; }
      if (view === 'items') {
        bodyEl.innerHTML = '<table><thead><tr><th>Item</th><th style="text-align:right">Qty</th><th style="text-align:right">Amount</th></tr></thead><tbody>' +
          (data.topItems || []).map(function (r) { return '<tr><td>' + esc(r.name) + '</td><td style="text-align:right">' + r.qty + '</td><td style="text-align:right">' + money(r.amount) + '</td></tr>'; }).join('') +
          '</tbody></table>';
        return;
      }
      if (view === 'orders') {
        if (!data.orders || !data.orders.length) { bodyEl.innerHTML = '<div class="empty-state">No bills for this filter.</div>'; return; }
        bodyEl.innerHTML = '<table><thead><tr><th>Bill</th><th>Date</th><th>Type</th><th>Payment</th><th style="text-align:right">Items</th><th style="text-align:right">Total</th></tr></thead><tbody>' +
          data.orders.map(function (o) {
            return '<tr><td>#' + esc(o.billNo) + '</td><td>' + esc(new Date(o.createdAt).toLocaleString()) + '</td><td>' + esc(o.orderType) + (o.table ? ' / ' + esc(o.table) : '') + '</td><td>' + esc(o.payment) + '</td><td style="text-align:right">' + o.items + '</td><td style="text-align:right">' + money(o.total) + '</td></tr>';
          }).join('') +
          '</tbody></table>';
        return;
      }
      // Overview: payment + type side by side
      bodyEl.innerHTML =
        '<div class="report-overview-grid">' +
          '<div><h4 class="report-sub">Payment Methods</h4>' + renderReportBars(data.byPayment, 'method', 'amount', 'money') + '</div>' +
          '<div><h4 class="report-sub">Order Types</h4>' + renderReportBars(data.byType, 'type', 'amount', 'money') + '</div>' +
        '</div>' +
        '<h4 class="report-sub" style="margin-top:18px">Top Categories</h4>' + renderReportBars((data.byCategory || []).slice(0, 6), 'category', 'amount', 'money');
    }

    // ---- Users & Staff management ----
    var staffRestaurantEl = document.getElementById('staffRestaurant');
    var staffListEl = document.getElementById('staffList');
    var staffCapacityEl = document.getElementById('staffCapacity');
    var staffFormStatusEl = document.getElementById('staffFormStatus');
    var staffState = { maxUsers: null, activeCount: 0, staff: [], editId: null, tableGroups: null, tablesSynced: false };
    var STAFF_PERMISSIONS = ['Billing','KOT / Kitchen Orders','Tables & Dining','Menu Management','Customers','Dues & Credit','Reports','Discounts','Refunds & Void','Reprint Bill','Day Close','Settings','Inventory','Printer Setup'];
    var STAFF_TABLES_FALLBACK = ['Counter','Table 1','Table 2','Table 3','Table 4','Parcel / Takeaway','Delivery'];

    function fillStaffRestaurants() {
      if (!staffRestaurantEl) return;
      var prev = staffRestaurantEl.value;
      var approved = portalRestaurants.filter(function (r) { return r.status === 'approved'; });
      staffRestaurantEl.innerHTML = approved.map(function (r) {
        return '<option value="' + esc(r.id) + '">' + esc(r.name) + '</option>';
      }).join('');
      if (approved.some(function (r) { return r.id === prev; })) staffRestaurantEl.value = prev;
    }
    function renderStaffCapacity() {
      if (!staffCapacityEl) return;
      if (!staffRestaurantEl || !staffRestaurantEl.value) { staffCapacityEl.textContent = 'Select a business to manage staff.'; return; }
      var limit = staffState.maxUsers == null ? 'Unlimited' : staffState.maxUsers;
      var full = staffState.maxUsers != null && staffState.activeCount >= staffState.maxUsers;
      staffCapacityEl.innerHTML = '<strong>' + staffState.activeCount + ' / ' + limit + '</strong> active staff' + (full ? ' <span class="badge warn">Limit reached</span>' : '');
    }
    function staffInitials(name) {
      var parts = String(name || '').trim().split(/\\s+/).filter(Boolean);
      if (!parts.length) return 'U';
      return (parts[0][0] + (parts.length > 1 ? parts[parts.length - 1][0] : '')).toUpperCase();
    }
    function renderStaffList(staff) {
      if (!staffListEl) return;
      if (!staff.length) { staffListEl.innerHTML = '<div class="empty-state">No staff accounts. Create staff in the GI POS Windows software under User Manage.</div>'; return; }
      staffListEl.innerHTML = staff.map(function (u) {
        var perms = (u.permissions || []).length;
        var tables = (u.tableAccess || []).length;
        var tags = '<span class="staff-chip">' + perms + ' permission' + (perms === 1 ? '' : 's') + '</span>' +
          '<span class="staff-chip">' + (tables ? tables + ' table' + (tables === 1 ? '' : 's') : 'No tables') + '</span>' +
          (u.staffCode ? '<span class="staff-chip muted">' + esc(u.staffCode) + '</span>' : '');
        return '<div class="staff-card" data-staff="' + esc(u.id) + '">' +
          '<div class="staff-card-main">' +
            '<div class="staff-avatar">' + esc(staffInitials(u.name)) + '</div>' +
            '<div class="staff-info"><strong>' + esc(u.name) + '<span class="role-pill">' + esc(u.role || 'Staff') + '</span>' + (u.active ? '' : ' <span class="badge">Disabled</span>') + '</strong>' +
              '<span class="staff-meta">' + (u.mobile ? esc(u.mobile) : 'No mobile') + '</span>' +
              '<div class="staff-tags">' + tags + '</div></div>' +
          '</div>' +
          '<div class="staff-actions">' +
            '<button class="btn btn-sm" data-staff-view="' + esc(u.id) + '">View Details</button>' +
          '</div>' +
        '</div>';
      }).join('');
      staffListEl.querySelectorAll('[data-staff-view]').forEach(function (btn) {
        btn.addEventListener('click', function () { openStaffDetails(btn.getAttribute('data-staff-view')); });
      });
      staffListEl.querySelectorAll('[data-staff-edit]').forEach(function (btn) {
        btn.addEventListener('click', function () { openStaffModal(btn.getAttribute('data-staff-edit')); });
      });
      staffListEl.querySelectorAll('[data-staff-toggle]').forEach(function (btn) {
        btn.addEventListener('click', function () {
          updateStaff(btn.getAttribute('data-staff-toggle'), { active: btn.getAttribute('data-active') !== '1' });
        });
      });
      staffListEl.querySelectorAll('[data-staff-del]').forEach(function (btn) {
        btn.addEventListener('click', function () {
          if (!confirm('Remove this staff account?')) return;
          deleteStaff(btn.getAttribute('data-staff-del'));
        });
      });
    }
    async function loadStaff() {
      if (!staffRestaurantEl || !staffRestaurantEl.value) { renderStaffCapacity(); if (staffListEl) staffListEl.innerHTML = '<div class="empty-state">Select a business to view staff.</div>'; return; }
      var result = await clientApi('/api/v1/client/restaurants/' + encodeURIComponent(staffRestaurantEl.value) + '/staff');
      staffState.maxUsers = result.maxUsers;
      staffState.activeCount = result.activeCount || 0;
      staffState.staff = result.staff || [];
      renderStaffCapacity();
      renderStaffList(staffState.staff);
    }
    async function updateStaff(staffId, patch) {
      try {
        await clientApi('/api/v1/client/restaurants/' + encodeURIComponent(staffRestaurantEl.value) + '/staff/' + encodeURIComponent(staffId), { method: 'POST', body: JSON.stringify(patch) });
        setStatus(staffFormStatusEl, 'Staff updated.', 'ok');
        await loadStaff();
      } catch (error) { setStatus(staffFormStatusEl, error.message, 'error'); }
    }
    async function deleteStaff(staffId) {
      try {
        await clientApi('/api/v1/client/restaurants/' + encodeURIComponent(staffRestaurantEl.value) + '/staff/' + encodeURIComponent(staffId), { method: 'DELETE' });
        setStatus(staffFormStatusEl, 'Staff removed.', 'ok');
        await loadStaff();
      } catch (error) { setStatus(staffFormStatusEl, error.message, 'error'); }
    }

    // ---- Staff modal (form) ----
    var staffModal = document.getElementById('staffModal');
    var staffModalStatus = document.getElementById('staffModalStatus');
    var staffRoleSelect = document.getElementById('staffRoleSelect');
    var staffCustomRoleWrap = document.getElementById('staffCustomRoleWrap');
    var staffPermGrid = document.getElementById('staffPermGrid');
    var staffTableGrid = document.getElementById('staffTableGrid');

    function buildCheckGrid(container, items, selected) {
      var sel = selected || [];
      container.innerHTML = items.map(function (label) {
        var checked = sel.indexOf(label) >= 0;
        return '<label class="check-item' + (checked ? ' checked' : '') + '"><input type="checkbox" value="' + esc(label) + '"' + (checked ? ' checked' : '') + '><span>' + esc(label) + '</span></label>';
      }).join('');
      container.querySelectorAll('input[type=checkbox]').forEach(function (cb) {
        cb.addEventListener('change', function () { cb.closest('.check-item').classList.toggle('checked', cb.checked); });
      });
    }
    function readCheckGrid(container) {
      return Array.prototype.map.call(container.querySelectorAll('input:checked'), function (cb) { return cb.value; });
    }
    // Render the Table Access grid grouped by the business's real areas (synced from software).
    function buildTableGrid(groups, selected, synced) {
      var sel = selected || [];
      if (!groups || !groups.length) {
        var fb = STAFF_TABLES_FALLBACK.map(function (label) {
          var checked = sel.indexOf(label) >= 0;
          return '<label class="check-item' + (checked ? ' checked' : '') + '"><input type="checkbox" value="' + esc(label) + '"' + (checked ? ' checked' : '') + '><span>' + esc(label) + '</span></label>';
        }).join('');
        staffTableGrid.innerHTML = '<div class="table-sync-note">' + (synced === false ? 'No tables have synced from your POS software yet. Showing default areas - run cloud sync in the software to load your real tables.' : 'Showing default areas.') + '</div><div class="check-grid" style="margin-top:10px">' + fb + '</div>';
      } else {
        staffTableGrid.innerHTML = '<div class="table-sync-note ok">Live from your POS software - ' + groups.reduce(function (n, g) { return n + g.tables.length; }, 0) + ' tables across ' + groups.length + ' area' + (groups.length === 1 ? '' : 's') + '.</div>' +
          groups.map(function (g) {
            var items = g.tables.map(function (label) {
              var checked = sel.indexOf(label) >= 0;
              return '<label class="check-item' + (checked ? ' checked' : '') + '"><input type="checkbox" value="' + esc(label) + '"' + (checked ? ' checked' : '') + '><span>' + esc(label) + '</span></label>';
            }).join('');
            return '<div class="table-area-group"><div class="table-area-label">' + esc(g.label) + '</div><div class="check-grid">' + items + '</div></div>';
          }).join('');
      }
      staffTableGrid.querySelectorAll('input[type=checkbox]').forEach(function (cb) {
        cb.addEventListener('change', function () { cb.closest('.check-item').classList.toggle('checked', cb.checked); });
      });
    }
    async function loadTablesIntoModal(selected) {
      staffTableGrid.innerHTML = '<div class="table-sync-note">Loading your tables...</div>';
      try {
        var res = await clientApi('/api/v1/client/restaurants/' + encodeURIComponent(staffRestaurantEl.value) + '/tables');
        staffState.tableGroups = res.groups || [];
        staffState.tablesSynced = !!res.synced;
      } catch (e) {
        staffState.tableGroups = [];
        staffState.tablesSynced = false;
      }
      buildTableGrid(staffState.tableGroups, selected, staffState.tablesSynced);
    }
    function setModalStep(step) {
      Array.prototype.forEach.call(document.querySelectorAll('.pmodal-step'), function (b) { b.classList.toggle('active', b.getAttribute('data-step') === step); });
      Array.prototype.forEach.call(document.querySelectorAll('.pmodal-section'), function (s) { s.classList.toggle('active', s.getAttribute('data-step-view') === step); });
    }
    function applyRoleToSelect(role) {
      var known = Array.prototype.map.call(staffRoleSelect.options, function (o) { return o.value; });
      if (role && known.indexOf(role) >= 0) { staffRoleSelect.value = role; staffCustomRoleWrap.style.display = 'none'; }
      else if (role) { staffRoleSelect.value = '__custom'; staffCustomRoleWrap.style.display = ''; document.getElementById('staffCustomRole').value = role; }
      else { staffRoleSelect.value = 'Cashier'; staffCustomRoleWrap.style.display = 'none'; }
    }
    function openStaffModal(staffId) {
      if (!staffRestaurantEl || !staffRestaurantEl.value) { setStatus(staffFormStatusEl, 'Select a business first.', 'error'); return; }
      staffState.editId = staffId || null;
      var existing = staffId ? staffState.staff.filter(function (s) { return s.id === staffId; })[0] : null;
      document.getElementById('staffModalTitle').textContent = existing ? 'Edit Staff Member' : 'Add Staff Member';
      document.getElementById('staffModalSave').textContent = existing ? 'Save Changes' : 'Create Staff';
      document.getElementById('staffName').value = existing ? existing.name : '';
      document.getElementById('staffMobile').value = existing ? (existing.mobile || '') : '';
      document.getElementById('staffPassword').value = '';
      document.getElementById('staffPassword').placeholder = existing ? 'Leave blank to keep current password' : 'Optional login password, min 4 characters';
      applyRoleToSelect(existing ? existing.role : 'Cashier');
      buildCheckGrid(staffPermGrid, STAFF_PERMISSIONS, existing ? existing.permissions : []);
      var selectedTables = existing ? existing.tableAccess : [];
      // Show cached tables instantly if available, then refresh live from the software.
      buildTableGrid(staffState.tableGroups, selectedTables, staffState.tablesSynced);
      loadTablesIntoModal(selectedTables);
      setModalStep('basic');
      setStatus(staffModalStatus, existing ? 'Update details and save.' : 'Fill in the staff details across the sections.', '');
      staffModal.classList.add('open');
      staffModal.setAttribute('aria-hidden', 'false');
    }
    function closeStaffModal() { staffModal.classList.remove('open'); staffModal.setAttribute('aria-hidden', 'true'); }
    function resolveRole() {
      if (staffRoleSelect.value === '__custom') return (document.getElementById('staffCustomRole').value.trim() || 'Staff');
      return staffRoleSelect.value;
    }
    async function saveStaffModal() {
      var name = document.getElementById('staffName').value.trim();
      var mobile = document.getElementById('staffMobile').value.trim();
      var role = resolveRole();
      var password = document.getElementById('staffPassword').value;
      var permissions = readCheckGrid(staffPermGrid);
      var tableAccess = readCheckGrid(staffTableGrid);
      if (!name) { setStatus(staffModalStatus, 'Staff name is required (Basic Info).', 'error'); setModalStep('basic'); return; }
      if (password && password.length < 4) { setStatus(staffModalStatus, 'Password must be at least 4 characters.', 'error'); setModalStep('basic'); return; }
      var payload = { name: name, mobile: mobile, role: role, permissions: permissions, tableAccess: tableAccess };
      if (password) payload.password = password;
      setStatus(staffModalStatus, 'Saving...', '');
      document.getElementById('staffModalSave').disabled = true;
      try {
        var base = '/api/v1/client/restaurants/' + encodeURIComponent(staffRestaurantEl.value) + '/staff';
        if (staffState.editId) await clientApi(base + '/' + encodeURIComponent(staffState.editId), { method: 'POST', body: JSON.stringify(payload) });
        else await clientApi(base, { method: 'POST', body: JSON.stringify(payload) });
        closeStaffModal();
        setStatus(staffFormStatusEl, staffState.editId ? 'Staff account updated.' : 'Staff account created with QR & barcode.', 'ok');
        await loadStaff();
      } catch (error) { setStatus(staffModalStatus, error.message, 'error'); }
      finally { document.getElementById('staffModalSave').disabled = false; }
    }
    Array.prototype.forEach.call(document.querySelectorAll('.pmodal-step'), function (b) {
      b.addEventListener('click', function () { setModalStep(b.getAttribute('data-step')); });
    });
    var openAddStaffBtn = document.getElementById('openAddStaffBtn');
    if (openAddStaffBtn) openAddStaffBtn.addEventListener('click', function () { openStaffModal(null); });
    document.getElementById('staffModalClose').addEventListener('click', closeStaffModal);
    document.getElementById('staffModalCancel').addEventListener('click', closeStaffModal);
    document.getElementById('staffModalSave').addEventListener('click', function () { saveStaffModal(); });
    staffModal.addEventListener('click', function (e) { if (e.target === staffModal) closeStaffModal(); });
    staffRoleSelect.addEventListener('change', function () { staffCustomRoleWrap.style.display = staffRoleSelect.value === '__custom' ? '' : 'none'; });
    document.getElementById('permAll').addEventListener('click', function () { staffPermGrid.querySelectorAll('input').forEach(function (cb) { cb.checked = true; cb.closest('.check-item').classList.add('checked'); }); });
    document.getElementById('permNone').addEventListener('click', function () { staffPermGrid.querySelectorAll('input').forEach(function (cb) { cb.checked = false; cb.closest('.check-item').classList.remove('checked'); }); });
    document.getElementById('tableAll').addEventListener('click', function () { staffTableGrid.querySelectorAll('input').forEach(function (cb) { cb.checked = true; cb.closest('.check-item').classList.add('checked'); }); });
    document.getElementById('tableNone').addEventListener('click', function () { staffTableGrid.querySelectorAll('input').forEach(function (cb) { cb.checked = false; cb.closest('.check-item').classList.remove('checked'); }); });

    // ---- Staff details modal (QR + barcode) ----
    var staffDetailsModal = document.getElementById('staffDetailsModal');
    function openStaffDetails(staffId) {
      var u = staffState.staff.filter(function (s) { return s.id === staffId; })[0];
      if (!u) return;
      document.getElementById('staffDetailsName').textContent = u.name;
      var perms = (u.permissions || []).length ? (u.permissions || []).map(function (p) { return '<span class="staff-chip">' + esc(p) + '</span>'; }).join('') : '<span style="color:var(--muted)">None</span>';
      var tables = (u.tableAccess || []).length ? (u.tableAccess || []).map(function (t) { return '<span class="staff-chip">' + esc(t) + '</span>'; }).join('') : '<span style="color:var(--muted)">None</span>';
      var code = u.staffCode || '';
      document.getElementById('staffDetailsBody').innerHTML =
        '<div class="staff-detail-grid">' +
          '<div class="staff-detail-row"><span>Role</span><strong>' + esc(u.role || 'Staff') + '</strong></div>' +
          '<div class="staff-detail-row"><span>Mobile</span><strong>' + esc(u.mobile || '-') + '</strong></div>' +
          '<div class="staff-detail-row"><span>Status</span><strong>' + (u.active ? 'Active' : 'Disabled') + '</strong></div>' +
          '<div class="staff-detail-row"><span>Login password</span><strong>' + (u.hasPassword ? 'Set' : 'Not set') + '</strong></div>' +
          '<div><div class="staff-code-label" style="margin-bottom:6px">Permissions</div><div class="staff-tags">' + perms + '</div></div>' +
          '<div><div class="staff-code-label" style="margin-bottom:6px">Table Access</div><div class="staff-tags">' + tables + '</div></div>' +
          '<div class="staff-codes">' +
            '<div class="staff-code-label">Staff QR Code</div>' +
            '<div class="staff-qr">' + (u.qrSvg || 'No code') + '</div>' +
            '<div class="staff-code-label">Barcode</div>' +
            '<div class="staff-barcode">' + (code ? renderBarcodeSvg(code) : '') + '</div>' +
            '<div class="staff-code-text">' + esc(code) + '</div>' +
          '</div>' +
        '</div>';
      var staffDetailsEditBtn = document.getElementById('staffDetailsEdit');
      if (staffDetailsEditBtn) staffDetailsEditBtn.onclick = function () { closeStaffDetails(); openStaffModal(staffId); };
      staffDetailsModal.classList.add('open');
      staffDetailsModal.setAttribute('aria-hidden', 'false');
    }
    function closeStaffDetails() { staffDetailsModal.classList.remove('open'); staffDetailsModal.setAttribute('aria-hidden', 'true'); }
    document.getElementById('staffDetailsClose').addEventListener('click', closeStaffDetails);
    document.getElementById('staffDetailsCloseBtn').addEventListener('click', closeStaffDetails);
    staffDetailsModal.addEventListener('click', function (e) { if (e.target === staffDetailsModal) closeStaffDetails(); });
    document.addEventListener('keydown', function (e) { if (e.key === 'Escape') { closeStaffModal(); closeStaffDetails(); } });

    // ---- Code128-B barcode as SVG (self-contained) ----
    function renderBarcodeSvg(text) {
      var PATTERNS = ['212222','222122','222221','121223','121322','131222','122213','122312','132212','221213','221312','231212','112232','122132','122231','113222','123122','123221','223211','221132','221231','213212','223112','312131','311222','321122','321221','312212','322112','322211','212123','212321','232121','111323','131123','131321','112313','132113','132311','211313','231113','231311','112133','112331','132131','113123','113321','133121','313121','211331','231131','213113','213311','213131','311123','311321','331121','312113','312311','332111','314111','221411','431111','111224','111422','121124','121421','141122','141221','112214','112412','122114','122411','142112','142211','241211','221114','413111','241112','134111','111242','121142','121241','114212','124112','124211','411212','421112','421211','212141','214121','412121','111143','111341','131141','114113','114311','411113','411311','113141','114131','311141','411131','211412','211214','211232','2331112'];
      var START_B = 104, STOP = 106;
      var values = [START_B];
      var sum = START_B;
      for (var i = 0; i < text.length; i++) {
        var v = text.charCodeAt(i) - 32;
        if (v < 0 || v > 94) v = 0;
        values.push(v);
        sum += v * (i + 1);
      }
      values.push(sum % 103);
      values.push(STOP);
      var bars = values.map(function (v) { return PATTERNS[v]; }).join('');
      var x = 0, rects = '', dark = true, total = 0, j;
      for (j = 0; j < bars.length; j++) total += parseInt(bars[j], 10);
      var h = 64;
      for (j = 0; j < bars.length; j++) {
        var w = parseInt(bars[j], 10);
        if (dark) rects += '<rect x="' + x + '" y="0" width="' + w + '" height="' + h + '"/>';
        x += w; dark = !dark;
      }
      return '<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 ' + total + ' ' + h + '" preserveAspectRatio="none" fill="#0b1220">' + rects + '</svg>';
    }

    if (staffRestaurantEl) staffRestaurantEl.addEventListener('change', function () { staffState.tableGroups = null; staffState.tablesSynced = false; loadStaff().catch(function (error) { setStatus(staffFormStatusEl, error.message, 'error'); }); });
    var staffPageInited = false;
    function initStaffPage() {
      fillStaffRestaurants();
      if (!staffPageInited) staffPageInited = true;
      loadStaff().catch(function (error) { setStatus(staffFormStatusEl, error.message, 'error'); });
    }

    function portalSignOut() {
      clientToken = '';
      localStorage.removeItem('giClientToken');
      showPortal(false);
      goToPortalPage('dashboard');
    }
    logoutBtn.addEventListener('click', portalSignOut);
    var logoutBtn2 = document.getElementById('logoutBtn2');
    if (logoutBtn2) logoutBtn2.addEventListener('click', portalSignOut);
    loadPortal().catch(function () {
      clientToken = '';
      localStorage.removeItem('giClientToken');
      showPortal(false);
    });
  </script>
</body>
</html>`;

const ADMIN_HTML = `<!doctype html>
<html>
<head>
  <meta charset="utf-8">
  <meta name="viewport" content="width=device-width, initial-scale=1">
  <title>GI POS - Cloud Admin</title>
  <link rel="icon" href="${SITE_LOGO_URL}">
  <style>${BASE_STYLES}</style>
</head>
<body>
  <!-- LOGIN SCREEN -->
  <div class="admin-login-layer" id="adminLoginLayer">
    <div class="admin-login-card">
      <div class="admin-login-brand">
        <div class="stack">
          <a class="brand-lockup" href="/" style="text-decoration:none;color:inherit"><img class="brand-logo-img sidebar-logo-img" src="${SITE_LOGO_URL}" alt="GI POS" onerror="this.style.display='none';this.nextElementSibling.style.display='grid'"><div class="brand-mark" style="display:none">GI</div><div><h1 style="color:#fff">GI Cloud Control</h1><p>Enterprise admin panel</p></div></a>
          <span class="eyebrow" style="color:#fff">Secure Access</span>
          <h2>Manage clients, plans, and releases from one console.</h2>
          <p>Approve restaurants, control Windows and Android subscriptions, and publish desktop updates.</p>
        </div>
        <div class="admin-login-points">
          <div>Client overview and analytics</div>
          <div>Per-module dedicated pages</div>
          <div>Windows app release publishing</div>
        </div>
      </div>
      <div class="admin-login-form">
        <div class="section-title"><span class="eyebrow">Admin Login</span><h2>Enter Control Panel</h2><p>Use your GI cloud admin token to continue.</p></div>
        <label>Admin Token <input id="adminToken" type="password" placeholder="GI_CLOUD_ADMIN_TOKEN" autocomplete="off"></label>
        <label style="flex-direction:row;align-items:center;gap:8px;display:flex;font-weight:800">
          <input type="checkbox" id="rememberToken" style="width:auto;height:auto" checked> Remember on this device
        </label>
        <button class="primary" id="loadBtn">Sign In</button>
        <button id="saveTokenBtn" style="display:none">Save Token</button>
        <div class="status" id="status">Enter admin token to sign in.</div>
        <div class="row" style="justify-content:center"><a class="nav-link" href="/portal">Client Portal</a><a class="nav-link" href="/signup">Signup Page</a></div>
      </div>
    </div>
  </div>

  <!-- APP SHELL -->
  <div class="app-shell" id="adminAppShell" style="display:none">
    <aside class="app-sidebar">
      <a class="brand-lockup" href="/" style="text-decoration:none;color:inherit"><img class="brand-logo-img sidebar-logo-img" src="${SITE_LOGO_URL}" alt="GI POS" onerror="this.style.display='none';this.nextElementSibling.style.display='grid'"><div class="brand-mark" style="display:none">GI</div><div><h1>GI Cloud Admin</h1><p>Control panel</p></div></a>
      <nav class="sidebar-nav">
        <div class="sidebar-section-label">Overview</div>
        <button class="sidebar-link active" type="button" data-page="overview"><span class="sidebar-ic">O</span> Client Overview</button>
        <button class="sidebar-link" type="button" data-page="clients"><span class="sidebar-ic">C</span> Clients</button>
        <button class="sidebar-link" type="button" data-page="messages"><span class="sidebar-ic">M</span> Messages <span class="sidebar-count" id="messagesBadge" style="display:none"></span></button>
        <button class="sidebar-link" type="button" data-page="plans"><span class="sidebar-ic">P</span> Plans</button>
        <div class="sidebar-section-label">Operations</div>
        <button class="sidebar-link" type="button" data-page="updates"><span class="sidebar-ic">U</span> App Updates</button>
        <button class="sidebar-link" type="button" data-page="whatsapp"><span class="sidebar-ic">W</span> WhatsApp</button>
        <button class="sidebar-link" type="button" data-page="origin"><span class="sidebar-ic">S</span> Origin Settings</button>
      </nav>
      <div class="sidebar-foot">
        <span class="sidebar-badge" id="sidebarClientCount">0 clients loaded</span>
        <a class="nav-link" href="/portal">Client Portal</a>
        <button id="adminLogoutBtn">Sign Out</button>
      </div>
    </aside>

    <main class="app-main"><div class="app-main-inner">
      <!-- OVERVIEW -->
      <section class="admin-page-view active" data-page-view="overview">
        <div class="app-topbar">
          <div class="section-title"><span class="eyebrow">Dashboard</span><h2>Client Overview</h2><p>Business health, plan mix, and recent activity at a glance.</p></div>
          <button class="primary" id="overviewRefreshBtn">Refresh Data</button>
        </div>
        <div class="kpi-grid" id="adminSummary">
          <div class="kpi"><span class="kpi-label">Total Clients</span><div class="kpi-value">0</div></div>
          <div class="kpi kpi-green"><span class="kpi-label">Active</span><div class="kpi-value">0</div></div>
          <div class="kpi kpi-amber"><span class="kpi-label">Pending</span><div class="kpi-value">0</div></div>
          <div class="kpi kpi-red"><span class="kpi-label">Suspended</span><div class="kpi-value">0</div></div>
        </div>
        <div class="dash-grid" id="adminAnalytics"></div>
      </section>

      <!-- CLIENTS -->
      <section class="admin-page-view" data-page-view="clients">
        <div class="app-topbar">
          <div class="section-title"><span class="eyebrow">Clients</span><h2>Restaurants</h2><p>Manage plan, expiry, user capacity, access, and renewal.</p></div>
        </div>
        <section class="card panel">
          <div class="admin-client-tools">
            <label>Search Client <input id="clientSearch" placeholder="Business, owner, phone, email"></label>
            <label>Status
              <select id="clientStatusFilter">
                <option value="all">All</option>
                <option value="active">Active</option>
                <option value="pending">Pending</option>
                <option value="expired">Expired</option>
                <option value="suspended">Suspended</option>
              </select>
            </label>
          </div>
          <div class="restaurant-list" id="restaurantRows"></div>
        </section>
      </section>

      <!-- MESSAGES -->
      <section class="admin-page-view" data-page-view="messages">
        <div class="app-topbar">
          <div class="section-title"><span class="eyebrow">Inbox</span><h2>Customer Messages</h2><p>Messages submitted from the Support page arrive here.</p></div>
          <button class="primary" id="messagesRefreshBtn">Refresh</button>
        </div>
        <div class="kpi-grid" id="messagesSummary"></div>
        <section class="card panel">
          <div class="admin-client-tools">
            <label>Filter
              <select id="messagesFilter">
                <option value="all">All</option>
                <option value="new">New</option>
                <option value="read">Read</option>
                <option value="resolved">Resolved</option>
              </select>
            </label>
          </div>
          <div class="message-list" id="messageRows"></div>
        </section>
      </section>

      <!-- PLANS -->
      <section class="admin-page-view" data-page-view="plans">
        <div class="app-topbar">
          <div class="section-title"><span class="eyebrow">Catalog</span><h2>Subscription Plans</h2><p>Premium, Gold, and Offline for Windows. Android for native mobile billing.</p></div>
          <div class="admin-note-row"><span>Yearly default</span><span>Custom expiry available</span></div>
        </div>
        <div class="plan-grid" id="adminPlanRows"></div>
      </section>

      <!-- APP UPDATES -->
      <section class="admin-page-view" data-page-view="updates">
        <div class="app-topbar">
          <div class="section-title"><span class="eyebrow">Releases</span><h2>Windows App Update</h2><p>Upload the three files from the desktop build release folder.</p></div>
        </div>
        <section class="card panel release-upload-card">
          <div class="admin-release-grid">
            <label>latest.yml <input id="latestYmlFile" type="file" accept=".yml,.yaml"></label>
            <label>Setup EXE <input id="setupExeFile" type="file" accept=".exe"></label>
            <label>EXE Blockmap <input id="setupBlockmapFile" type="file" accept=".blockmap"></label>
          </div>
          <div class="upload-progress-card" id="uploadProgressCard" aria-live="polite">
            <div class="upload-progress-head">
              <span id="uploadProgressTitle">Preparing upload...</span>
              <strong id="uploadProgressPercent">0%</strong>
            </div>
            <div class="upload-progress-track">
              <div class="upload-progress-fill" id="uploadProgressFill"></div>
            </div>
            <div class="upload-progress-meta">
              <span id="uploadProgressBytes">0 B / 0 B</span>
              <span id="uploadProgressDetail">Waiting</span>
            </div>
          </div>
          <div class="row release-actions">
            <button class="primary" id="uploadUpdateBtn">Upload Update</button>
            <div class="release-action-buttons">
              <button id="refreshUpdateBtn">Refresh Info</button>
              <a class="button" href="/download/windows">Current Setup</a>
              <a class="button" href="/updates/win/latest.yml" target="_blank" rel="noreferrer">latest.yml</a>
            </div>
          </div>
          <div class="status" id="updateStatus">Load update info to see current files.</div>
          <div class="file-list" id="updateFiles"></div>
        </section>
      </section>

      <!-- WHATSAPP -->
      <section class="admin-page-view" data-page-view="whatsapp">
        <div class="app-topbar">
          <div class="section-title"><span class="eyebrow">Integration</span><h2>WhatsApp</h2><p>Notify clients on approval, renewal, and expiry (configuration).</p></div>
        </div>
        <section class="card panel">
          <div class="status warn">This module is a configuration shell. Connect a WhatsApp Business API provider to enable sending. Values are saved locally in this browser until backend support is added.</div>
          <div class="settings-form-grid">
            <label>Provider <select id="waProvider"><option value="meta">Meta Cloud API</option><option value="twilio">Twilio</option><option value="gupshup">Gupshup</option></select></label>
            <label>Sender Number <input id="waNumber" placeholder="+91XXXXXXXXXX"></label>
            <label>API Key / Token <input id="waApiKey" type="password" placeholder="Provider API token"></label>
            <label>Business Account ID <input id="waBusinessId" placeholder="Optional"></label>
          </div>
          <label>Approval Message Template <input id="waTemplate" placeholder="Hi {name}, your GI POS subscription is approved."></label>
          <div class="row"><button class="primary" id="waSaveBtn">Save Settings</button><button id="waTestBtn">Send Test</button></div>
          <div class="status" id="waStatus">Not configured.</div>
        </section>
      </section>

      <!-- ORIGIN SETTINGS -->
      <section class="admin-page-view" data-page-view="origin">
        <div class="app-topbar">
          <div class="section-title"><span class="eyebrow">Security</span><h2>Origin Settings</h2><p>Allowed origins and public base URL for the cloud API (configuration).</p></div>
        </div>
        <section class="card panel">
          <div class="status warn">This module is a configuration shell. Origin/CORS enforcement requires backend support; values are saved locally in this browser for now.</div>
          <label>Public Base URL <input id="originBaseUrl" placeholder="https://goldensea.gihostings.in"></label>
          <label>Allowed Origins (one per line)
            <textarea id="originAllowed" rows="5" style="width:100%;padding:12px;border:1px solid #cbd5e1;border-radius:7px;font:inherit;font-weight:800;min-height:120px" placeholder="https://app.example.com"></textarea>
          </label>
          <div class="row"><button class="primary" id="originSaveBtn">Save Settings</button></div>
          <div class="status" id="originStatus">Using server defaults.</div>
        </section>
      </section>
    </div></main>
  </div>
  ${APP_POWERED_BY_HTML}
  <script>
    const tokenInput = document.getElementById('adminToken');
    const rowsEl = document.getElementById('restaurantRows');
    const statusEl = document.getElementById('status');
    const updateStatusEl = document.getElementById('updateStatus');
    const updateFilesEl = document.getElementById('updateFiles');
    const adminSummaryEl = document.getElementById('adminSummary');
    const adminAnalyticsEl = document.getElementById('adminAnalytics');
    const adminPlanRows = document.getElementById('adminPlanRows');
    const clientSearchInput = document.getElementById('clientSearch');
    const clientStatusFilter = document.getElementById('clientStatusFilter');
    const uploadUpdateBtn = document.getElementById('uploadUpdateBtn');
    const uploadProgressCard = document.getElementById('uploadProgressCard');
    const uploadProgressTitle = document.getElementById('uploadProgressTitle');
    const uploadProgressPercent = document.getElementById('uploadProgressPercent');
    const uploadProgressFill = document.getElementById('uploadProgressFill');
    const uploadProgressBytes = document.getElementById('uploadProgressBytes');
    const uploadProgressDetail = document.getElementById('uploadProgressDetail');
    const planCatalog = ${PLAN_CATALOG_JSON};
    let adminRestaurants = [];
    tokenInput.value = localStorage.getItem('giAdminToken') || '';

    function defaultExpiryValue(years) {
      const date = new Date();
      date.setFullYear(date.getFullYear() + Number(years || 1));
      return date.toISOString().slice(0, 10);
    }

    function esc(value) {
      return String(value || '').replace(/[&<>"']/g, function (ch) {
        return ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#039;' })[ch];
      });
    }
    function setStatus(text, kind) {
      statusEl.className = 'status ' + (kind || '');
      statusEl.textContent = text;
    }
    function setUpdateStatus(text, kind) {
      updateStatusEl.className = 'status ' + (kind || '');
      updateStatusEl.textContent = text;
    }
    function formatDate(value) {
      return value ? new Date(value).toLocaleString() : '';
    }
    function formatUploadBytes(value) {
      const bytes = Number(value || 0);
      if (!bytes) return '0 B';
      const units = ['B', 'KB', 'MB', 'GB'];
      const index = Math.min(Math.floor(Math.log(bytes) / Math.log(1024)), units.length - 1);
      return (bytes / Math.pow(1024, index)).toFixed(index === 0 ? 0 : 1) + ' ' + units[index];
    }
    function setUploadProgress(percent, loaded, total, detail) {
      const safePercent = Math.max(0, Math.min(100, Math.round(Number(percent || 0))));
      uploadProgressCard.classList.add('active');
      uploadProgressPercent.textContent = safePercent + '%';
      uploadProgressFill.style.width = safePercent + '%';
      uploadProgressBytes.textContent = formatUploadBytes(loaded) + ' / ' + formatUploadBytes(total);
      uploadProgressDetail.textContent = detail || 'Uploading';
    }
    function uploadAdminFormData(path, form, totalBytes) {
      return new Promise(function (resolve, reject) {
        const xhr = new XMLHttpRequest();
        xhr.open('POST', path);
        xhr.setRequestHeader('x-admin-token', tokenInput.value);

        xhr.upload.onprogress = function (event) {
          const total = event.lengthComputable ? event.total : totalBytes;
          const loaded = event.loaded || 0;
          const percent = total ? (loaded / total) * 100 : 0;
          setUploadProgress(percent, loaded, total, 'Uploading to cloud');
        };

        xhr.onload = function () {
          let result = {};
          try {
            result = xhr.responseText ? JSON.parse(xhr.responseText) : {};
          } catch {
            reject(new Error('Upload completed but server response was invalid.'));
            return;
          }

          if (xhr.status < 200 || xhr.status >= 300 || result.ok === false) {
            reject(new Error(result.error || 'Upload failed'));
            return;
          }

          setUploadProgress(100, totalBytes, totalBytes, 'Upload complete. Validating files...');
          resolve(result);
        };

        xhr.onerror = function () {
          reject(new Error('Upload failed. Check cloud server connection.'));
        };

        xhr.onabort = function () {
          reject(new Error('Upload cancelled.'));
        };

        xhr.send(form);
      });
    }
    function getPlan(planName) {
      const value = String(planName || '').toLowerCase();
      return planCatalog.find(function (plan) { return value.indexOf(plan.id) >= 0 || value.indexOf(String(plan.name).toLowerCase()) >= 0; }) || planCatalog[0];
    }
    function planOptionsHtml(selectedPlanName) {
      const selectedPlan = getPlan(selectedPlanName);
      return planCatalog.map(function (plan) {
        return '<option value="' + esc(plan.name) + '"' + (plan.id === selectedPlan.id ? ' selected' : '') + '>' + esc(plan.name) + '</option>';
      }).join('');
    }
    function planFeaturesHtml(plan) {
      return '<ul>' + (plan.features || []).map(function (feature) { return '<li>' + esc(feature) + '</li>'; }).join('') + '</ul>';
    }
    function renderAdminPlanCards() {
      adminPlanRows.innerHTML = planCatalog.map(function (plan) {
        return '<article class="plan-card ' + (plan.id === 'gold' ? 'featured' : '') + '">' +
          '<h3>' + esc(plan.name) + '<span class="badge ' + (plan.id === 'gold' ? 'good' : '') + '">' + esc(plan.counterLabel) + '</span></h3>' +
          '<p>' + esc(plan.subtitle) + '</p>' +
          '<div class="plan-meta"><span class="badge">' + esc((plan.platforms || []).map(function (platform) { return platform === 'android' ? 'Android' : 'Windows'; }).join(' + ')) + '</span><span class="badge ' + (plan.localPos ? 'good' : 'warn') + '">' + (plan.localPos ? 'Local POS included' : 'Local POS not included') + '</span><span class="badge">Yearly / custom expiry</span></div>' +
          planFeaturesHtml(plan) +
        '</article>';
      }).join('');
    }
    async function api(path, options) {
      const headers = Object.assign(
        { 'content-type': 'application/json', 'x-admin-token': tokenInput.value },
        (options && options.headers) || {}
      );
      const response = await fetch(path, Object.assign({}, options || {}, { headers }));
      const result = await response.json().catch(function () { return {}; });
      if (!response.ok || result.ok === false) throw new Error(result.error || 'Request failed');
      return result;
    }
    async function adminFetchJson(path, options) {
      const response = await fetch(path, Object.assign({}, options || {}, {
        headers: Object.assign({ 'x-admin-token': tokenInput.value }, (options && options.headers) || {})
      }));
      const result = await response.json().catch(function () { return {}; });
      if (!response.ok || result.ok === false) throw new Error(result.error || 'Request failed');
      return result;
    }
    function subscriptionText(restaurant) {
      if (!restaurant.subscription_id) return 'No subscription';
      return restaurant.plan_name + ' / ' + restaurant.subscription_status + ' / ' + new Date(restaurant.expires_at).toLocaleDateString();
    }
    function subscriptionHealth(restaurant) {
      if (!restaurant.subscription_id) return { label: 'No subscription', className: 'warn' };
      const activeStatus = restaurant.subscription_status === 'active' || restaurant.subscription_status === 'trial';
      const expired = restaurant.expires_at && new Date(restaurant.expires_at).getTime() < Date.now();
      if (activeStatus && !expired) return { label: 'Active till ' + new Date(restaurant.expires_at).toLocaleDateString(), className: 'good' };
      if (expired) return { label: 'Expired ' + new Date(restaurant.expires_at).toLocaleDateString(), className: 'bad' };
      return { label: restaurant.subscription_status || 'Inactive', className: 'warn' };
    }
    function dateInputValue(value) {
      if (!value) return defaultExpiryValue();
      const date = new Date(value);
      if (Number.isNaN(date.getTime())) return defaultExpiryValue();
      return date.toISOString().slice(0, 10);
    }
    var USER_LIMIT_PRESETS = ['', '5', '10', '25', '50'];
    function userLimitIsPreset(value) { return USER_LIMIT_PRESETS.indexOf(String(value || '')) >= 0; }
    function userLimitSelectHtml(value) {
      var v = String(value || '');
      var opts = [
        { val: '', label: 'Unlimited' },
        { val: '5', label: '5 users' },
        { val: '10', label: '10 users' },
        { val: '25', label: '25 users' },
        { val: '50', label: '50 users' },
        { val: 'custom', label: 'Custom...' }
      ];
      var isPreset = userLimitIsPreset(v);
      return '<select data-user-limit-input>' + opts.map(function (o) {
        var selected = (o.val === 'custom' ? !isPreset : (o.val === v && isPreset)) ? ' selected' : '';
        return '<option value="' + o.val + '"' + selected + '>' + o.label + '</option>';
      }).join('') + '</select>';
    }
    function renderAdminSummary(restaurants) {
      const total = restaurants.length;
      const suspended = restaurants.filter(function (restaurant) { return restaurant.status === 'suspended'; }).length;
      const pending = restaurants.filter(function (restaurant) { return restaurant.status !== 'approved' && restaurant.status !== 'suspended'; }).length;
      const active = restaurants.filter(function (restaurant) {
        const health = subscriptionHealth(restaurant);
        return restaurant.status === 'approved' && health.className === 'good';
      }).length;
      const devices = restaurants.reduce(function (sum, restaurant) { return sum + Number(restaurant.active_devices || 0); }, 0);
      adminSummaryEl.className = 'kpi-grid';
      adminSummaryEl.innerHTML =
        '<div class="kpi"><div class="kpi-top"><span class="kpi-label">Total Clients</span><span class="kpi-icon">C</span></div><div class="kpi-value">' + total + '</div><span class="kpi-hint">' + devices + ' active devices</span></div>' +
        '<div class="kpi kpi-green"><div class="kpi-top"><span class="kpi-label">Active</span><span class="kpi-icon">A</span></div><div class="kpi-value">' + active + '</div><span class="kpi-hint">' + (total ? Math.round((active / total) * 100) : 0) + '% of clients</span></div>' +
        '<div class="kpi kpi-amber"><div class="kpi-top"><span class="kpi-label">Pending</span><span class="kpi-icon">P</span></div><div class="kpi-value">' + pending + '</div><span class="kpi-hint">Awaiting approval</span></div>' +
        '<div class="kpi kpi-red"><div class="kpi-top"><span class="kpi-label">Suspended</span><span class="kpi-icon">S</span></div><div class="kpi-value">' + suspended + '</div><span class="kpi-hint">Access blocked</span></div>';
    }
    function daysUntil(value) {
      if (!value) return null;
      var ms = new Date(value).getTime() - Date.now();
      if (!isFinite(ms)) return null;
      return Math.ceil(ms / (24 * 60 * 60 * 1000));
    }
    function renderAdminAnalytics(restaurants) {
      if (!adminAnalyticsEl) return;
      var approved = restaurants.filter(function (r) { return r.status === 'approved'; });
      var planCounts = {};
      planCatalog.forEach(function (plan) { planCounts[plan.id] = 0; });
      approved.forEach(function (r) { var p = getPlan(r.plan_name); if (p && planCounts[p.id] != null) planCounts[p.id] += 1; });
      var maxPlan = Math.max(1, Math.max.apply(null, planCatalog.map(function (plan) { return planCounts[plan.id]; })));
      var barsHtml = planCatalog.map(function (plan) {
        var count = planCounts[plan.id];
        var pct = Math.round((count / maxPlan) * 100);
        return '<div class="analytics-bar"><div class="analytics-bar-head"><span>' + esc(plan.name) + '</span><span>' + count + ' active</span></div>' +
          '<div class="analytics-track"><div class="analytics-fill plan-' + plan.id + '" style="width:' + pct + '%"></div></div></div>';
      }).join('');

      var expiring = approved.filter(function (r) { var d = daysUntil(r.expires_at); return d != null && d >= 0 && d <= 14; }).length;
      var expired = restaurants.filter(function (r) { var d = daysUntil(r.expires_at); return r.subscription_id && d != null && d < 0; }).length;
      var totalUsers = restaurants.reduce(function (sum, r) { return sum + Number(r.active_users || 0); }, 0);

      adminAnalyticsEl.innerHTML =
        '<div class="card panel">' +
          '<div class="section-title"><span class="eyebrow">Analytics</span><h2>Plan Distribution</h2><p>Active subscriptions by plan</p></div>' +
          '<div class="analytics-bars">' + barsHtml + '</div>' +
          '<div class="kpi-grid" style="margin-top:6px">' +
            '<div class="kpi kpi-amber"><span class="kpi-label">Expiring (14d)</span><div class="kpi-value">' + expiring + '</div></div>' +
            '<div class="kpi kpi-red"><span class="kpi-label">Expired</span><div class="kpi-value">' + expired + '</div></div>' +
            '<div class="kpi kpi-slate"><span class="kpi-label">Total Users</span><div class="kpi-value">' + totalUsers + '</div></div>' +
            '<div class="kpi kpi-green"><span class="kpi-label">Approved</span><div class="kpi-value">' + approved.length + '</div></div>' +
          '</div>' +
        '</div>' +
        '<div class="card panel">' +
          '<div class="section-title"><span class="eyebrow">Recent Activity</span><h2>Latest Clients</h2><p>Newest signups and their status</p></div>' +
          '<div class="activity-list">' + (
            restaurants.slice(0, 6).map(function (r) {
              var cls = r.status === 'approved' ? '' : (r.status === 'suspended' ? 'suspended' : 'pending');
              var initial = esc(String(r.name || '?').trim().charAt(0).toUpperCase() || '?');
              return '<div class="activity-row ' + cls + '"><div class="activity-dot">' + initial + '</div>' +
                '<div class="activity-main"><strong>' + esc(r.name) + '</strong><span>' + esc(r.owner_name || r.phone || r.email || '') + ' / ' + esc(r.status) + '</span></div>' +
                '<span class="activity-time">' + esc(r.created_at ? new Date(r.created_at).toLocaleDateString() : '') + '</span></div>';
            }).join('') || '<div class="empty-state">No clients yet.</div>'
          ) + '</div>' +
        '</div>';
    }
    function getFilteredRestaurants(restaurants) {
      const search = String(clientSearchInput.value || '').trim().toLowerCase();
      const filter = clientStatusFilter.value || 'all';

      return restaurants.filter(function (restaurant) {
        const health = subscriptionHealth(restaurant);
        const text = [
          restaurant.name,
          restaurant.owner_name,
          restaurant.phone,
          restaurant.email,
          restaurant.status,
          restaurant.plan_name,
        ].join(' ').toLowerCase();
        const matchesSearch = !search || text.indexOf(search) >= 0;
        let matchesFilter = true;

        if (filter === 'active') {
          matchesFilter = restaurant.status === 'approved' && health.className === 'good';
        } else if (filter === 'pending') {
          matchesFilter = restaurant.status !== 'approved' && restaurant.status !== 'suspended';
        } else if (filter === 'expired') {
          matchesFilter = restaurant.status === 'approved' && health.className === 'bad';
        } else if (filter === 'suspended') {
          matchesFilter = restaurant.status === 'suspended';
        }

        return matchesSearch && matchesFilter;
      });
    }
    function renderUpdateInfo(result) {
      const files = result.files || [];
      const readyText = result.ready ? 'Ready' : 'Missing files';
      const versionText = result.version ? ' / v' + result.version : '';
      const slots = [
        { label: 'Version Manifest', required: 'latest.yml', file: result.latest },
        { label: 'Windows Setup', required: 'Setup .exe', file: result.setup },
        { label: 'Blockmap', required: 'Setup .exe.blockmap', file: result.blockmap }
      ];
      const checklistHtml = '<div class="release-checklist">' + slots.map(function (slot) {
        const ok = Boolean(slot.file);
        return '<div class="release-slot ' + (ok ? 'ok' : 'missing') + '">' +
          '<strong>' + esc(slot.label) + '</strong>' +
          '<span>' + (ok ? esc(slot.file.name || slot.required) : 'Missing ' + esc(slot.required)) + '</span>' +
        '</div>';
      }).join('') + '</div>';
      setUpdateStatus(
        readyText + versionText + ' / ' + (result.updateDir || 'cloud/updates/win'),
        result.ready ? 'ok' : ''
      );
      const filesHtml = files.length
        ? files.map(function (file) {
            return '<div class="file-row">' +
              '<div><strong>' + esc(file.name) + '</strong><br><span>' + esc(formatDate(file.updatedAt)) + '</span></div>' +
              '<span>' + esc(file.sizeLabel || '') + '</span>' +
            '</div>';
          }).join('')
        : '<div class="status">No update files uploaded yet.</div>';
      updateFilesEl.innerHTML = checklistHtml + filesHtml;
    }
    async function loadUpdateInfo() {
      setUpdateStatus('Loading update files...', '');
      const result = await adminFetchJson('/api/v1/admin/updates/windows');
      renderUpdateInfo(result);
    }
    async function uploadUpdate() {
      const latest = document.getElementById('latestYmlFile').files[0];
      const setup = document.getElementById('setupExeFile').files[0];
      const blockmap = document.getElementById('setupBlockmapFile').files[0];

      if (!latest || !setup || !blockmap) {
        setUpdateStatus('Select latest.yml, setup .exe, and setup .exe.blockmap.', 'error');
        return;
      }

      if (!confirm('Replace current Windows app update files?')) {
        return;
      }

      const form = new FormData();
      form.append('latestYml', latest);
      form.append('setupExe', setup);
      form.append('setupBlockmap', blockmap);
      const totalBytes = latest.size + setup.size + blockmap.size;
      uploadUpdateBtn.disabled = true;
      setUpdateStatus('Uploading update files. Keep this page open.', '');
      uploadProgressTitle.textContent = 'Uploading Windows update files';
      setUploadProgress(0, 0, totalBytes, 'Starting upload');

      try {
        const result = await uploadAdminFormData('/api/v1/admin/updates/windows', form, totalBytes);
        renderUpdateInfo(result);
        setUpdateStatus(result.message || 'Update uploaded successfully.', 'ok');
        setUploadProgress(100, totalBytes, totalBytes, 'Ready for app updater');
      } finally {
        uploadUpdateBtn.disabled = false;
      }
    }
    function render(restaurants) {
      adminRestaurants = Array.isArray(restaurants) ? restaurants : adminRestaurants;
      renderAdminSummary(adminRestaurants);
      renderAdminAnalytics(adminRestaurants);
      const visibleRestaurants = getFilteredRestaurants(adminRestaurants);

      if (!adminRestaurants.length) {
        rowsEl.innerHTML = '<div class="empty-state">No signup or restaurant found yet.</div>';
        return;
      }

      if (!visibleRestaurants.length) {
        rowsEl.innerHTML = '<div class="empty-state">No matching clients found.</div>';
        return;
      }

      var rowsHtml = visibleRestaurants.map(function (restaurant) {
        const approved = restaurant.status === 'approved';
        const badgeClass = approved ? 'good' : restaurant.status === 'suspended' ? 'bad' : '';
        const devices = String(restaurant.active_devices || 0);
        const rowClass = restaurant.status === 'suspended' ? 'suspended' : '';
        const subscription = subscriptionHealth(restaurant);
        const planValue = restaurant.plan_name || 'Premium';
        const expiryValue = dateInputValue(restaurant.expires_at);
        const selectedPlan = getPlan(planValue);
        const userLimit = restaurant.max_users == null ? '' : String(restaurant.max_users);
        const userUsage = Number(restaurant.active_users || 1);
        const userCapacityText = userLimit ? userUsage + ' / ' + userLimit + ' users' : userUsage + ' / Unlimited users';
        const initial = esc(String(restaurant.name || '?').trim().charAt(0).toUpperCase() || '?');
        const deviceList = (restaurant.devices || []).slice(0, 4).map(function (device) {
          const platform = device.platform === 'android' ? 'Android' : 'Windows';
          return esc(device.name) + ' / ' + platform + ' - ' + (device.active ? 'active' : 'disabled') + (device.last_seen_at ? ' / ' + esc(formatDate(device.last_seen_at)) : '');
        }).join('<br>');
        return '<div class="client-row ' + rowClass + '" data-client-row="' + esc(restaurant.id) + '">' +
            '<div class="client-cell-main">' +
              '<div class="client-avatar">' + initial + '</div>' +
              '<div class="client-name"><strong>' + esc(restaurant.name) + '</strong><small>' + esc(restaurant.id) + '</small></div>' +
            '</div>' +
            '<div class="client-contact"><strong>' + esc(restaurant.owner_name || 'Owner') + '</strong><span>' + esc(restaurant.phone || restaurant.email || 'No contact') + '</span></div>' +
            '<div class="client-plan-cell">' + esc(selectedPlan.name) + '</div>' +
            '<div class="client-status-cell"><span class="badge ' + subscription.className + '">' + esc(subscription.label) + '</span></div>' +
            '<div class="client-caret">v</div>' +
          '</div>' +
          '<div class="client-drawer restaurant-card ' + rowClass + '" data-client-drawer="' + esc(restaurant.id) + '">' +
            '<div class="client-drawer-inner">' +
              '<div class="client-detail-grid">' +
                '<div class="client-detail"><span>Status</span><strong>' + esc(restaurant.status) + '</strong></div>' +
                '<div class="client-detail"><span>Plan &amp; Access</span><strong>' + esc(subscriptionText(restaurant)) + '</strong></div>' +
                '<div class="client-detail"><span>Users</span><strong>' + esc(userCapacityText) + '</strong></div>' +
                '<div class="client-detail"><span>Active Devices</span><strong>' + esc(devices) + '</strong></div>' +
                '<div class="client-detail"><span>Counter</span><strong>' + esc(selectedPlan.counterLabel) + '</strong></div>' +
                '<div class="client-detail"><span>Platform</span><strong>' + esc((selectedPlan.platforms || []).map(function (platform) { return platform === 'android' ? 'Android' : 'Windows'; }).join(' + ')) + '</strong></div>' +
              '</div>' +
              '<div class="client-detail"><span>Connected Devices</span><div class="device-list">' + (deviceList || 'No device connected yet') + '</div></div>' +
              '<div class="subscription-edit">' +
                '<label>Plan <select data-plan-input>' + planOptionsHtml(planValue) + '</select></label>' +
                '<label>Period <select data-duration-input><option value="1">1 Year</option><option value="2">2 Years</option><option value="3">3 Years</option><option value="custom" selected>Custom</option></select></label>' +
                '<label>Expiry <input data-expiry-input type="date" value="' + esc(expiryValue) + '"></label>' +
                '<label>User Limit ' + userLimitSelectHtml(userLimit) + '</label>' +
                '<label data-user-limit-custom-wrap style="' + (userLimitIsPreset(userLimit) ? 'display:none' : '') + '">Custom Users <input data-user-limit-custom type="number" min="1" max="10000" value="' + esc(userLimit) + '" placeholder="e.g. 15"></label>' +
                '<button class="primary" data-approve="' + esc(restaurant.id) + '">' + (approved ? 'Renew / Update' : 'Approve') + '</button>' +
              '</div>' +
              '<div class="row">' +
                '<button data-reset-password="' + esc(restaurant.id) + '" ' + (!restaurant.account_id ? 'disabled' : '') + '>Reset Portal Password</button>' +
                '<button class="danger" data-suspend="' + esc(restaurant.id) + '">Suspend</button>' +
                '<button class="danger" data-delete-client="' + esc(restaurant.id) + '" data-client-name="' + esc(restaurant.name) + '">Delete</button>' +
              '</div>' +
            '</div>' +
          '</div>';
      }).join('');

      rowsEl.innerHTML =
        '<div class="client-table">' +
          '<div class="client-table-head"><span>Business</span><span>Owner / Contact</span><span>Plan</span><span>Status</span><span></span></div>' +
          rowsHtml +
        '</div>';

      document.querySelectorAll('[data-client-row]').forEach(function (row) {
        row.addEventListener('click', function (event) {
          if (event.target.closest('input, select, button, a, label')) return;
          const id = row.getAttribute('data-client-row');
          const drawer = document.querySelector('[data-client-drawer="' + id + '"]');
          const isOpen = row.classList.toggle('open');
          if (drawer) drawer.classList.toggle('open', isOpen);
        });
      });
      document.querySelectorAll('[data-user-limit-input]').forEach(function (select) {
        select.addEventListener('change', function () {
          const card = select.closest('.restaurant-card');
          const wrap = card ? card.querySelector('[data-user-limit-custom-wrap]') : null;
          if (wrap) wrap.style.display = select.value === 'custom' ? '' : 'none';
        });
      });
      document.querySelectorAll('[data-approve]').forEach(function (button) {
        button.addEventListener('click', function () {
          const card = button.closest('.restaurant-card');
          const planInput = card ? card.querySelector('[data-plan-input]') : null;
          const expiryInput = card ? card.querySelector('[data-expiry-input]') : null;
          const userLimitSelect = card ? card.querySelector('[data-user-limit-input]') : null;
          const userLimitCustom = card ? card.querySelector('[data-user-limit-custom]') : null;
          var maxUsers;
          if (!userLimitSelect || userLimitSelect.value === '') maxUsers = '';
          else if (userLimitSelect.value === 'custom') maxUsers = (userLimitCustom && userLimitCustom.value) || '';
          else maxUsers = userLimitSelect.value;
          approve(button.getAttribute('data-approve'), planInput && planInput.value, expiryInput && expiryInput.value, maxUsers);
        });
      });
      document.querySelectorAll('[data-duration-input]').forEach(function (select) {
        select.addEventListener('change', function () {
          const card = select.closest('.restaurant-card');
          const expiryInput = card ? card.querySelector('[data-expiry-input]') : null;
          if (expiryInput && select.value !== 'custom') {
            expiryInput.value = defaultExpiryValue(select.value);
          }
        });
      });
      document.querySelectorAll('[data-reset-password]').forEach(function (button) {
        button.addEventListener('click', function () { resetPassword(button.getAttribute('data-reset-password')); });
      });
      document.querySelectorAll('[data-suspend]').forEach(function (button) {
        button.addEventListener('click', function () { suspend(button.getAttribute('data-suspend')); });
      });
      document.querySelectorAll('[data-delete-client]').forEach(function (button) {
        button.addEventListener('click', function () {
          deleteClient(button.getAttribute('data-delete-client'), button.getAttribute('data-client-name'));
        });
      });
    }
    async function load() {
      setStatus('Loading clients...', '');
      const result = await api('/api/v1/admin/restaurants');
      render(result.restaurants || []);
      setStatus('Loaded ' + (result.restaurants || []).length + ' restaurant(s).', 'ok');
      await loadUpdateInfo();
    }
    async function approve(id, planName, expiresAt, maxUsers) {
      setStatus('Approving restaurant...', '');
      await api('/api/v1/admin/restaurants/' + encodeURIComponent(id) + '/approve', {
        method: 'POST',
        body: JSON.stringify({
          planName: planName || 'Premium',
          expiresAt: expiresAt || defaultExpiryValue(),
          maxUsers: maxUsers || null
        })
      });
      await load();
    }
    async function resetPassword(id) {
      const newPassword = prompt('New client portal password. Minimum 6 characters.');
      if (!newPassword) return;
      if (newPassword.length < 6) {
        setStatus('Password must be at least 6 characters.', 'error');
        return;
      }
      setStatus('Resetting client portal password...', '');
      const result = await api('/api/v1/admin/restaurants/' + encodeURIComponent(id) + '/password-reset', {
        method: 'POST',
        body: JSON.stringify({ newPassword: newPassword })
      });
      setStatus(result.message || 'Client portal password reset successfully.', 'ok');
    }
    async function suspend(id) {
      if (!confirm('Suspend this restaurant?')) return;
      setStatus('Suspending restaurant...', '');
      await api('/api/v1/admin/restaurants/' + encodeURIComponent(id) + '/suspend', { method: 'POST' });
      await load();
    }
    async function deleteClient(id, name) {
      var label = name || 'this client';
      if (!confirm('Permanently delete "' + label + '"? This removes the restaurant, its account, devices, subscriptions, and cloud data. This cannot be undone.')) return;
      var typed = prompt('Type the business name to confirm deletion:\\n' + label);
      if (typed == null) return;
      if (typed.trim() !== String(name || '').trim()) {
        setStatus('Name did not match. Delete cancelled.', 'error');
        return;
      }
      setStatus('Deleting client...', '');
      try {
        var result = await api('/api/v1/admin/restaurants/' + encodeURIComponent(id), { method: 'DELETE' });
        setStatus(result.message || 'Client deleted.', 'ok');
        await load();
      } catch (error) {
        setStatus(error.message || 'Delete failed.', 'error');
      }
    }
    // ---- App shell: login gate + sidebar routing ----
    const adminLoginLayer = document.getElementById('adminLoginLayer');
    const adminAppShell = document.getElementById('adminAppShell');
    const sidebarClientCountEl = document.getElementById('sidebarClientCount');

    function showAppShell(show) {
      adminLoginLayer.style.display = show ? 'none' : '';
      adminAppShell.style.display = show ? '' : 'none';
    }
    function goToPage(pageId) {
      Array.prototype.forEach.call(document.querySelectorAll('.sidebar-link'), function (link) {
        link.classList.toggle('active', link.getAttribute('data-page') === pageId);
      });
      Array.prototype.forEach.call(document.querySelectorAll('.admin-page-view'), function (view) {
        view.classList.toggle('active', view.getAttribute('data-page-view') === pageId);
      });
    }
    Array.prototype.forEach.call(document.querySelectorAll('.sidebar-link'), function (link) {
      link.addEventListener('click', function () {
        var page = link.getAttribute('data-page');
        goToPage(page);
        if (page === 'messages') loadMessages().catch(function (error) { setStatus(error.message, 'error'); });
      });
    });

    // ---- Customer messages ----
    var adminMessages = [];
    function messageStatusBadge(status) {
      if (status === 'resolved') return '<span class="badge good">Resolved</span>';
      if (status === 'read') return '<span class="badge">Read</span>';
      return '<span class="badge warn">New</span>';
    }
    function renderMessages() {
      var listEl = document.getElementById('messageRows');
      var summaryEl = document.getElementById('messagesSummary');
      var filterEl = document.getElementById('messagesFilter');
      if (!listEl) return;
      var counts = { total: adminMessages.length, new: 0, read: 0, resolved: 0 };
      adminMessages.forEach(function (m) { counts[m.status] = (counts[m.status] || 0) + 1; });
      if (summaryEl) {
        summaryEl.className = 'kpi-grid';
        summaryEl.innerHTML =
          '<div class="kpi"><div class="kpi-top"><span class="kpi-label">Total</span><span class="kpi-icon">M</span></div><div class="kpi-value">' + counts.total + '</div><span class="kpi-hint">All messages</span></div>' +
          '<div class="kpi kpi-amber"><div class="kpi-top"><span class="kpi-label">New</span><span class="kpi-icon">N</span></div><div class="kpi-value">' + counts.new + '</div><span class="kpi-hint">Awaiting review</span></div>' +
          '<div class="kpi kpi-slate"><div class="kpi-top"><span class="kpi-label">Read</span><span class="kpi-icon">R</span></div><div class="kpi-value">' + counts.read + '</div><span class="kpi-hint">Seen</span></div>' +
          '<div class="kpi kpi-green"><div class="kpi-top"><span class="kpi-label">Resolved</span><span class="kpi-icon">D</span></div><div class="kpi-value">' + counts.resolved + '</div><span class="kpi-hint">Closed</span></div>';
      }
      var badge = document.getElementById('messagesBadge');
      if (badge) { if (counts.new > 0) { badge.style.display = ''; badge.textContent = counts.new; } else { badge.style.display = 'none'; } }
      var filter = filterEl ? filterEl.value : 'all';
      var rows = adminMessages.filter(function (m) { return filter === 'all' || m.status === filter; });
      if (!rows.length) { listEl.innerHTML = '<div class="empty-state">No messages' + (filter === 'all' ? ' yet.' : ' in this filter.') + '</div>'; return; }
      listEl.innerHTML = rows.map(function (m) {
        var contact = [m.phone, m.email].filter(Boolean).join(' &middot; ');
        var when = new Date(m.created_at).toLocaleString();
        return '<div class="message-card ' + (m.status === 'new' ? 'is-new' : '') + '" data-msg="' + m.id + '">' +
          '<div class="message-head"><div><strong>' + esc(m.name || 'Anonymous') + '</strong>' + messageStatusBadge(m.status) + '</div><span class="message-when">' + esc(when) + '</span></div>' +
          '<div class="message-contact">' + (contact || '<span style="color:var(--muted)">No contact provided</span>') + '</div>' +
          (m.message ? '<p class="message-body">' + esc(m.message) + '</p>' : '') +
          '<div class="message-actions">' +
            (m.phone ? '<a class="btn btn-sm" href="tel:' + esc(m.phone) + '">Call</a><a class="btn btn-sm" href="https://wa.me/' + esc(String(m.phone).replace(/[^0-9]/g, "")) + '" target="_blank" rel="noopener">WhatsApp</a>' : '') +
            (m.email ? '<a class="btn btn-sm" href="mailto:' + esc(m.email) + '">Email</a>' : '') +
            (m.status !== 'read' && m.status !== 'resolved' ? '<button class="btn btn-sm" data-msg-action="read" data-msg-id="' + m.id + '">Mark Read</button>' : '') +
            (m.status !== 'resolved' ? '<button class="btn btn-sm btn-primary" data-msg-action="resolved" data-msg-id="' + m.id + '">Resolve</button>' : '<button class="btn btn-sm" data-msg-action="new" data-msg-id="' + m.id + '">Reopen</button>') +
            '<button class="btn btn-sm btn-danger" data-msg-action="delete" data-msg-id="' + m.id + '">Delete</button>' +
          '</div>' +
        '</div>';
      }).join('');
      listEl.querySelectorAll('[data-msg-action]').forEach(function (btn) {
        btn.addEventListener('click', function () {
          updateMessage(btn.getAttribute('data-msg-id'), btn.getAttribute('data-msg-action')).catch(function (error) { setStatus(error.message, 'error'); });
        });
      });
    }
    async function loadMessages() {
      var result = await api('/api/v1/admin/messages');
      adminMessages = result.messages || [];
      renderMessages();
    }
    async function updateMessage(id, action) {
      var body = action === 'delete' ? { action: 'delete' } : { status: action };
      await api('/api/v1/admin/messages/' + encodeURIComponent(id), { method: 'POST', body: JSON.stringify(body) });
      await loadMessages();
    }
    var messagesRefreshBtn = document.getElementById('messagesRefreshBtn');
    if (messagesRefreshBtn) messagesRefreshBtn.addEventListener('click', function () { loadMessages().catch(function (error) { setStatus(error.message, 'error'); }); });
    var messagesFilterEl = document.getElementById('messagesFilter');
    if (messagesFilterEl) messagesFilterEl.addEventListener('change', renderMessages);

    async function signIn() {
      setStatus('Signing in...', '');
      await load();
      if (document.getElementById('rememberToken').checked) {
        localStorage.setItem('giAdminToken', tokenInput.value);
      } else {
        localStorage.removeItem('giAdminToken');
      }
      showAppShell(true);
      goToPage('overview');
      if (sidebarClientCountEl) sidebarClientCountEl.textContent = adminRestaurants.length + ' clients loaded';
      loadMessages().catch(function () {});
    }

    document.getElementById('loadBtn').addEventListener('click', function () {
      signIn().catch(function (error) { setStatus(error.message, 'error'); });
    });
    document.getElementById('adminLogoutBtn').addEventListener('click', function () {
      localStorage.removeItem('giAdminToken');
      tokenInput.value = '';
      adminRestaurants = [];
      showAppShell(false);
      setStatus('Signed out.', '');
    });
    document.getElementById('overviewRefreshBtn').addEventListener('click', function () {
      load().then(function () {
        if (sidebarClientCountEl) sidebarClientCountEl.textContent = adminRestaurants.length + ' clients loaded';
      }).catch(function (error) { setStatus(error.message, 'error'); });
    });
    document.getElementById('refreshUpdateBtn').addEventListener('click', function () {
      loadUpdateInfo().catch(function (error) { setUpdateStatus(error.message, 'error'); });
    });
    uploadUpdateBtn.addEventListener('click', function () {
      uploadUpdate().catch(function (error) { setUpdateStatus(error.message, 'error'); });
    });
    clientSearchInput.addEventListener('input', function () {
      render(adminRestaurants);
    });
    clientStatusFilter.addEventListener('change', function () {
      render(adminRestaurants);
    });

    // ---- Settings shells (WhatsApp + Origin) persisted locally ----
    function loadSettingsShell() {
      try {
        var wa = JSON.parse(localStorage.getItem('giAdminWhatsApp') || '{}');
        if (wa.provider) document.getElementById('waProvider').value = wa.provider;
        document.getElementById('waNumber').value = wa.number || '';
        document.getElementById('waApiKey').value = wa.apiKey || '';
        document.getElementById('waBusinessId').value = wa.businessId || '';
        document.getElementById('waTemplate').value = wa.template || '';
        if (wa.number || wa.apiKey) document.getElementById('waStatus').textContent = 'Saved locally. Backend delivery not yet enabled.';
        var origin = JSON.parse(localStorage.getItem('giAdminOrigin') || '{}');
        document.getElementById('originBaseUrl').value = origin.baseUrl || '';
        document.getElementById('originAllowed').value = origin.allowed || '';
        if (origin.baseUrl) document.getElementById('originStatus').textContent = 'Saved locally. Backend enforcement not yet enabled.';
      } catch (error) { /* ignore */ }
    }
    document.getElementById('waSaveBtn').addEventListener('click', function () {
      localStorage.setItem('giAdminWhatsApp', JSON.stringify({
        provider: document.getElementById('waProvider').value,
        number: document.getElementById('waNumber').value,
        apiKey: document.getElementById('waApiKey').value,
        businessId: document.getElementById('waBusinessId').value,
        template: document.getElementById('waTemplate').value
      }));
      var s = document.getElementById('waStatus'); s.className = 'status ok'; s.textContent = 'WhatsApp settings saved on this device.';
    });
    document.getElementById('waTestBtn').addEventListener('click', function () {
      var s = document.getElementById('waStatus'); s.className = 'status warn'; s.textContent = 'Test sending needs backend WhatsApp provider support (not enabled yet).';
    });
    document.getElementById('originSaveBtn').addEventListener('click', function () {
      localStorage.setItem('giAdminOrigin', JSON.stringify({
        baseUrl: document.getElementById('originBaseUrl').value,
        allowed: document.getElementById('originAllowed').value
      }));
      var s = document.getElementById('originStatus'); s.className = 'status ok'; s.textContent = 'Origin settings saved on this device.';
    });

    renderAdminPlanCards();
    loadSettingsShell();

    // Auto sign-in if a token is already saved in this browser.
    if (tokenInput.value) {
      signIn().catch(function () {
        showAppShell(false);
        setStatus('Saved token is invalid or expired. Enter it again.', 'error');
      });
    } else {
      showAppShell(false);
    }
  </script>
</body>
</html>`;

async function ensureRuntimeSchema() {
  await pool.query(`
    ALTER TABLE accounts ADD COLUMN IF NOT EXISTS recovery_code_hash TEXT NOT NULL DEFAULT '';
    ALTER TABLE accounts ADD COLUMN IF NOT EXISTS recovery_code_set_at TIMESTAMPTZ;
    ALTER TABLE subscriptions ALTER COLUMN max_devices SET DEFAULT 999999;
    ALTER TABLE subscriptions ADD COLUMN IF NOT EXISTS max_users INTEGER;
    ALTER TABLE devices ADD COLUMN IF NOT EXISTS platform TEXT NOT NULL DEFAULT 'windows';
    ALTER TABLE pairing_codes ADD COLUMN IF NOT EXISTS platform TEXT NOT NULL DEFAULT 'windows';
    CREATE INDEX IF NOT EXISTS devices_restaurant_platform_idx ON devices(restaurant_id, platform, active);
  `);
}

// One-time bridge for staff that already existed in the portal before the sync bridge was added:
// project every restaurant's portal_staff into pos-staff-users so the Main PC receives them on the
// next sync. Safe to run on every boot; it only rewrites the projected cloud_kv rows.
async function backfillPortalStaffToKv() {
  const restaurants = await pool.query('SELECT DISTINCT restaurant_id FROM portal_staff');
  let count = 0;
  for (const row of restaurants.rows) {
    try {
      const users = await syncPortalStaffToKv(row.restaurant_id);
      count += users.length;
    } catch (error) {
      console.error(`Portal staff backfill failed for restaurant ${row.restaurant_id}`, error?.message || error);
    }
  }
  if (restaurants.rows.length) {
    console.log(`Portal staff backfill complete: ${count} staff projected across ${restaurants.rows.length} restaurant(s).`);
  }
}

ensureRuntimeSchema()
  .then(async () => {
    await backfillPortalStaffToKv().catch((error) => {
      console.error('Portal staff backfill failed', error?.message || error);
    });
    server.listen(PORT, () => {
      console.log(`GI POS cloud API listening on ${PORT}`);
    });
  })
  .catch((error) => {
    console.error('Cloud database schema check failed', error);
    process.exit(1);
  });

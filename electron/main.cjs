const { app, BrowserWindow, ipcMain, dialog } = require('electron');
const { autoUpdater } = require('electron-updater');
const path = require('node:path');
const net = require('node:net');
const os = require('node:os');
const fs = require('node:fs/promises');
const { execFile } = require('node:child_process');
const { createLocalDatabase, registerLocalDatabaseHandlers } = require('./local-db.cjs');
const { createLanServer, registerLanServerHandlers } = require('./lan-server.cjs');

let mainWindow;
let localDatabase;
let lanServer;
let updateStatus = {
  state: 'idle',
  message: 'Update check not started',
  version: app.getVersion(),
  updateUrl: process.env.GI_UPDATE_URL || 'https://goldensea.gihostings.in/updates/win',
};

const CURRENCY = 'Rs.';
const APP_NAME = 'GI POS Restaurant';
const UPDATE_URL = process.env.GI_UPDATE_URL || 'https://goldensea.gihostings.in/updates/win';
const RAW_PRINT_POWERSHELL = `
param(
  [Parameter(Mandatory=$true)][string]$DataPath,
  [Parameter(Mandatory=$true)][string]$PrinterName,
  [Parameter(Mandatory=$true)][string]$DocumentName
)

Add-Type -TypeDefinition @"
using System;
using System.Runtime.InteropServices;

public class RawPrinterHelper
{
  [StructLayout(LayoutKind.Sequential, CharSet = CharSet.Ansi)]
  public class DOCINFOA
  {
    [MarshalAs(UnmanagedType.LPStr)] public string pDocName;
    [MarshalAs(UnmanagedType.LPStr)] public string pOutputFile;
    [MarshalAs(UnmanagedType.LPStr)] public string pDataType;
  }

  [DllImport("winspool.Drv", EntryPoint = "OpenPrinterA", SetLastError = true, CharSet = CharSet.Ansi, ExactSpelling = true, CallingConvention = CallingConvention.StdCall)]
  public static extern bool OpenPrinter([MarshalAs(UnmanagedType.LPStr)] string szPrinter, out IntPtr hPrinter, IntPtr pd);

  [DllImport("winspool.Drv", EntryPoint = "ClosePrinter", SetLastError = true, ExactSpelling = true, CallingConvention = CallingConvention.StdCall)]
  public static extern bool ClosePrinter(IntPtr hPrinter);

  [DllImport("winspool.Drv", EntryPoint = "StartDocPrinterA", SetLastError = true, CharSet = CharSet.Ansi, ExactSpelling = true, CallingConvention = CallingConvention.StdCall)]
  public static extern bool StartDocPrinter(IntPtr hPrinter, Int32 level, [In, MarshalAs(UnmanagedType.LPStruct)] DOCINFOA di);

  [DllImport("winspool.Drv", EntryPoint = "EndDocPrinter", SetLastError = true, ExactSpelling = true, CallingConvention = CallingConvention.StdCall)]
  public static extern bool EndDocPrinter(IntPtr hPrinter);

  [DllImport("winspool.Drv", EntryPoint = "StartPagePrinter", SetLastError = true, ExactSpelling = true, CallingConvention = CallingConvention.StdCall)]
  public static extern bool StartPagePrinter(IntPtr hPrinter);

  [DllImport("winspool.Drv", EntryPoint = "EndPagePrinter", SetLastError = true, ExactSpelling = true, CallingConvention = CallingConvention.StdCall)]
  public static extern bool EndPagePrinter(IntPtr hPrinter);

  [DllImport("winspool.Drv", EntryPoint = "WritePrinter", SetLastError = true, ExactSpelling = true, CallingConvention = CallingConvention.StdCall)]
  public static extern bool WritePrinter(IntPtr hPrinter, IntPtr pBytes, Int32 dwCount, out Int32 dwWritten);

  public static bool SendBytesToPrinter(string printerName, byte[] bytes, string documentName)
  {
    IntPtr printerHandle = IntPtr.Zero;
    IntPtr unmanagedBytes = IntPtr.Zero;
    DOCINFOA docInfo = new DOCINFOA();
    docInfo.pDocName = documentName;
    docInfo.pDataType = "RAW";

    try
    {
      if (!OpenPrinter(printerName.Normalize(), out printerHandle, IntPtr.Zero)) return false;
      if (!StartDocPrinter(printerHandle, 1, docInfo)) return false;
      if (!StartPagePrinter(printerHandle)) return false;

      unmanagedBytes = Marshal.AllocCoTaskMem(bytes.Length);
      Marshal.Copy(bytes, 0, unmanagedBytes, bytes.Length);
      Int32 written;
      bool success = WritePrinter(printerHandle, unmanagedBytes, bytes.Length, out written);

      EndPagePrinter(printerHandle);
      EndDocPrinter(printerHandle);
      return success && written == bytes.Length;
    }
    finally
    {
      if (unmanagedBytes != IntPtr.Zero) Marshal.FreeCoTaskMem(unmanagedBytes);
      if (printerHandle != IntPtr.Zero) ClosePrinter(printerHandle);
    }
  }
}
"@

[byte[]]$bytes = [System.IO.File]::ReadAllBytes($DataPath)
if ($bytes.Length -le 0) {
  throw "Print data is empty"
}

$success = [RawPrinterHelper]::SendBytesToPrinter($PrinterName, $bytes, $DocumentName)
if (-not $success) {
  $errorCode = [Runtime.InteropServices.Marshal]::GetLastWin32Error()
  throw "Raw printer write failed for '$PrinterName'. Windows error: $errorCode"
}
`;

function getAppIconPath() {
  return process.env.VITE_DEV_SERVER_URL
    ? path.join(__dirname, '..', 'public', 'app_icon.ico')
    : path.join(__dirname, '..', 'dist', 'app_icon.ico');
}

function createWindow() {
  mainWindow = new BrowserWindow({
    width: 1360,
    height: 650,
    minWidth: 1120,
    minHeight: 620,
    backgroundColor: '#f3f4f6',
    title: APP_NAME,
    icon: getAppIconPath(),
    autoHideMenuBar: true,
    webPreferences: {
      contextIsolation: true,
      nodeIntegration: false,
      preload: path.join(__dirname, 'preload.cjs'),
    },
  });

  if (process.env.VITE_DEV_SERVER_URL) {
    mainWindow.loadURL(process.env.VITE_DEV_SERVER_URL);
  } else {
    mainWindow.loadFile(path.join(__dirname, '..', 'dist', 'index.html'));
  }

  mainWindow.webContents.on('console-message', (details) => {
    if (details.level !== 'warning' && details.level !== 'error') {
      return;
    }
    writeStartupDiagnostic(
      `Renderer console (${details.sourceId}:${details.lineNumber}): ${details.message}`,
    );
  });

  mainWindow.webContents.on('did-fail-load', (_event, errorCode, errorDescription, validatedUrl) => {
    writeStartupDiagnostic(`Renderer failed to load ${validatedUrl}: ${errorCode} ${errorDescription}`);
  });

  mainWindow.webContents.on('render-process-gone', (_event, details) => {
    writeStartupDiagnostic(`Renderer process ended: ${details.reason} (exit code ${details.exitCode})`);
  });

  mainWindow.maximize();
}

function writeStartupDiagnostic(message) {
  const filePath = path.join('C:\\', 'GIPOS Restaurant', 'startup-errors.log');
  const line = `[${new Date().toISOString()}] ${message}\n`;
  fs.appendFile(filePath, line).catch(() => undefined);
}

const hasSingleInstanceLock = app.requestSingleInstanceLock();

if (!hasSingleInstanceLock) {
  app.quit();
} else {
  app.on('second-instance', () => {
    if (!mainWindow || mainWindow.isDestroyed()) {
      return;
    }

    if (mainWindow.isMinimized()) {
      mainWindow.restore();
    }
    mainWindow.show();
    mainWindow.focus();
  });

  app.whenReady().then(async () => {
    app.setName(APP_NAME);
    localDatabase = await createLocalDatabase(app);
    registerLocalDatabaseHandlers(ipcMain, () => localDatabase);
    if (shouldStartLanServer(localDatabase)) {
      lanServer = await createLanServer({
        app,
        getDatabase: () => localDatabase,
        printKot: printKotFromLanServer,
      });
    }

    registerLanServerHandlers(ipcMain, () => lanServer);
    registerUpdaterHandlers();
    createWindow();
    setupAutoUpdater();

    app.on('activate', () => {
      if (BrowserWindow.getAllWindows().length === 0) {
        createWindow();
      }
    });
  });
}

app.on('window-all-closed', () => {
  if (process.platform !== 'darwin') {
    app.quit();
  }
});

app.on('before-quit', () => {
  if (lanServer) {
    void lanServer.close();
  }

  if (localDatabase) {
    localDatabase.close();
  }
});

ipcMain.handle('printer:list', async () => {
  if (!mainWindow) {
    return [];
  }

  const printers = await mainWindow.webContents.getPrintersAsync();
  return printers.map((printer) => ({
    name: printer.name,
    displayName: printer.displayName || printer.name,
    isDefault: printer.isDefault,
    status: printer.status,
  }));
});

function registerUpdaterHandlers() {
  ipcMain.handle('updater:status', async () => updateStatus);
  ipcMain.handle('updater:check', async () => {
    if (!canUseAutoUpdater()) {
      setUpdateStatus('disabled', 'Auto update works only in the installed Windows app.');
      return updateStatus;
    }

    try {
      setUpdateStatus('checking', 'Checking for updates...');
      await autoUpdater.checkForUpdates();
    } catch (error) {
      setUpdateStatus('error', getUpdateErrorMessage(error));
    }

    return updateStatus;
  });
  ipcMain.handle('updater:install', async () => {
    if (updateStatus.state !== 'downloaded') {
      return updateStatus;
    }

    setUpdateStatus('installing', 'Installing update in the background. The app will reopen automatically...');
    autoUpdater.quitAndInstall(true, true);
    return updateStatus;
  });
}

ipcMain.handle('lan-server:stop', async () => {
  if (lanServer) {
    await lanServer.close();
    lanServer = null;
  }

  return { ok: true };
});

function setupAutoUpdater() {
  autoUpdater.autoDownload = false;
  autoUpdater.autoInstallOnAppQuit = false;
  autoUpdater.setFeedURL({
    provider: 'generic',
    url: UPDATE_URL,
  });

  autoUpdater.on('checking-for-update', () => {
    setUpdateStatus('checking', 'Checking for updates...');
  });

  autoUpdater.on('update-available', (info) => {
    setUpdateStatus('available', `Version ${info.version} available. Downloading...`, {
      latestVersion: info.version,
      updateInfo: pickUpdateInfo(info),
    });
    autoUpdater.downloadUpdate().catch((error) => {
      setUpdateStatus('error', getUpdateErrorMessage(error));
    });
  });

  autoUpdater.on('update-not-available', (info) => {
    setUpdateStatus('not-available', 'You are using the latest version.', {
      latestVersion: info.version,
      updateInfo: pickUpdateInfo(info),
    });
  });

  autoUpdater.on('download-progress', (progress) => {
    const percent = Math.round(Number(progress.percent || 0));
    setUpdateStatus('downloading', `Downloading update ${percent}%...`, {
      percent,
      bytesPerSecond: progress.bytesPerSecond,
      transferred: progress.transferred,
      total: progress.total,
    });
  });

  autoUpdater.on('update-downloaded', (info) => {
    setUpdateStatus('downloaded', `Version ${info.version} downloaded. Click Install to update and reopen.`, {
      latestVersion: info.version,
      updateInfo: pickUpdateInfo(info),
      percent: 100,
    });
  });

  autoUpdater.on('error', (error) => {
    setUpdateStatus('error', getUpdateErrorMessage(error));
  });
}

function canUseAutoUpdater() {
  return app.isPackaged && process.platform === 'win32';
}

function shouldStartLanServer(database) {
  try {
    const snapshot = database?.getSnapshot?.();
    const appMode = parseJsonValue(snapshot?.values?.['pos-app-mode'], 'cloud');
    return appMode !== 'offline';
  } catch {
    return true;
  }
}

function parseJsonValue(value, fallback) {
  if (typeof value !== 'string') {
    return fallback;
  }

  try {
    return JSON.parse(value);
  } catch {
    return value || fallback;
  }
}

function setUpdateStatus(state, message, extra = {}) {
  updateStatus = {
    ...updateStatus,
    ...extra,
    state,
    message,
    version: app.getVersion(),
    updateUrl: UPDATE_URL,
    updatedAt: new Date().toISOString(),
  };

  if (mainWindow && !mainWindow.isDestroyed()) {
    mainWindow.webContents.send('updater:status', updateStatus);
  }
}

function pickUpdateInfo(info = {}) {
  return {
    version: info.version,
    releaseDate: info.releaseDate,
    releaseName: info.releaseName,
  };
}

function getUpdateErrorMessage(error) {
  return error?.message || 'Update check failed';
}

function sanitizeFileName(value) {
  const cleaned = String(value || '')
    .replace(/[<>:"/\\|?*\x00-\x1F]/g, '-')
    .replace(/\s+/g, ' ')
    .trim();

  return cleaned || 'GI POS Report.pdf';
}

async function searchFoodImage(payload = {}) {
  const name = String(payload.name || '').trim();
  if (!name) {
    return { ok: false, error: 'Item name required' };
  }

  try {
    const variant = Math.max(1, Number(payload.variant || 1));
    const profile = buildPinterestFoodProfile(name, payload.category, payload.tags);
    const candidates = await getPinterestImageCandidates(profile.search);
    const rankedCandidates = candidates
      .map((candidate) => ({
        ...candidate,
        score: scorePinterestFoodImage(profile, candidate),
      }))
      .filter((candidate) => isPinterestFoodImageRelevant(profile, candidate))
      .sort((a, b) => b.score - a.score);
    const chosen = rankedCandidates[(variant - 1) % Math.max(1, rankedCandidates.length)];

    if (!chosen) {
      return { ok: false, error: 'No matching Pinterest food image found' };
    }

    const dataUrl = await fetchPinterestImageAsDataUrl(chosen.src);
    return {
      ok: true,
      dataUrl,
      title: cleanPinterestTitle(chosen.alt || chosen.title || name),
      sourceUrl: chosen.src,
    };
  } catch (error) {
    return { ok: false, error: error?.message || 'Pinterest image fetch failed' };
  }
}

function buildPinterestFoodProfile(name, category, tags) {
  const normalizedName = normalizePinterestText(name);
  const alias = getPinterestFoodAlias(normalizedName);
  const search = `${alias.search || normalizedName} food`.trim();
  const rawTokens = normalizedName
    .split(' ')
    .filter((token) => token.length > 2 && !PINTEREST_FOOD_MODIFIER_TOKENS.has(token));
  const dishTokens = alias.tokens.length ? alias.tokens : rawTokens;
  const tagTokens = Array.isArray(tags) ? tags.map(normalizePinterestText).filter(Boolean) : [];
  const categoryTokens = normalizePinterestText(category).split(' ').filter((token) => token.length > 2);

  return {
    search,
    dishTokens,
    rawTokens,
    categoryTokens,
    tagTokens,
    strict: alias.strict,
    requireAllDishTokens: alias.requireAllDishTokens,
  };
}

const PINTEREST_FOOD_MODIFIER_TOKENS = new Set([
  'buffet',
  'combo',
  'full',
  'half',
  'quarter',
  'special',
  'regular',
  'large',
  'small',
  'plate',
  'item',
  'menu',
]);

function normalizePinterestText(value) {
  return String(value || '')
    .toLowerCase()
    .replace(/&amp;/g, ' ')
    .replace(/[^a-z0-9\s]/g, ' ')
    .replace(/\s+/g, ' ')
    .trim();
}

function getPinterestFoodAlias(text) {
  const aliases = [
    [/alfaham|al faham|alfam|al fahm/, 'alfaham chicken', ['alfaham'], false],
    [/mandi|mandhi|manthi|mandy/, 'chicken mandi rice', ['mandi', 'mandhi', 'manthi', 'mandy'], false],
    [/biriyani|biryani/, 'biryani', ['biryani', 'biriyani'], false],
    [/porotta|parotta/, 'kerala parotta', ['porotta', 'parotta'], false],
    [/kubbus|kuboos|khubz/, 'khubz bread', ['kubbus', 'kuboos', 'khubz'], false],
    [/ney pathal|neypathal|pathal/, 'ney pathal', ['pathal', 'neypathal'], false],
    [/shawarma/, 'shawarma', ['shawarma'], false],
    [/broast|broasted/, 'broasted chicken', ['broast', 'broasted'], false],
    [/fish tawa|tawa fish/, 'tawa fish fry', ['fish', 'tawa'], true],
    [/grill chicken|chicken grill|grilled chicken/, 'grilled chicken', ['grill', 'grilled', 'chicken'], true],
    [/fresh lime|lime juice/, 'fresh lime juice', ['lime', 'juice'], true],
    [/fried rice/, 'fried rice', ['fried', 'rice'], true],
    [/noodle|noodles/, 'noodles food', ['noodle', 'noodles'], false],
    [/burger/, 'burger food', ['burger'], false],
    [/pizza/, 'pizza food', ['pizza'], false],
    [/sandwich/, 'sandwich food', ['sandwich'], false],
    [/samosa/, 'samosa food', ['samosa'], false],
    [/cutlet/, 'cutlet food', ['cutlet'], false],
    [/falooda/, 'falooda dessert', ['falooda'], false],
    [/shake/, 'milkshake', ['shake'], false],
  ];
  const match = aliases.find(([pattern]) => pattern.test(text));
  return match
    ? { search: match[1], tokens: match[2], strict: true, requireAllDishTokens: Boolean(match[3]) }
    : { search: '', tokens: [], strict: false, requireAllDishTokens: false };
}

async function getPinterestImageCandidates(searchText) {
  const searchUrl = `https://www.pinterest.com/search/pins/?q=${encodeURIComponent(searchText)}`;
  const browser = new BrowserWindow({
    width: 1200,
    height: 900,
    show: false,
    webPreferences: {
      contextIsolation: true,
      nodeIntegration: false,
      backgroundThrottling: false,
    },
  });

  try {
    await browser.loadURL(searchUrl, {
      userAgent:
        'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124 Safari/537.36',
    });
    await delay(5500);
    const candidates = await browser.webContents.executeJavaScript(`
      (() => {
        const images = Array.from(document.querySelectorAll('img'));
        return images
          .map((image) => ({
            src: image.currentSrc || image.src || '',
            alt: image.alt || '',
            title: image.getAttribute('aria-label') || image.title || '',
            width: image.naturalWidth || image.width || 0,
            height: image.naturalHeight || image.height || 0
          }))
          .filter((image) => image.src.includes('pinimg.com') && image.width >= 120 && image.height >= 120)
          .slice(0, 80);
      })()
    `);
    return Array.isArray(candidates) ? dedupePinterestCandidates(candidates) : [];
  } finally {
    if (!browser.isDestroyed()) {
      browser.destroy();
    }
  }
}

function dedupePinterestCandidates(candidates) {
  const seen = new Set();
  const result = [];
  for (const candidate of candidates) {
    const src = getHighResolutionPinterestUrl(candidate.src);
    if (!src || seen.has(src)) {
      continue;
    }

    seen.add(src);
    result.push({
      ...candidate,
      src,
      alt: String(candidate.alt || ''),
      title: String(candidate.title || ''),
      width: Number(candidate.width || 0),
      height: Number(candidate.height || 0),
    });
  }
  return result;
}

function getHighResolutionPinterestUrl(src) {
  const cleaned = String(src || '').replace(/\\u002F/g, '/').replace(/\\\//g, '/');
  if (!/^https:\/\/i\.pinimg\.com\//i.test(cleaned)) {
    return '';
  }

  return cleaned.replace(/\/(60x60|75x75|136x136|170x|236x|474x|564x)\//i, '/736x/');
}

function scorePinterestFoodImage(profile, candidate) {
  const text = normalizePinterestText(`${candidate.alt} ${candidate.title} ${candidate.src}`);
  let score = 0;

  for (const token of profile.dishTokens) {
    if (text.includes(token)) score += 14;
  }

  for (const token of profile.rawTokens) {
    if (text.includes(token)) score += 8;
  }

  for (const token of profile.categoryTokens) {
    if (text.includes(token)) score += 2;
  }

  for (const token of profile.tagTokens) {
    if (text.includes(token)) score += 1;
  }

  for (const token of ['food', 'recipe', 'dish', 'chicken', 'rice', 'restaurant', 'cuisine', 'meal']) {
    if (text.includes(token)) score += 2;
  }

  for (const token of ['dress', 'wallpaper', 'drawing', 'logo', 'poster', 'text', 'menu design', 'kitchen design']) {
    if (text.includes(token)) score -= 15;
  }

  return score;
}

function isPinterestFoodImageRelevant(profile, candidate) {
  const text = normalizePinterestText(`${candidate.alt} ${candidate.title} ${candidate.src}`);
  const hasDishToken = profile.dishTokens.some((token) => text.includes(token));
  const hasAllDishTokens = profile.dishTokens.every((token) => text.includes(token));
  const hasAllRawTokens = profile.rawTokens.length > 0 && profile.rawTokens.every((token) => text.includes(token));

  if (profile.strict) {
    return profile.requireAllDishTokens ? hasAllDishTokens && candidate.score >= 14 : hasDishToken && candidate.score >= 12;
  }

  return (hasAllRawTokens && candidate.score >= 10) || (hasDishToken && candidate.score >= 12);
}

async function fetchPinterestImageAsDataUrl(src) {
  const response = await fetch(src, {
    headers: {
      Referer: 'https://www.pinterest.com/',
      'User-Agent':
        'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124 Safari/537.36',
    },
  });

  if (!response.ok) {
    throw new Error(`Pinterest image download failed: ${response.status}`);
  }

  const contentType = response.headers.get('content-type') || 'image/jpeg';
  const buffer = Buffer.from(await response.arrayBuffer());
  return `data:${contentType};base64,${buffer.toString('base64')}`;
}

function cleanPinterestTitle(value) {
  return String(value || '')
    .replace(/\s+/g, ' ')
    .replace(/Pinterest/i, '')
    .trim()
    .slice(0, 90);
}

function delay(ms) {
  return new Promise((resolve) => setTimeout(resolve, ms));
}

ipcMain.handle('image:search-food', async (_event, payload) => searchFoodImage(payload));

ipcMain.handle('receipt:print', async (_event, payload) => {
  const settings = payload?.settings || {};

  if (settings.mode === 'network') {
    await printNetworkReceipt(payload);
    return { ok: true, mode: 'network' };
  }

  await printSystemReceipt(payload);
  return { ok: true, mode: 'system' };
});

ipcMain.handle('receipt:print-test', async (_event, settings) => {
  const payload = {
    settings,
    order: {
      billNo: 'TEST',
      orderType: 'Printer Test',
      table: 'T1',
      cashier: 'Owner',
      paymentMethod: 'Cash',
      items: [
        { name: 'Chicken Biriyani', qty: 1, price: 150, total: 150 },
        { name: 'Green Salad', qty: 1, price: 70, total: 70 },
      ],
      subtotal: 220,
      discount: 0,
      tax: 0,
      serviceCharge: 0,
      total: 220,
      paid: 220,
      balance: 0,
      createdAt: new Date().toISOString(),
    },
  };

  if (settings?.mode === 'network') {
    await printNetworkReceipt(payload);
    return { ok: true, mode: 'network' };
  }

  await printSystemReceipt(payload);
  return { ok: true, mode: 'system' };
});

ipcMain.handle('report:print', async (_event, payload) => {
  const settings = payload?.settings || {};

  if (settings.mode === 'network') {
    await printNetworkReport(payload);
    return { ok: true, mode: 'network' };
  }

  await printSystemReport(payload);
  return { ok: true, mode: 'system' };
});

ipcMain.handle('report:export-pdf', async (_event, payload) => {
  const html = String(payload?.html || '');
  const defaultFileName = sanitizeFileName(String(payload?.defaultFileName || 'GI POS Report.pdf'));

  if (!html.trim()) {
    throw new Error('Report content is empty');
  }

  const result = await dialog.showSaveDialog(mainWindow, {
    title: 'Export Report PDF',
    defaultPath: defaultFileName.endsWith('.pdf') ? defaultFileName : `${defaultFileName}.pdf`,
    filters: [{ name: 'PDF', extensions: ['pdf'] }],
  });

  if (result.canceled || !result.filePath) {
    return { ok: false, canceled: true };
  }

  await exportHtmlToPdf(html, result.filePath);
  return { ok: true, path: result.filePath };
});

ipcMain.handle('kot:print', async (_event, payload) => {
  const settings = payload?.settings || {};

  if (settings.mode === 'network') {
    await printNetworkKot(payload);
    return { ok: true, mode: 'network' };
  }

  await printSystemKot(payload);
  return { ok: true, mode: 'system' };
});

ipcMain.handle('kot:print-test', async (_event, payload) => {
  const settings = payload?.settings || {};
  const station = payload?.station || 'Kitchen';
  const testPayload = {
    settings,
    kot: {
      billNo: 'TEST',
      station,
      orderType: 'Dining',
      table: 'T1',
      cashier: 'Owner',
      items: [
        { name: 'Chicken Biriyani', qty: 1, description: 'Less spicy' },
        { name: 'Fresh Lime', qty: 2, description: 'No ice' },
      ],
      createdAt: new Date().toISOString(),
    },
  };

  if (settings.mode === 'network') {
    await printNetworkKot(testPayload);
    return { ok: true, mode: 'network' };
  }

  await printSystemKot(testPayload);
  return { ok: true, mode: 'system' };
});

async function printKotFromLanServer(payload) {
  const settings = payload?.settings || {};

  if (settings.mode === 'network') {
    await printNetworkKot(payload);
    return { ok: true, mode: 'network' };
  }

  await printSystemKot(payload);
  return { ok: true, mode: 'system' };
}

function printSystemReceipt(payload) {
  if (shouldUseWindowsRawEscPos(payload?.settings)) {
    return printWindowsRawEscPos(payload?.settings, buildEscPosReceipt(payload), 'GI POS Receipt');
  }

  return printSystemHtml(payload, buildReceiptHtml(payload), 'Printer rejected the print job');
}

function printSystemKot(payload) {
  if (shouldUseWindowsRawEscPos(payload?.settings)) {
    return printWindowsRawEscPos(payload?.settings, buildEscPosKot(payload), 'GI POS KOT');
  }

  return printSystemHtml(payload, buildKotHtml(payload), 'Printer rejected the KOT print job');
}

function printSystemReport(payload) {
  if (shouldUseWindowsRawEscPos(payload?.settings)) {
    return printWindowsRawEscPos(payload?.settings, buildEscPosReport(payload), 'GI POS Report');
  }

  return printSystemHtml(payload, buildReportHtml(payload), 'Printer rejected the report print job');
}

function exportHtmlToPdf(html, filePath) {
  return new Promise((resolve, reject) => {
    const pdfWindow = new BrowserWindow({
      width: 900,
      height: 1200,
      show: false,
      webPreferences: {
        sandbox: true,
      },
    });

    pdfWindow.webContents.once('did-finish-load', async () => {
      try {
        const pdf = await pdfWindow.webContents.printToPDF({
          printBackground: true,
          margins: {
            marginType: 'custom',
            top: 0.35,
            bottom: 0.35,
            left: 0.35,
            right: 0.35,
          },
          pageSize: 'A4',
          landscape: false,
        });
        await fs.writeFile(filePath, pdf);
        pdfWindow.close();
        resolve();
      } catch (error) {
        pdfWindow.close();
        reject(error);
      }
    });

    pdfWindow.webContents.once('did-fail-load', (_event, _errorCode, errorDescription) => {
      pdfWindow.close();
      reject(new Error(errorDescription || 'Failed to render report PDF'));
    });

    pdfWindow.loadURL(`data:text/html;charset=utf-8,${encodeURIComponent(html)}`);
  });
}

function printSystemHtml(payload, html, rejectionMessage) {
  const settings = payload?.settings || {};
  const deviceName = settings.deviceName || '';

  return new Promise((resolve, reject) => {
    const printWindow = new BrowserWindow({
      width: 420,
      height: 700,
      show: false,
      webPreferences: {
        sandbox: true,
      },
    });

    printWindow.webContents.once('did-finish-load', async () => {
      try {
        const metrics = await waitForPrintableContent(printWindow);
        if (!metrics.textLength || metrics.height < 40) {
          throw new Error('Print content is blank. Check printer mode and try Test Print again.');
        }

        const options = {
          silent: true,
          printBackground: true,
          margins: { marginType: 'none' },
          deviceName,
          // Thermal Windows drivers are more reliable when their own configured
          // POS 80mm or POS 58mm page size is used instead of a Chromium custom size.
          usePrinterDefaultPageSize: true,
        };

        printWindow.webContents.print(options, (success, errorType) => {
          printWindow.close();
          if (success) {
            resolve();
          } else {
            reject(new Error(errorType || rejectionMessage));
          }
        });
      } catch (error) {
        printWindow.close();
        reject(error);
      }
    });

    printWindow.webContents.once('did-fail-load', (_event, _errorCode, errorDescription) => {
      printWindow.close();
      reject(new Error(errorDescription || 'Failed to load print content'));
    });

    printWindow.loadURL(`data:text/html;charset=utf-8,${encodeURIComponent(html)}`);
  });
}

async function waitForPrintableContent(printWindow) {
  return printWindow.webContents.executeJavaScript(
    `
      new Promise((resolve) => {
        const done = () => {
          const height = Math.ceil(Math.max(
            document.body.scrollHeight,
            document.body.offsetHeight,
            document.documentElement.scrollHeight,
            document.documentElement.offsetHeight
          ));
          const textLength = (document.body.innerText || '').trim().length;
          resolve({ height, textLength });
        };

        const images = Array.from(document.images || []);
        const waitForImages = images.length
          ? Promise.all(images.map((image) => image.complete ? true : new Promise((resolveImage) => {
              image.addEventListener('load', resolveImage, { once: true });
              image.addEventListener('error', resolveImage, { once: true });
            })))
          : Promise.resolve();

        waitForImages
          .then(() => document.fonts && document.fonts.ready ? document.fonts.ready : undefined)
          .then(() => requestAnimationFrame(() => requestAnimationFrame(done)))
          .catch(done);
      })
    `,
    true,
  );
}

function getPrintableHtmlWidthMm(paperWidth) {
  return paperWidth === 58 ? 44 : 64;
}

function getEscPosColumns(settings) {
  return settings?.paperWidth === '58' ? 28 : 40;
}

function shouldUseWindowsRawEscPos(settings = {}) {
  return process.platform === 'win32' && (settings.printMethod || 'escpos') === 'escpos';
}

async function printWindowsRawEscPos(settings = {}, body, jobName) {
  const printerName = String(settings.deviceName || '').trim();
  if (!printerName) {
    throw new Error('Select installed printer before printing');
  }

  const tempDir = app.getPath('temp') || os.tmpdir();
  const uniqueId = `${Date.now()}-${Math.random().toString(36).slice(2, 10)}`;
  const dataPath = path.join(tempDir, `gi-pos-print-${uniqueId}.bin`);
  const scriptPath = path.join(tempDir, `gi-pos-raw-print-${uniqueId}.ps1`);

  await fs.writeFile(dataPath, body);
  await fs.writeFile(scriptPath, RAW_PRINT_POWERSHELL, 'utf8');

  try {
    await execFilePromise(
      'powershell.exe',
      ['-NoProfile', '-ExecutionPolicy', 'Bypass', '-File', scriptPath, dataPath, printerName, jobName || 'GI POS Print'],
      { windowsHide: true, timeout: 20000 },
    );
  } finally {
    await Promise.allSettled([fs.unlink(dataPath), fs.unlink(scriptPath)]);
  }
}

function execFilePromise(command, args, options) {
  return new Promise((resolve, reject) => {
    execFile(command, args, options, (error, stdout, stderr) => {
      if (error) {
        reject(new Error(String(stderr || stdout || error.message || error)));
        return;
      }
      resolve(stdout);
    });
  });
}

function printNetworkReceipt(payload) {
  const settings = payload?.settings || {};
  const host = settings.ipAddress;
  const port = Number(settings.port || 9100);

  if (!host) {
    throw new Error('Printer IP address is required for network printing');
  }

  const body = buildEscPosReceipt(payload);

  return new Promise((resolve, reject) => {
    const socket = new net.Socket();
    let settled = false;

    const finish = (error) => {
      if (settled) {
        return;
      }
      settled = true;
      socket.destroy();
      if (error) {
        reject(error);
      } else {
        resolve();
      }
    };

    socket.setTimeout(6000);
    socket.once('timeout', () => finish(new Error('Network printer timed out')));
    socket.once('error', finish);
    socket.connect(port, host, () => {
      socket.write(body, (error) => {
        if (error) {
          finish(error);
          return;
        }
        socket.end();
      });
    });
    socket.once('close', () => finish());
  });
}

function printNetworkReport(payload) {
  const settings = payload?.settings || {};
  const host = settings.ipAddress;
  const port = Number(settings.port || 9100);

  if (!host) {
    throw new Error('Printer IP address is required for network report printing');
  }

  const body = buildEscPosReport(payload);

  return new Promise((resolve, reject) => {
    const socket = new net.Socket();
    let settled = false;

    const finish = (error) => {
      if (settled) {
        return;
      }
      settled = true;
      socket.destroy();
      if (error) {
        reject(error);
      } else {
        resolve();
      }
    };

    socket.setTimeout(6000);
    socket.once('timeout', () => finish(new Error('Network report printer timed out')));
    socket.once('error', finish);
    socket.connect(port, host, () => {
      socket.write(body, (error) => {
        if (error) {
          finish(error);
          return;
        }
        socket.end();
      });
    });
    socket.once('close', () => finish());
  });
}

function printNetworkKot(payload) {
  const settings = payload?.settings || {};
  const host = settings.ipAddress;
  const port = Number(settings.port || 9100);

  if (!host) {
    throw new Error('Printer IP address is required for network KOT printing');
  }

  const body = buildEscPosKot(payload);

  return new Promise((resolve, reject) => {
    const socket = new net.Socket();
    let settled = false;

    const finish = (error) => {
      if (settled) {
        return;
      }
      settled = true;
      socket.destroy();
      if (error) {
        reject(error);
      } else {
        resolve();
      }
    };

    socket.setTimeout(6000);
    socket.once('timeout', () => finish(new Error('Network KOT printer timed out')));
    socket.once('error', finish);
    socket.connect(port, host, () => {
      socket.write(body, (error) => {
        if (error) {
          finish(error);
          return;
        }
        socket.end();
      });
    });
    socket.once('close', () => finish());
  });
}

function buildReceiptHtml(payload) {
  const { order } = payload;
  const business = order.business || {};
  const businessName = business.name || 'Restaurant';
  const businessLines = [
    business.branch || 'Main Branch',
    business.address,
    business.phone ? `Phone: ${business.phone}` : '',
    business.gstin ? `GSTIN: ${business.gstin}` : '',
    business.fssai ? `FSSAI: ${business.fssai}` : '',
  ]
    .filter(Boolean)
    .map((lineText) => `<div class="muted">${escapeHtml(lineText)}</div>`)
    .join('');
  const logo = business.logoDataUrl
    ? `<img class="logo" src="${escapeHtml(business.logoDataUrl)}" alt="" />`
    : '';
  const footerNote = business.footerNote || 'Thank you. Visit again.';
  const paperWidth = payload?.settings?.paperWidth === '58' ? 58 : 80;
  const printableWidth = getPrintableHtmlWidthMm(paperWidth);
  const now = new Date(order.createdAt || Date.now());
  const staffName = order.serviceStaffName || order.cashier || 'Admin';
  const totalQuantity = (order.items || []).reduce((sum, item) => sum + Number(item.qty || 0), 0);
  const taxableSubtotal = Number(order.taxableSubtotal ?? Math.max(0, Number(order.subtotal || 0) - Number(order.tax || 0)));
  const roundOff = Number(order.roundOff || 0);
  const taxBreakdown = buildReceiptTaxBreakdown(order);
  const taxRows = taxBreakdown.length
    ? taxBreakdown
        .map((row) => {
          const rateText = row.rate > 0 ? ` @${formatRate(row.rate)}%` : '';
          const halfRateText = row.rate > 0 ? ` @${formatRate(row.rate / 2)}%` : '';
          return `
            <tr><td>GST${rateText}</td><td>${money(row.amount)}</td></tr>
            <tr><td>CGST${halfRateText}</td><td>${money(row.amount / 2)}</td></tr>
            <tr><td>SGST${halfRateText}</td><td>${money(row.amount / 2)}</td></tr>
          `;
        })
        .join('')
    : '';
  const itemRows = order.items
    .map(
      (item) => `
        <tr>
          <td>${escapeHtml(item.name)}</td>
          <td class="qty">${formatQty(item.qty)}</td>
          <td class="price">${compactMoney(item.price)}</td>
          <td class="amount">${compactMoney(item.total)}</td>
        </tr>
      `,
    )
    .join('');
  return `
    <!doctype html>
    <html>
      <head>
        <meta charset="utf-8" />
        <style>
          @page { margin: 0; size: ${paperWidth}mm auto; }
          * { box-sizing: border-box; }
          html { background: #fff; }
          body {
            margin: 0 auto;
            padding: 1.5mm 1.5mm ${paperWidth === 58 ? 1.5 : 3}mm;
            width: ${printableWidth}mm;
            background: #fff;
            color: #000;
            font-family: "Arial", "Segoe UI", sans-serif;
            font-size: ${paperWidth === 58 ? 9 : 11}px;
            overflow: visible;
            -webkit-print-color-adjust: exact;
            print-color-adjust: exact;
          }
          .center { text-align: center; }
          .logo { width: ${paperWidth === 58 ? 34 : 44}px; height: ${paperWidth === 58 ? 34 : 44}px; object-fit: contain; margin-bottom: 4px; }
          .shop { font-size: ${paperWidth === 58 ? 14 : 17}px; font-weight: 800; letter-spacing: .3px; }
          .muted { color: #000; font-weight: 600; }
          .rule { border-top: 1px solid #000; margin: 8px 0; }
          .meta, .totals { width: 100%; border-collapse: collapse; table-layout: fixed; }
          .meta td, .totals td { padding: 2px 0; vertical-align: top; }
          .meta-grid { display: grid; grid-template-columns: minmax(0, 0.43fr) minmax(0, 0.57fr); gap: 7px; }
          .meta-block { min-width: 0; }
          .meta-block.right { text-align: right; }
          .meta-row { display: flex; justify-content: space-between; gap: 4px; padding: 2px 0; min-width: 0; }
          .meta-row span:first-child { flex: 0 0 auto; }
          .meta-row span:last-child { min-width: 0; overflow-wrap: anywhere; word-break: break-word; text-align: right; }
          .meta-row.kot-no { font-weight: 900; font-size: ${paperWidth === 58 ? 13 : 15}px; }
          .totals td:first-child { width: 46%; }
          .totals td:last-child { width: 54%; text-align: right; white-space: nowrap; padding-left: 3px; }
          table.items { width: 100%; border-collapse: collapse; table-layout: fixed; }
          .items td { padding: 4px 0; vertical-align: top; }
          .items td:first-child { width: 42%; padding-right: 2px; overflow-wrap: anywhere; }
          .items td.qty { width: 13%; text-align: center; white-space: nowrap; }
          .items td.price { width: 20%; text-align: right; white-space: nowrap; padding-left: 2px; overflow: hidden; font-size: ${paperWidth === 58 ? 8 : 11}px; }
          .items td.amount { width: 25%; text-align: right; white-space: nowrap; padding-left: 2px; overflow: hidden; font-size: ${paperWidth === 58 ? 8 : 11}px; }
          .items-head td { font-weight: 900; padding: 1px 0 3px; border-bottom: 1px solid #000; }
          .items-head td.qty { text-align: center; }
          .items-head td.price { text-align: right; }
          .items-head td.amount { text-align: right; }
          .total-qty td { padding-top: 5px; font-weight: 400; }
          .subtotal-row td { padding-top: 6px; }
          .subtotal-label strong { display: block; font-size: ${paperWidth === 58 ? 10 : 12}px; }
          .subtotal-label small { display: block; font-size: ${paperWidth === 58 ? 7 : 9}px; font-weight: 600; }
          .grand td { font-size: ${paperWidth === 58 ? 16 : 20}px; font-weight: 900; border-top: 2px solid #000; padding-top: 7px; }
          .thanks { margin-top: 10px; font-weight: 700; }
        </style>
      </head>
      <body>
        <div class="center">
          ${logo}
          <div class="shop">${escapeHtml(businessName)}</div>
          ${businessLines}
        </div>
        <div class="rule"></div>
        <div class="meta-grid">
          <div class="meta-block">
            <div class="meta-row"><span>Bill</span><span>${escapeHtml(order.billNo)}</span></div>
            <div class="meta-row kot-no"><span>KOT</span><span>${escapeHtml(order.kotNo || '')}</span></div>
            <div class="meta-row"><span>Payment</span><span>${escapeHtml(order.paymentMethod || 'Cash')}</span></div>
            ${order.customer ? `<div class="meta-row"><span>Name</span><span>${escapeHtml(order.customer)}</span></div>` : ''}
            ${order.customerPhone ? `<div class="meta-row"><span>Phone</span><span>${escapeHtml(order.customerPhone)}</span></div>` : ''}
            ${order.customerAddress ? `<div class="meta-row"><span>Address</span><span>${escapeHtml(order.customerAddress)}</span></div>` : ''}
          </div>
          <div class="meta-block right">
            <div class="meta-row"><span>Date</span><span>${escapeHtml(formatDateTime(now))}</span></div>
            <div class="meta-row"><span>Order</span><span>${escapeHtml(order.orderType)}${order.table ? ` / ${escapeHtml(order.table)}` : ''}</span></div>
            <div class="meta-row"><span>Staff</span><span>${escapeHtml(staffName)}</span></div>
          </div>
        </div>
        <div class="rule"></div>
        <table class="items"><tr class="items-head"><td>Item</td><td class="qty">Qty</td><td class="price">Price</td><td class="amount">Amount</td></tr>${itemRows}<tr class="total-qty"><td>Total Qty</td><td class="qty">${formatQty(totalQuantity)}</td><td class="price"></td><td class="amount"></td></tr></table>
        <table class="totals">
          <tr class="subtotal-row"><td class="subtotal-label"><strong>Subtotal</strong><small>Excl. GST</small></td><td>${money(taxableSubtotal)}</td></tr>
          ${order.discount > 0 ? `<tr><td>Discount</td><td>-${money(order.discount)}</td></tr>` : ''}
          ${taxRows}
          ${order.serviceCharge > 0 ? `<tr><td>Service Charge</td><td>${money(order.serviceCharge)}</td></tr>` : ''}
          ${roundOff !== 0 ? `<tr><td>Round Off</td><td>${roundOff > 0 ? '+' : ''}${money(roundOff)}</td></tr>` : ''}
          <tr class="grand"><td>Total</td><td>${money(order.total)}</td></tr>
        </table>
        <div class="rule"></div>
        <div class="center thanks">${escapeHtml(footerNote)}</div>
      </body>
    </html>
  `;
}

function buildReportHtml(payload) {
  const report = payload.report || {};
  const business = report.business || {};
  const businessName = business.name || 'Restaurant';
  const paperWidth = payload?.settings?.paperWidth === '58' ? 58 : 80;
  const printableWidth = getPrintableHtmlWidthMm(paperWidth);
  const generatedAt = new Date(report.generatedAt || Date.now());
  const options = report.printOptions || {};
  const isEnabled = (key) => options[key] !== false;
  const orderTypeRows = (report.orderTypeRows || [])
    .map((row) => `<tr><td>${escapeHtml(row.label)} x ${Number(row.count || 0)}</td><td>${money(row.total)}</td></tr>`)
    .join('');
  const paymentRows = (report.paymentRows || [])
    .map((row) => `<tr><td>${escapeHtml(row.label)}</td><td>${money(row.amount)}</td></tr>`)
    .join('');
  const itemRows = (report.itemRows || [])
    .map(
      (row) => `<tr><td>${escapeHtml(row.label)}</td><td class="qty">${Number(row.count || 0)}</td><td>${money(row.total)}</td></tr>`,
    )
    .join('');
  const summaryRows = [
    ['showTotalSales', 'Total Sales', money(report.salesTotal), 'grand'],
    ['showAmountReceived', 'Amount Received', money(report.receivedTotal), ''],
    ['showPaidBills', 'Paid Bills', String(Number(report.paidCount || 0)), ''],
    ['showOpeningCash', 'Opening Cash', money(report.openingCash), ''],
    ['showCashInHand', 'Cash In Hand', money(report.cashInHand), ''],
    ['showBank', 'Bank', money(report.bankTotal), ''],
    ['showDueCredit', 'Due / Credit', money(report.balanceTotal), ''],
    ['showDiscount', 'Discount', money(report.discountTotal), ''],
    ['showExpenses', 'Expense', money(report.expenseTotal), ''],
    ['showNetAmount', 'Net Amount', money(report.netTotal), 'grand'],
    ['showOpenBills', 'Open Amount', money(report.openTotal), ''],
    ['showOpenBills', 'Open Bills', String(Number(report.openCount || 0)), ''],
  ]
    .filter(([key]) => isEnabled(key))
    .map(([, label, value, className]) => `<tr class="${className}"><td>${label}</td><td>${value}</td></tr>`)
    .join('');

  return `
    <!doctype html>
    <html>
      <head>
        <meta charset="utf-8" />
        <style>
          @page { margin: 0; size: ${paperWidth}mm auto; }
          * { box-sizing: border-box; }
          html { background: #fff; }
          body {
            margin: 0 auto;
            padding: 1.5mm 1.5mm 4mm;
            width: ${printableWidth}mm;
            background: #fff;
            color: #000;
            font-family: "Arial", "Segoe UI", sans-serif;
            font-size: ${paperWidth === 58 ? 9 : 11}px;
            overflow: visible;
            -webkit-print-color-adjust: exact;
            print-color-adjust: exact;
          }
          .center { text-align: center; }
          .shop { font-size: ${paperWidth === 58 ? 14 : 17}px; font-weight: 800; }
          .title { margin-top: 4px; font-size: ${paperWidth === 58 ? 12 : 14}px; font-weight: 800; }
          .muted { color: #000; font-weight: 600; }
          .rule { border-top: 1px dashed #000; margin: 8px 0; }
          table { width: 100%; border-collapse: collapse; table-layout: fixed; }
          td { padding: 2px 0; vertical-align: top; }
           td:first-child { width: 54%; overflow-wrap: anywhere; }
           td:last-child { width: 46%; text-align: right; white-space: nowrap; padding-left: 3px; font-variant-numeric: tabular-nums; }
           .grand td { font-size: ${paperWidth === 58 ? 11 : 14}px; font-weight: 800; border-top: 1px solid #000; padding-top: 6px; }
           .section { margin-top: 8px; font-weight: 800; }
           .item-table td:first-child { width: 56%; }
           .item-table td.qty { width: 14%; text-align: right; white-space: nowrap; }
           .item-table td:last-child { width: 30%; }
           .item-head td { font-size: 9px; font-weight: 800; border-bottom: 1px solid #000; }
          .thanks { margin-top: 10px; font-weight: 700; }
        </style>
      </head>
      <body>
        <div class="center">
          <div class="shop">${escapeHtml(businessName)}</div>
          ${business.branch ? `<div class="muted">${escapeHtml(business.branch)}</div>` : ''}
          ${business.phone ? `<div class="muted">Phone: ${escapeHtml(business.phone)}</div>` : ''}
          <div class="title">${escapeHtml(report.title || 'Sales Report')}</div>
          <div class="muted">${escapeHtml(report.periodLabel || '')}</div>
          <div class="muted">${escapeHtml(formatDateTime(generatedAt))}</div>
        </div>
        <div class="rule"></div>
        <table>
          ${summaryRows}
        </table>
        ${isEnabled('showPaymentSummary') && paymentRows ? `<div class="rule"></div><div class="section">Payment Summary</div><table>${paymentRows}</table>` : ''}
        ${isEnabled('showOrderTypes') ? `<div class="rule"></div><div class="section">Order Type</div><table>${orderTypeRows || '<tr><td>No orders</td><td>0.00</td></tr>'}</table>` : ''}
        ${isEnabled('showItemSummary') ? `<div class="rule"></div><div class="section">Item Summary</div><table class="item-table"><tr class="item-head"><td>Item</td><td class="qty">Qty</td><td>Total</td></tr>${itemRows || '<tr><td>No item sales</td><td class="qty">0</td><td>0.00</td></tr>'}</table>` : ''}
        <div class="rule"></div>
        <div class="center thanks">End of report</div>
      </body>
    </html>
  `;
}

function buildKotHtml(payload) {
  const kot = payload.kot || {};
  const paperWidth = payload?.settings?.paperWidth === '58' ? 58 : 80;
  const printableWidth = getPrintableHtmlWidthMm(paperWidth);
  const now = new Date(kot.createdAt || Date.now());
  const staffName = kot.serviceStaffName || kot.cashier || 'Admin';
  const itemRows = (kot.items || [])
    .map(
      (item) => `
        <tr>
          <td class="qty">${formatQty(item.qty)}</td>
          <td>
            <strong>${escapeHtml(item.name)}</strong>
            ${item.description ? `<span>${escapeHtml(item.description)}</span>` : ''}
          </td>
        </tr>
      `,
    )
    .join('');

  return `
    <!doctype html>
    <html>
      <head>
        <meta charset="utf-8" />
        <style>
          @page { margin: 0; size: ${paperWidth}mm auto; }
          * { box-sizing: border-box; }
          html { background: #fff; }
          body {
            margin: 0 auto;
            padding: 1.5mm 1.5mm 4mm;
            width: ${printableWidth}mm;
            background: #fff;
            color: #000;
            font-family: "Arial", "Segoe UI", sans-serif;
            font-size: ${paperWidth === 58 ? 11 : 13}px;
            overflow: visible;
            -webkit-print-color-adjust: exact;
            print-color-adjust: exact;
          }
          .center { text-align: center; }
          .title { font-size: ${paperWidth === 58 ? 18 : 22}px; font-weight: 900; letter-spacing: .8px; }
          .station { margin-top: 2px; font-size: ${paperWidth === 58 ? 14 : 16}px; font-weight: 800; }
          .rule { border-top: 1px solid #000; margin: 8px 0; }
          .meta-grid { display: grid; grid-template-columns: minmax(0, .43fr) minmax(0, .57fr); gap: 7px; }
          .meta-block { min-width: 0; }
          .meta-block.right { text-align: right; }
          .meta-row { display: flex; justify-content: space-between; gap: 4px; padding: 2px 0; min-width: 0; }
          .meta-row span:first-child { flex: 0 0 auto; }
          .meta-row span:last-child { min-width: 0; overflow-wrap: anywhere; word-break: break-word; text-align: right; }
          .meta-row.kot-no { font-weight: 900; font-size: ${paperWidth === 58 ? 13 : 15}px; }
          table.items { width: 100%; border-collapse: collapse; }
          .items td { padding: 7px 0; border-bottom: 1px dotted #000; vertical-align: top; }
          .items .qty { width: 34px; font-size: ${paperWidth === 58 ? 17 : 20}px; font-weight: 900; text-align: center; }
          .items strong { display: block; font-size: ${paperWidth === 58 ? 13 : 15}px; overflow-wrap: anywhere; }
          .items span { display: block; margin-top: 2px; color: #000; font-weight: 600; overflow-wrap: anywhere; }
          .order-note { margin-top: 8px; padding-top: 6px; border-top: 1px solid #000; overflow-wrap: anywhere; }
          .order-note strong { display: block; margin-bottom: 3px; }
          .footer { margin-top: 10px; text-align: center; font-weight: 800; }
        </style>
      </head>
      <body>
        <div class="center">
          <div class="title">KOT</div>
          <div class="station">${escapeHtml(kot.station || 'Kitchen')}</div>
        </div>
        <div class="rule"></div>
        <div class="meta-grid">
          <div class="meta-block">
            <div class="meta-row"><span>Bill</span><span>${escapeHtml(kot.billNo || '')}</span></div>
            <div class="meta-row kot-no"><span>KOT</span><span>${escapeHtml(kot.kotNo || '')}</span></div>
            ${kot.customer ? `<div class="meta-row"><span>Name</span><span>${escapeHtml(kot.customer)}</span></div>` : ''}
          </div>
          <div class="meta-block right">
            <div class="meta-row"><span>Date</span><span>${escapeHtml(formatDateTime(now))}</span></div>
            <div class="meta-row"><span>Order</span><span>${escapeHtml(kot.orderType || '')}${kot.table ? ` / ${escapeHtml(kot.table)}` : ''}</span></div>
            <div class="meta-row"><span>Staff</span><span>${escapeHtml(staffName)}</span></div>
          </div>
        </div>
        <div class="rule"></div>
        <table class="items">${itemRows}</table>
        ${kot.note ? `<div class="order-note"><strong>KOT Note</strong><span>${escapeHtml(kot.note)}</span></div>` : ''}
        <div class="rule"></div>
        <div class="footer">Kitchen Order Ticket</div>
      </body>
    </html>
  `;
}

function buildEscPosReceipt(payload) {
  const order = payload.order;
  const business = order.business || {};
  const businessName = business.name || 'Restaurant';
  const businessLines = [
    business.branch || 'Main Branch',
    business.address,
    business.phone ? `Phone: ${business.phone}` : '',
    business.gstin ? `GSTIN: ${business.gstin}` : '',
    business.fssai ? `FSSAI: ${business.fssai}` : '',
  ].filter(Boolean);
  const footerNote = business.footerNote || 'Thank you. Visit again.';
  const columns = getEscPosColumns(payload?.settings);
  const parts = [];
  const staffName = order.serviceStaffName || order.cashier || 'Admin';
  const totalQuantity = (order.items || []).reduce((sum, item) => sum + Number(item.qty || 0), 0);
  const taxableSubtotal = Number(order.taxableSubtotal ?? Math.max(0, Number(order.subtotal || 0) - Number(order.tax || 0)));
  const roundOff = Number(order.roundOff || 0);
  const taxBreakdown = buildReceiptTaxBreakdown(order);

  push(parts, [0x1b, 0x40]);
  align(parts, 1);
  size(parts, 0x11);
  text(parts, `${businessName}\n`);
  size(parts, 0x00);
  for (const businessLine of businessLines) {
    text(parts, `${businessLine}\n`);
  }
  text(parts, solidLine(columns));
  align(parts, 0);
  text(parts, twoCol('Bill', order.billNo, columns));
  bold(parts, true);
  text(parts, twoCol('KOT', order.kotNo || '', columns));
  bold(parts, false);
  text(parts, twoCol('Payment', order.paymentMethod || 'Cash', columns));
  text(parts, twoCol('Date', formatDateTime(new Date(order.createdAt || Date.now())), columns));
  text(parts, twoCol('Order', `${order.orderType}${order.table ? ` / ${order.table}` : ''}`, columns));
  if (order.customer) {
    text(parts, twoCol('Name', order.customer, columns));
  }
  if (order.customerPhone) {
    text(parts, twoCol('Phone', order.customerPhone, columns));
  }
  if (order.customerAddress) {
    text(parts, twoCol('Address', order.customerAddress, columns));
  }
  text(parts, twoCol('Staff', staffName, columns));
  text(parts, solidLine(columns));

  bold(parts, true);
  text(parts, receiptItemRow('Item', 'Qty', 'Price', 'Amount', columns));
  bold(parts, false);

  for (const item of order.items) {
    const itemWidth = columns <= 28 ? 9 : 15;
    const itemNameLines = wrapText(item.name, itemWidth);
    text(parts, receiptItemRow(itemNameLines[0], formatQty(item.qty), compactMoney(item.price), compactMoney(item.total), columns));
    for (const wrapped of itemNameLines.slice(1)) {
      text(parts, `${wrapped}\n`);
    }
  }

  text(parts, receiptItemRow('Total Qty', formatQty(totalQuantity), '', '', columns));
  text(parts, twoCol('Subtotal', money(taxableSubtotal), columns));
  if (order.discount > 0) {
    text(parts, twoCol('Discount', `-${money(order.discount)}`, columns));
  }
  for (const row of taxBreakdown) {
    const rateText = row.rate > 0 ? ` @${formatRate(row.rate)}%` : '';
    const halfRateText = row.rate > 0 ? ` @${formatRate(row.rate / 2)}%` : '';
    text(parts, twoCol(`GST${rateText}`, money(row.amount), columns));
    text(parts, twoCol(`CGST${halfRateText}`, money(row.amount / 2), columns));
    text(parts, twoCol(`SGST${halfRateText}`, money(row.amount / 2), columns));
  }
  if (order.serviceCharge > 0) {
    text(parts, twoCol('Service Charge', money(order.serviceCharge), columns));
  }
  if (roundOff !== 0) {
    text(parts, twoCol('Round Off', `${roundOff > 0 ? '+' : ''}${money(roundOff)}`, columns));
  }
  text(parts, solidLine(columns));
  bold(parts, true);
  align(parts, 1);
  size(parts, 0x11);
  text(parts, 'TOTAL\n');
  text(parts, `${money(order.total)}\n`);
  size(parts, 0x00);
  align(parts, 0);
  bold(parts, false);
  text(parts, solidLine(columns));
  align(parts, 1);
  text(parts, `${footerNote}\n${payload?.settings?.paperWidth === '58' ? '\n' : '\n\n'}`);
  push(parts, [0x1d, 0x56, 0x42, 0x00]);

  return Buffer.concat(parts);
}

function buildEscPosKot(payload) {
  const kot = payload.kot || {};
  const columns = getEscPosColumns(payload?.settings);
  const parts = [];
  const staffName = kot.serviceStaffName || kot.cashier || 'Admin';

  push(parts, [0x1b, 0x40]);
  align(parts, 1);
  size(parts, 0x11);
  text(parts, 'KOT\n');
  size(parts, 0x00);
  bold(parts, true);
  text(parts, `${kot.station || 'Kitchen'}\n`);
  bold(parts, false);
  text(parts, line(columns));
  align(parts, 0);
  text(parts, twoCol('Bill', kot.billNo || '', columns));
  bold(parts, true);
  text(parts, twoCol('KOT', kot.kotNo || '', columns));
  bold(parts, false);
  text(parts, twoCol('Date', formatDateTime(new Date(kot.createdAt || Date.now())), columns));
  text(parts, twoCol('Order', `${kot.orderType || ''}${kot.table ? ` / ${kot.table}` : ''}`, columns));
  if (kot.customer) {
    text(parts, twoCol('Name', kot.customer, columns));
  }
  text(parts, twoCol('Staff', staffName, columns));
  text(parts, line(columns));

  for (const item of kot.items || []) {
    bold(parts, true);
    text(parts, `${formatQty(item.qty)} x ${item.name}\n`);
    bold(parts, false);
    if (item.description) {
      for (const wrapped of wrapText(`Note: ${item.description}`, columns)) {
        text(parts, wrapped + '\n');
      }
    }
  }

  if (kot.note) {
    text(parts, line(columns));
    bold(parts, true);
    text(parts, 'KOT NOTE\n');
    bold(parts, false);
    for (const wrapped of wrapText(kot.note, columns)) {
      text(parts, wrapped + '\n');
    }
  }

  text(parts, line(columns));
  align(parts, 1);
  text(parts, 'Kitchen Order Ticket\n\n\n');
  push(parts, [0x1d, 0x56, 0x42, 0x00]);

  return Buffer.concat(parts);
}

function buildEscPosReport(payload) {
  const report = payload.report || {};
  const business = report.business || {};
  const businessName = business.name || 'Restaurant';
  const columns = getEscPosColumns(payload?.settings);
  const parts = [];
  const options = report.printOptions || {};
  const isEnabled = (key) => options[key] !== false;

  push(parts, [0x1b, 0x40]);
  align(parts, 1);
  size(parts, 0x11);
  text(parts, `${businessName}\n`);
  size(parts, 0x00);
  bold(parts, true);
  text(parts, `${report.title || 'Sales Report'}\n`);
  bold(parts, false);
  text(parts, `${report.periodLabel || ''}\n`);
  text(parts, `${formatDateTime(new Date(report.generatedAt || Date.now()))}\n`);
  text(parts, line(columns));
  align(parts, 0);
  const reportRows = [
    ['showTotalSales', 'Total Sales', money(report.salesTotal), true],
    ['showAmountReceived', 'Amount Received', money(report.receivedTotal), false],
    ['showPaidBills', 'Paid Bills', String(Number(report.paidCount || 0)), false],
    ['showOpeningCash', 'Opening Cash', money(report.openingCash), false],
    ['showCashInHand', 'Cash In Hand', money(report.cashInHand), false],
    ['showBank', 'Bank', money(report.bankTotal), false],
    ['showDueCredit', 'Due / Credit', money(report.balanceTotal), false],
    ['showDiscount', 'Discount', money(report.discountTotal), false],
    ['showExpenses', 'Expense', money(report.expenseTotal), false],
    ['showNetAmount', 'Net Amount', money(report.netTotal), true],
    ['showOpenBills', 'Open Amount', money(report.openTotal), false],
    ['showOpenBills', 'Open Bills', String(Number(report.openCount || 0)), false],
  ];
  for (const [key, label, value, emphasized] of reportRows) {
    if (!isEnabled(key)) continue;
    bold(parts, emphasized);
    text(parts, twoCol(label, value, columns));
    bold(parts, false);
  }

  if (isEnabled('showPaymentSummary') && (report.paymentRows || []).length) {
    text(parts, line(columns));
    bold(parts, true);
    text(parts, 'Payment Summary\n');
    bold(parts, false);
    for (const row of report.paymentRows) {
      text(parts, twoCol(row.label, money(row.amount), columns));
    }
  }

  if (isEnabled('showOrderTypes')) {
    text(parts, line(columns));
    bold(parts, true);
    text(parts, 'Order Type\n');
    bold(parts, false);
    for (const row of report.orderTypeRows || []) {
      text(parts, twoCol(`${row.label} x ${Number(row.count || 0)}`, money(row.total), columns));
    }
  }

  if (isEnabled('showItemSummary')) {
    text(parts, line(columns));
    bold(parts, true);
    text(parts, 'Item Summary\n');
    bold(parts, false);
    for (const row of report.itemRows || []) {
      text(parts, twoCol(`${row.label} x ${Number(row.count || 0)}`, money(row.total), columns));
    }
  }

  text(parts, solidLine(columns));
  align(parts, 1);
  text(parts, 'End of report\n\n\n');
  push(parts, [0x1d, 0x56, 0x42, 0x00]);

  return Buffer.concat(parts);
}

function escapeHtml(value) {
  return String(value ?? '')
    .replaceAll('&', '&amp;')
    .replaceAll('<', '&lt;')
    .replaceAll('>', '&gt;')
    .replaceAll('"', '&quot;')
    .replaceAll("'", '&#039;');
}

function money(value) {
  return `${CURRENCY} ${Number(value || 0).toFixed(2)}`;
}

function formatQty(value) {
  return Number(value || 0).toFixed(0);
}

function formatDateTime(value) {
  return value.toLocaleString('en-IN', {
    day: '2-digit',
    month: '2-digit',
    year: 'numeric',
    hour: '2-digit',
    minute: '2-digit',
  });
}

function push(parts, bytes) {
  parts.push(Buffer.from(bytes));
}

function text(parts, value) {
  parts.push(Buffer.from(toEscPosText(value), 'ascii'));
}

function ascii(value) {
  return String(value ?? '').replace(/[^\x20-\x7E\n\r]/g, '');
}

function toEscPosText(value) {
  return ascii(value)
    .replace(/\r\n/g, '\n')
    .replace(/\r/g, '\n')
    .replace(/\n/g, '\r\n');
}

function align(parts, value) {
  push(parts, [0x1b, 0x61, value]);
}

function bold(parts, enabled) {
  push(parts, [0x1b, 0x45, enabled ? 1 : 0]);
}

function size(parts, value) {
  push(parts, [0x1d, 0x21, value]);
}

function line(columns) {
  return `${'-'.repeat(columns)}\n`;
}

function solidLine(columns) {
  return `${'_'.repeat(columns)}\n`;
}

function twoCol(left, right, columns) {
  const safeLeft = ascii(left);
  const safeRight = ascii(right);
  const width = Math.max(1, columns - safeRight.length);
  const trimmedLeft = safeLeft.length > width ? safeLeft.slice(0, width - 1) : safeLeft;
  return `${trimmedLeft}${' '.repeat(Math.max(1, columns - trimmedLeft.length - safeRight.length))}${safeRight}\n`;
}

function buildReceiptTaxBreakdown(order = {}) {
  const items = Array.isArray(order.items) ? order.items : [];
  const gstType = order.gstType === 'inclusive' ? 'inclusive' : 'exclusive';
  const rowsByRate = new Map();

  for (const item of items) {
    const rate = Math.max(0, Number(item.taxRate || 0));
    if (!rate) continue;

    const lineTotal = Number(item.total || 0);
    const amount = gstType === 'inclusive' ? (lineTotal * rate) / (100 + rate) : (lineTotal * rate) / 100;
    rowsByRate.set(rate, (rowsByRate.get(rate) || 0) + amount);
  }

  const rows = [...rowsByRate.entries()]
    .map(([rate, amount]) => ({ rate, amount: roundReceiptMoney(amount) }))
    .filter((row) => row.amount > 0)
    .sort((a, b) => a.rate - b.rate);

  const savedTax = roundReceiptMoney(order.tax || 0);
  if (!savedTax) return [];
  if (!rows.length) return [{ rate: 0, amount: savedTax }];

  const calculatedTax = roundReceiptMoney(rows.reduce((sum, row) => sum + row.amount, 0));
  const difference = roundReceiptMoney(savedTax - calculatedTax);
  if (difference !== 0) {
    rows[rows.length - 1].amount = roundReceiptMoney(rows[rows.length - 1].amount + difference);
  }

  return rows.filter((row) => row.amount > 0);
}

function roundReceiptMoney(value) {
  return Math.round(Number(value || 0) * 100) / 100;
}

function formatRate(value) {
  const rounded = Math.round(Number(value || 0) * 100) / 100;
  return Number.isInteger(rounded) ? String(rounded) : rounded.toFixed(2).replace(/0+$/, '').replace(/\.$/, '');
}

function receiptItemRow(item, qty, price, amount, columns) {
  const widths = columns <= 28 ? [9, 3, 7, 9] : [15, 4, 9, 12];
  const values = [item, qty, price, amount].map((value) => ascii(value));
  return values
    .map((value, index) => {
      const width = widths[index];
      const clipped = value.slice(0, width);
      return index === 0 ? clipped.padEnd(width) : clipped.padStart(width);
    })
    .join('') + '\n';
}

function compactMoney(value) {
  return Number(value || 0).toFixed(2);
}

function wrapText(value, width) {
  const words = ascii(value).split(/\s+/).filter(Boolean);
  const lines = [];
  let lineText = '';

  for (const word of words) {
    const next = lineText ? `${lineText} ${word}` : word;
    if (next.length > width) {
      if (lineText) {
        lines.push(lineText);
      }
      lineText = word;
    } else {
      lineText = next;
    }
  }

  if (lineText) {
    lines.push(lineText);
  }

  return lines.length ? lines : [''];
}

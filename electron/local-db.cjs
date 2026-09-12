const fs = require('node:fs');
const path = require('node:path');
const crypto = require('node:crypto');
const { DatabaseSync } = require('node:sqlite');

const DB_FILE_NAME = 'gi-pos-local.sqlite';
const DATA_DIR_NAME = 'GIPOS Restaurant';
const BACKUP_DIR_NAME = 'Backups';
const MAX_AUTO_BACKUPS = 8;
const MAX_MANUAL_BACKUPS = 10;
const AUTO_BACKUP_MIN_INTERVAL_MS = 12 * 60 * 60 * 1000;
const MIN_FREE_PAGES_FOR_COMPACTION = 256;
const COMPACTION_FREE_PAGE_RATIO = 0.2;
const SYNCABLE_KV_KEYS = new Set([
  'pos-business-profile',
  'pos-categories',
  'pos-customers',
  'pos-expenses',
  'pos-menu-items',
  'pos-opening-cash-balances',
  'pos-orders',
  'pos-app-configuration',
  'pos-service-staff',
  'pos-staff-users',
  'pos-staff-user-directory',
  'pos-dining-table-groups',
]);

async function createLocalDatabase(app) {
  const dataDir = getPersistentDataDir(app);
  const backupDir = path.join(dataDir, BACKUP_DIR_NAME);
  const dbPath = path.join(dataDir, DB_FILE_NAME);
  fs.mkdirSync(dataDir, { recursive: true });
  fs.mkdirSync(backupDir, { recursive: true });
  migrateLegacyDatabase(app, dbPath, backupDir);

  let db = enableNativeCompatibility(openDatabaseWithBackupFallback(dbPath, backupDir));
  const previousUserVersion = getPragmaNumber(db, 'user_version');
  if (previousUserVersion < 2 && fs.existsSync(dbPath)) {
    createDatabaseBackup(dbPath, backupDir, 'before-native-upgrade');
  }

  db.exec(`
    PRAGMA journal_mode = WAL;
    PRAGMA synchronous = NORMAL;
    PRAGMA busy_timeout = 5000;
    PRAGMA foreign_keys = ON;
    PRAGMA user_version = 2;

    CREATE TABLE IF NOT EXISTS app_kv (
      key TEXT PRIMARY KEY,
      value TEXT NOT NULL,
      updated_at TEXT NOT NULL,
      sync_status TEXT NOT NULL DEFAULT 'pending',
      server_id TEXT
    );

    CREATE TABLE IF NOT EXISTS sync_outbox (
      id TEXT PRIMARY KEY,
      entity_type TEXT NOT NULL,
      entity_id TEXT NOT NULL,
      operation TEXT NOT NULL,
      payload TEXT NOT NULL,
      created_at TEXT NOT NULL,
      synced_at TEXT
    );

    CREATE TABLE IF NOT EXISTS orders_v2 (
      id TEXT PRIMARY KEY,
      bill_number INTEGER NOT NULL DEFAULT 0,
      status TEXT NOT NULL DEFAULT '',
      order_type TEXT NOT NULL DEFAULT '',
      table_name TEXT NOT NULL DEFAULT '',
      customer_id TEXT NOT NULL DEFAULT '',
      total REAL NOT NULL DEFAULT 0,
      created_at TEXT NOT NULL DEFAULT '',
      updated_at TEXT NOT NULL DEFAULT '',
      payload TEXT NOT NULL
    );

    CREATE INDEX IF NOT EXISTS idx_orders_v2_created_at ON orders_v2 (created_at);
    CREATE INDEX IF NOT EXISTS idx_orders_v2_bill_number ON orders_v2 (bill_number);
    CREATE INDEX IF NOT EXISTS idx_orders_v2_status_created ON orders_v2 (status, created_at);
    CREATE INDEX IF NOT EXISTS idx_orders_v2_customer ON orders_v2 (customer_id);

    CREATE INDEX IF NOT EXISTS idx_sync_outbox_pending_created
      ON sync_outbox (synced_at, created_at);

    CREATE INDEX IF NOT EXISTS idx_sync_outbox_entity_pending
      ON sync_outbox (entity_type, entity_id, synced_at);
  `);

  migrateOrdersFromLegacyValue(db);
  pruneLegacyOutbox(db);
  compactDatabaseIfNeeded(db);

  return {
    path: dbPath,
    dataDir,
    backupDir,
    getSnapshot() {
      const values = {};
      const rows = db.prepare('SELECT key, value FROM app_kv ORDER BY key').all();

      for (const row of rows) {
        values[String(row.key)] = String(row.value ?? '');
      }
      values['pos-orders'] = serializeStoredOrders(db, values['pos-orders']);

      return {
        engine: 'sqlite',
        path: dbPath,
        dataDir,
        backupDir,
        values,
      };
    },
    setValue(key, value) {
      if (key === 'pos-orders') {
        return replaceStoredOrders(db, value);
      }

      const existing = getStoredValue(db, key);
      if (existing && existing.value === value) {
        return { ok: true, key, updatedAt: existing.updatedAt, skipped: true };
      }

      const now = new Date().toISOString();
      const payload = JSON.stringify({ key, value, updatedAt: now });
      db.run(
        `
          INSERT INTO app_kv (key, value, updated_at, sync_status)
          VALUES (?, ?, ?, 'pending')
          ON CONFLICT(key) DO UPDATE SET
            value = excluded.value,
            updated_at = excluded.updated_at,
            sync_status = 'pending'
        `,
        [key, value, now],
      );
      if (shouldSyncKey(key)) {
        insertOutboxEvent(db, 'app_kv', key, 'upsert', payload, now);
      }
      persistDatabase(db, dbPath);

      return { ok: true, key, updatedAt: now };
    },
    setMany(entries) {
      const now = new Date().toISOString();
      const latestEntries = new Map();
      for (const entry of entries) {
        latestEntries.set(entry.key, entry.value);
      }
      const changedEntries = [...latestEntries.entries()]
        .map(([key, value]) => ({ key, value, existing: getStoredValue(db, key) }))
        .filter((entry) => !entry.existing || entry.existing.value !== entry.value);

      if (!changedEntries.length) {
        return { ok: true, count: 0, updatedAt: now, skipped: true };
      }

      db.run('BEGIN TRANSACTION');

      try {
        const statement = db.prepare(`
          INSERT INTO app_kv (key, value, updated_at, sync_status)
          VALUES (?, ?, ?, 'pending')
          ON CONFLICT(key) DO UPDATE SET
            value = excluded.value,
            updated_at = excluded.updated_at,
            sync_status = 'pending'
        `);

        for (const entry of changedEntries) {
          statement.run(entry.key, entry.value, now);
          if (shouldSyncKey(entry.key)) {
            insertOutboxEvent(
              db,
              'app_kv',
              entry.key,
              'upsert',
              JSON.stringify({ key: entry.key, value: entry.value, updatedAt: now }),
              now,
            );
          }
        }

        db.run('COMMIT');
      } catch (error) {
        db.run('ROLLBACK');
        throw error;
      }

      persistDatabase(db, dbPath);

      return { ok: true, count: changedEntries.length, updatedAt: now };
    },
    applyRemoteValues(entries) {
      const cleanEntries = Array.isArray(entries)
        ? entries.filter((entry) => entry && typeof entry.key === 'string')
        : [];
      if (!cleanEntries.length) {
        return { ok: true, count: 0, updatedAt: new Date().toISOString() };
      }

      const now = new Date().toISOString();
      const remoteOrders = cleanEntries.find((entry) => entry.key === 'pos-orders');
      const keyValueEntries = cleanEntries.filter((entry) => entry.key !== 'pos-orders');

      if (remoteOrders) {
        replaceStoredOrders(db, String(remoteOrders.value ?? ''), remoteOrders.updatedAt || now, false);
      }

      if (!keyValueEntries.length) {
        return { ok: true, count: remoteOrders ? 1 : 0, updatedAt: now };
      }

      db.run('BEGIN TRANSACTION');

      try {
        const statement = db.prepare(`
          INSERT INTO app_kv (key, value, updated_at, sync_status)
          VALUES (?, ?, ?, 'synced')
          ON CONFLICT(key) DO UPDATE SET
            value = excluded.value,
            updated_at = excluded.updated_at,
            sync_status = 'synced'
        `);

        for (const entry of keyValueEntries) {
          statement.run(entry.key, String(entry.value ?? ''), entry.updatedAt || now);
        }

        db.run('COMMIT');
      } catch (error) {
        db.run('ROLLBACK');
        throw error;
      }

      persistDatabase(db, dbPath);

      return { ok: true, count: keyValueEntries.length + (remoteOrders ? 1 : 0), updatedAt: now };
    },
    getPendingSync(limit = 100) {
      const safeLimit = Math.max(1, Math.min(Number(limit) || 100, 500));
      const rows = db.prepare(`
        SELECT id, entity_type, entity_id, operation, payload, created_at
        FROM sync_outbox
        WHERE synced_at IS NULL
        ORDER BY created_at ASC
        LIMIT ?
      `).all(safeLimit);
      const changes = rows.map((row) => ({
          id: String(row.id),
          entityType: String(row.entity_type),
          entityId: String(row.entity_id),
          operation: String(row.operation),
          payload: parseJson(row.payload, {}),
          createdAt: String(row.created_at),
        }));

      return { ok: true, changes };
    },
    markSynced(ids) {
      const syncIds = Array.isArray(ids) ? ids.filter(Boolean).map(String) : [];
      if (!syncIds.length) {
        return { ok: true, count: 0, updatedAt: new Date().toISOString() };
      }

      const now = new Date().toISOString();
      const placeholders = syncIds.map(() => '?').join(', ');
      db.run(`DELETE FROM sync_outbox WHERE id IN (${placeholders})`, syncIds);
      persistDatabase(db, dbPath);

      return { ok: true, count: syncIds.length, updatedAt: now };
    },
    clearPendingSync() {
      const now = new Date().toISOString();
      db.run('DELETE FROM sync_outbox WHERE synced_at IS NULL');
      persistDatabase(db, dbPath);

      return { ok: true, updatedAt: now };
    },
    resetAll() {
      const now = new Date().toISOString();
      checkpointDatabase(db);
      createDatabaseBackup(dbPath, backupDir, 'before-reset');
      db.run('BEGIN TRANSACTION');

      try {
        db.run('DELETE FROM sync_outbox');
        db.run('DELETE FROM orders_v2');
        db.run('DELETE FROM app_kv');
        db.run('COMMIT');
      } catch (error) {
        db.run('ROLLBACK');
        throw error;
      }

      persistDatabase(db, dbPath);

      return { ok: true, updatedAt: now };
    },
    createBackup() {
      checkpointDatabase(db);
      const backup = createDatabaseBackup(dbPath, backupDir, 'manual');
      return { ok: true, path: backup.path, fileName: backup.fileName, updatedAt: new Date().toISOString() };
    },
    listBackups() {
      pruneBackups(backupDir);
      return { ok: true, backups: listDatabaseBackups(backupDir) };
    },
    restoreBackup(fileName) {
      const backupPath = resolveBackupPath(backupDir, fileName);
      if (!backupPath) {
        throw new Error('Backup file not found');
      }

      createDatabaseBackup(dbPath, backupDir, 'before-restore');
      checkpointDatabase(db);
      db.close();
      fs.copyFileSync(backupPath, dbPath);
      db = enableNativeCompatibility(openDatabaseWithBackupFallback(dbPath, backupDir));

      return { ok: true, path: dbPath, restoredFrom: backupPath, updatedAt: new Date().toISOString() };
    },
    close() {
      checkpointDatabase(db);
      createDatabaseBackup(dbPath, backupDir, 'close');
      db.close();
    },
  };
}

function pruneLegacyOutbox(db) {
  const before = getTableRowCount(db, 'sync_outbox');
  if (!before) {
    return false;
  }

  // Confirmed changes already live in the cloud, while older unsynced snapshots
  // are replaced by the latest snapshot for the same key-value entity.
  db.run('DELETE FROM sync_outbox WHERE synced_at IS NOT NULL');
  db.run(`
    DELETE FROM sync_outbox
    WHERE synced_at IS NULL
      AND rowid NOT IN (
        SELECT latest_rowid
        FROM (
          SELECT MAX(rowid) AS latest_rowid
          FROM sync_outbox
          WHERE synced_at IS NULL
          GROUP BY entity_type, entity_id
        )
      )
  `);

  return getTableRowCount(db, 'sync_outbox') !== before;
}

function compactDatabaseIfNeeded(db) {
  const pageCount = getPragmaNumber(db, 'page_count');
  const freePageCount = getPragmaNumber(db, 'freelist_count');
  const shouldCompact =
    freePageCount >= MIN_FREE_PAGES_FOR_COMPACTION &&
    pageCount > 0 &&
    freePageCount / pageCount >= COMPACTION_FREE_PAGE_RATIO;

  if (!shouldCompact) {
    return false;
  }

  db.run('VACUUM');
  return true;
}

function getPragmaNumber(db, pragma) {
  const row = db.prepare(`PRAGMA ${pragma}`).get();
  return Number(row?.[pragma] || 0);
}

function getTableRowCount(db, tableName) {
  const row = db.prepare(`SELECT COUNT(*) AS count FROM ${tableName}`).get();
  return Number(row?.count || 0);
}

function migrateOrdersFromLegacyValue(db) {
  const legacy = getStoredValue(db, 'pos-orders');
  if (!legacy) {
    return false;
  }

  const migrated = replaceStoredOrders(db, legacy.value, legacy.updatedAt, false);
  if (migrated.ok) {
    db.run('DELETE FROM app_kv WHERE key = ?', ['pos-orders']);
  }
  return migrated.ok;
}

function replaceStoredOrders(db, serializedOrders, updatedAt = new Date().toISOString(), queueForSync = true) {
  const orders = parseJson(serializedOrders, null);
  if (!Array.isArray(orders)) {
    throw new Error('Saved orders data is invalid');
  }

  const normalizedOrders = orders.filter((order) => order && typeof order === 'object' && String(order.id || '').trim());
  const existingRows = db.prepare('SELECT id, payload FROM orders_v2').all();
  const existingPayloads = new Map(existingRows.map((row) => [String(row.id), String(row.payload)]));
  const nextIds = new Set(normalizedOrders.map((order) => String(order.id)));
  let changed = false;

  db.run('BEGIN IMMEDIATE');
  try {
    for (const order of normalizedOrders) {
      const id = String(order.id);
      const payload = JSON.stringify(order);
      if (existingPayloads.get(id) === payload) {
        continue;
      }

      db.run(
        `
          INSERT INTO orders_v2 (
            id, bill_number, status, order_type, table_name, customer_id,
            total, created_at, updated_at, payload
          ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
          ON CONFLICT(id) DO UPDATE SET
            bill_number = excluded.bill_number,
            status = excluded.status,
            order_type = excluded.order_type,
            table_name = excluded.table_name,
            customer_id = excluded.customer_id,
            total = excluded.total,
            created_at = excluded.created_at,
            updated_at = excluded.updated_at,
            payload = excluded.payload
        `,
        [
          id,
          Number(order.billNumber || 0),
          String(order.status || ''),
          String(order.orderType || ''),
          String(order.tableName || order.table?.name || ''),
          String(order.customer?.id || order.customerId || ''),
          Number(order.totals?.total || order.total || 0),
          String(order.createdAt || updatedAt),
          String(order.updatedAt || updatedAt),
          payload,
        ],
      );
      changed = true;
    }

    for (const row of existingRows) {
      if (!nextIds.has(String(row.id))) {
        db.run('DELETE FROM orders_v2 WHERE id = ?', [String(row.id)]);
        changed = true;
      }
    }

    db.run('COMMIT');
  } catch (error) {
    db.run('ROLLBACK');
    throw error;
  }

  if (queueForSync && changed) {
    insertOutboxEvent(
      db,
      'app_kv',
      'pos-orders',
      'upsert',
      JSON.stringify({ key: 'pos-orders', value: JSON.stringify(normalizedOrders), updatedAt }),
      updatedAt,
    );
  }

  return { ok: true, key: 'pos-orders', updatedAt, skipped: !changed };
}

function serializeStoredOrders(db, legacyValue = '') {
  const rows = db.prepare('SELECT payload FROM orders_v2 ORDER BY created_at DESC, bill_number DESC').all();
  if (rows.length) {
    return JSON.stringify(rows.map((row) => parseJson(row.payload, {})));
  }

  const legacy = parseJson(legacyValue, []);
  return JSON.stringify(Array.isArray(legacy) ? legacy : []);
}

function persistDatabase(db, dbPath) {
  void db;
  void dbPath;
  // Native SQLite durably writes each transaction to its WAL. Full database
  // exports are intentionally avoided because they delay POS printing.
}

function getPersistentDataDir(app) {
  if (process.env.GI_POS_DATA_DIR) {
    return path.resolve(process.env.GI_POS_DATA_DIR);
  }

  if (process.platform === 'win32') {
    return path.join('C:\\', DATA_DIR_NAME);
  }

  return path.join(app.getPath('documents'), DATA_DIR_NAME);
}

function migrateLegacyDatabase(app, dbPath, backupDir) {
  if (fs.existsSync(dbPath)) {
    return;
  }

  const legacyPath = path.join(app.getPath('userData'), 'data', DB_FILE_NAME);
  if (!fs.existsSync(legacyPath)) {
    const latestBackup = listDatabaseBackups(backupDir)[0];
    if (latestBackup) {
      fs.copyFileSync(latestBackup.path, dbPath);
    }
    return;
  }

  fs.copyFileSync(legacyPath, dbPath);
  createDatabaseBackup(dbPath, backupDir, 'legacy-migrated');
}

function openDatabaseWithBackupFallback(dbPath, backupDir) {
  if (fs.existsSync(dbPath)) {
    try {
      const database = new DatabaseSync(dbPath);
      const integrity = database.prepare('PRAGMA integrity_check').get();
      if (String(integrity?.integrity_check || '').toLowerCase() !== 'ok') {
        throw new Error('SQLite integrity check failed');
      }
      return database;
    } catch {
      createCorruptCopy(dbPath, backupDir);
    }
  }

  const latestBackup = listDatabaseBackups(backupDir)[0];
  if (latestBackup) {
    fs.copyFileSync(latestBackup.path, dbPath);
    return new DatabaseSync(dbPath);
  }

  return new DatabaseSync(dbPath);
}

function enableNativeCompatibility(db) {
  db.run = (sql, params = []) => db.prepare(sql).run(...(Array.isArray(params) ? params : [params]));
  return db;
}

function checkpointDatabase(db) {
  try {
    db.prepare('PRAGMA wal_checkpoint(TRUNCATE)').get();
  } catch {
    // A backup is still attempted; SQLite keeps the committed WAL data safe.
  }
}

function createDatabaseBackup(dbPath, backupDir, reason) {
  if (!fs.existsSync(dbPath)) {
    return { path: '', fileName: '' };
  }

  fs.mkdirSync(backupDir, { recursive: true });
  const safeReason = String(reason || 'backup').replace(/[^a-z0-9-]/gi, '-').toLowerCase();
  const recentBackup = getRecentAutomaticBackup(backupDir, safeReason);

  if (recentBackup) {
    pruneBackups(backupDir);
    return { path: recentBackup.path, fileName: recentBackup.fileName, skipped: true };
  }

  const stamp = new Date().toISOString().replace(/[-:]/g, '').replace(/\..+$/, '').replace('T', '-');
  const fileName = `gi-pos-${safeReason}-${stamp}.sqlite`;
  const backupPath = path.join(backupDir, fileName);
  fs.copyFileSync(dbPath, backupPath);
  pruneBackups(backupDir);
  return { path: backupPath, fileName };
}

function getRecentAutomaticBackup(backupDir, reason) {
  if (reason !== 'startup' && reason !== 'close') {
    return null;
  }

  const latestAutoBackup = listDatabaseBackups(backupDir).find(
    (backup) => !isManualBackup(backup.fileName) && !isCorruptBackup(backup.fileName),
  );

  if (!latestAutoBackup) {
    return null;
  }

  const ageMs = Date.now() - new Date(latestAutoBackup.updatedAt).getTime();
  return ageMs >= 0 && ageMs < AUTO_BACKUP_MIN_INTERVAL_MS ? latestAutoBackup : null;
}

function createCorruptCopy(dbPath, backupDir) {
  try {
    fs.mkdirSync(backupDir, { recursive: true });
    const stamp = new Date().toISOString().replace(/[-:]/g, '').replace(/\..+$/, '').replace('T', '-');
    fs.copyFileSync(dbPath, path.join(backupDir, `gi-pos-corrupt-${stamp}.sqlite`));
  } catch {
    // Keep startup moving; the backup fallback handles recovery.
  }
}

function listDatabaseBackups(backupDir) {
  if (!fs.existsSync(backupDir)) {
    return [];
  }

  return fs
    .readdirSync(backupDir)
    .filter((fileName) => fileName.toLowerCase().endsWith('.sqlite'))
    .map((fileName) => {
      const filePath = path.join(backupDir, fileName);
      const stat = fs.statSync(filePath);
      return {
        fileName,
        path: filePath,
        size: stat.size,
        createdAt: stat.birthtime.toISOString(),
        updatedAt: stat.mtime.toISOString(),
      };
    })
    .sort((first, second) => new Date(second.updatedAt).getTime() - new Date(first.updatedAt).getTime());
}

function pruneBackups(backupDir) {
  pruneBackupList(
    listDatabaseBackups(backupDir).filter((backup) => !isManualBackup(backup.fileName) && !isCorruptBackup(backup.fileName)),
    MAX_AUTO_BACKUPS,
  );
  pruneBackupList(
    listDatabaseBackups(backupDir).filter((backup) => isManualBackup(backup.fileName)),
    MAX_MANUAL_BACKUPS,
  );
}

function pruneBackupList(backups, maxCount) {
  backups.slice(maxCount).forEach((backup) => {
    try {
      fs.unlinkSync(backup.path);
    } catch {
      // Old backup cleanup is best effort only.
    }
  });
}

function isManualBackup(fileName) {
  return String(fileName || '').toLowerCase().includes('-manual-');
}

function isCorruptBackup(fileName) {
  return String(fileName || '').toLowerCase().includes('-corrupt-');
}

function resolveBackupPath(backupDir, fileName) {
  const safeName = path.basename(String(fileName || ''));
  const backupRoot = path.resolve(backupDir);
  const backupPath = path.resolve(backupRoot, safeName);
  const isInsideBackupDir = backupPath === backupRoot || backupPath.startsWith(`${backupRoot}${path.sep}`);
  return isInsideBackupDir && fs.existsSync(backupPath) ? backupPath : '';
}

function insertOutboxEvent(db, entityType, entityId, operation, payload, createdAt) {
  // Keep only the newest unsynced snapshot for each entity. A later change fully
  // supersedes an older key-value snapshot, so retaining both only slows sync.
  db.run(
    `DELETE FROM sync_outbox
     WHERE entity_type = ? AND entity_id = ? AND synced_at IS NULL`,
    [entityType, entityId],
  );
  db.run(
    `
      INSERT INTO sync_outbox (id, entity_type, entity_id, operation, payload, created_at)
      VALUES (?, ?, ?, ?, ?, ?)
    `,
    [crypto.randomUUID(), entityType, entityId, operation, payload, createdAt],
  );
}

function getStoredValue(db, key) {
  const existing = db.prepare(`
    SELECT value, updated_at
    FROM app_kv
    WHERE key = ?
    LIMIT 1
  `).get(key);

  if (!existing) {
    return null;
  }

  return {
    value: String(existing.value ?? ''),
    updatedAt: String(existing.updated_at ?? ''),
  };
}

function shouldSyncKey(key) {
  return SYNCABLE_KV_KEYS.has(key);
}

function parseJson(value, fallback) {
  try {
    return JSON.parse(String(value ?? ''));
  } catch {
    return fallback;
  }
}

function registerLocalDatabaseHandlers(ipcMain, getDatabase) {
  ipcMain.handle('db:load', async () => getDatabase().getSnapshot());
  ipcMain.handle('db:set', async (_event, key, value) => getDatabase().setValue(String(key), String(value ?? '')));
  ipcMain.handle('db:set-many', async (_event, entries) => {
    const cleanEntries = Array.isArray(entries)
      ? entries
          .filter((entry) => entry && typeof entry.key === 'string')
          .map((entry) => ({ key: entry.key, value: String(entry.value ?? '') }))
      : [];

    return getDatabase().setMany(cleanEntries);
  });
  ipcMain.handle('db:sync-pending', async (_event, limit) => getDatabase().getPendingSync(limit));
  ipcMain.handle('db:sync-mark-synced', async (_event, ids) => getDatabase().markSynced(ids));
  ipcMain.handle('db:sync-clear-pending', async () => getDatabase().clearPendingSync());
  ipcMain.handle('db:apply-remote-values', async (_event, entries) => getDatabase().applyRemoteValues(entries));
  ipcMain.handle('db:reset-all', async () => getDatabase().resetAll());
  ipcMain.handle('db:backup-create', async () => getDatabase().createBackup());
  ipcMain.handle('db:backup-list', async () => getDatabase().listBackups());
  ipcMain.handle('db:backup-restore', async (_event, fileName) => getDatabase().restoreBackup(String(fileName || '')));
}

module.exports = {
  createLocalDatabase,
  registerLocalDatabaseHandlers,
};

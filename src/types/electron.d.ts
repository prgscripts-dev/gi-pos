export type PrinterInfo = {
  name: string
  displayName: string
  isDefault: boolean
  status?: number
}

export type ReceiptPrinterSettings = {
  mode: 'system' | 'network'
  printMethod?: 'escpos' | 'driver'
  deviceName: string
  ipAddress: string
  port: string
  paperWidth: '58' | '80'
}

export type LocalDbSnapshot = {
  engine: 'sqlite'
  path: string
  dataDir?: string
  backupDir?: string
  values: Record<string, string>
}

export type LocalDbWriteResult = {
  ok: boolean
  key?: string
  count?: number
  updatedAt: string
}

export type LocalDbSyncChange = {
  id: string
  entityType: string
  entityId: string
  operation: string
  payload: unknown
  createdAt: string
}

export type LocalDbBackup = {
  fileName: string
  path: string
  size: number
  createdAt: string
  updatedAt: string
}

export type AppUpdateStatus = {
  state:
    | 'idle'
    | 'checking'
    | 'available'
    | 'downloading'
    | 'downloaded'
    | 'installing'
    | 'not-available'
    | 'disabled'
    | 'error'
  message: string
  version?: string
  latestVersion?: string
  updateUrl?: string
  updatedAt?: string
  percent?: number
  error?: string
}

export type LocalServerStatus = {
  enabled: boolean
  port: number
  host: string
  appName: string
  version: string
  computerName: string
  urls: string[]
  primaryUrl: string
  startedAt: string
  error: string
  dbPath: string
  connectedDevices?: Array<{
    userName: string
    deviceName: string
    deviceType: string
    connectedAt: number
    lastSeenAt: number
  }>
  discovery: {
    enabled: boolean
    name: string
    hostName: string
    serviceType: string
    state: 'advertising' | 'disabled' | 'unavailable'
    error: string
  }
}

export type FoodImageSearchResult = {
  ok: boolean
  dataUrl?: string
  title?: string
  sourceUrl?: string
  error?: string
}

declare global {
  interface Window {
    posPrinter?: {
      listPrinters: () => Promise<PrinterInfo[]>
      printReceipt: (payload: unknown) => Promise<{ ok: boolean; mode: string }>
      printReport: (payload: unknown) => Promise<{ ok: boolean; mode: string }>
      exportReportPdf: (payload: {
        html: string
        defaultFileName: string
      }) => Promise<{ ok: boolean; path?: string; canceled?: boolean }>
      printTest: (settings: ReceiptPrinterSettings) => Promise<{ ok: boolean; mode: string }>
      printKot: (payload: unknown) => Promise<{ ok: boolean; mode: string }>
      printKotTest: (payload: unknown) => Promise<{ ok: boolean; mode: string }>
    }
    posDb?: {
      load: () => Promise<LocalDbSnapshot>
      set: (key: string, value: string) => Promise<LocalDbWriteResult>
      setMany: (entries: Array<{ key: string; value: string }>) => Promise<LocalDbWriteResult>
      getPendingSync: (limit?: number) => Promise<{ ok: boolean; changes: LocalDbSyncChange[] }>
      markSynced: (ids: string[]) => Promise<LocalDbWriteResult>
      clearPendingSync: () => Promise<LocalDbWriteResult>
      applyRemoteValues: (
        entries: Array<{ key: string; value: string; updatedAt?: string }>,
      ) => Promise<LocalDbWriteResult>
      resetAll: () => Promise<LocalDbWriteResult>
      createBackup: () => Promise<{ ok: boolean; path: string; fileName: string; updatedAt: string }>
      listBackups: () => Promise<{ ok: boolean; backups: LocalDbBackup[] }>
      restoreBackup: (fileName: string) => Promise<{ ok: boolean; path: string; restoredFrom: string; updatedAt: string }>
    }
    posUpdater?: {
      getStatus: () => Promise<AppUpdateStatus>
      check: () => Promise<AppUpdateStatus>
      install: () => Promise<AppUpdateStatus>
      onStatus: (callback: (status: AppUpdateStatus) => void) => () => void
    }
    posServer?: {
      getStatus: () => Promise<LocalServerStatus>
      configureDiscovery: (settings: { enabled: boolean; name: string; hostName?: string }) => Promise<LocalServerStatus>
      stop: () => Promise<{ ok: boolean }>
    }
    posImages?: {
      searchFoodImage: (payload: {
        name: string
        category?: string
        tags?: string[]
        variant?: number
      }) => Promise<FoodImageSearchResult>
    }
  }
}

export {}

# GI POS Current System Workflow

This document describes the system as it exists at commit `310b0ab` before multi-branch implementation.

## 1. Current Product Components

### Windows Desktop POS

- Electron and React desktop application
- SQLite is the primary operational database
- Billing, KOT, tables, menus, customers, dues, expenses, reports, users, and printer settings work locally
- Cloud-enabled plans queue local changes and synchronize them to PostgreSQL
- Offline plan continues with local SQLite after activation

### Main PC Local POS Server

- Runs from the desktop application on the LAN
- Exposes authenticated local POS endpoints
- Supports multiple local users with their own PIN/login permissions
- Sessions include device identity and expiry
- Local browser/pilot clients use the Main PC data instead of creating an independent restaurant database

### Native Android POS

- Separate native Android project with local SQLite
- Android plan is platform-restricted and cannot activate the Windows application
- Supports offline-first billing after activation and cloud synchronization when internet is available
- Bluetooth/network printing and mobile POS modules are developed separately from the WebView pilot

### Android Pilot

- Lightweight native LAN client for the Main PC
- Uses Main PC users, permissions, tables, menu, orders, and printing routes
- Does not own an independent restaurant dataset

### Cloud Service

- Node.js HTTP API
- PostgreSQL storage
- Public signup, Client Portal, Cloud Admin, subscriptions, activation, device transfer, synchronization, downloads, and update hosting

## 2. Current Account and Tenant Model

```text
Account
`- Restaurant record(s)
   |- Subscription
   |- Devices
   |- Cloud KV data
   |- Sync events
   `- Pairing/transfer codes
```

PostgreSQL technically permits multiple restaurant rows under one account. However, there is no branch entity, branch selector, branch assignment, consolidated report system, or branch-aware operational workflow. Each restaurant currently behaves as an independent sync/subscription tenant.

## 3. Signup and Approval

1. Client signs up with business, owner, phone/email, and password.
2. Cloud creates a pending account and restaurant.
3. GI Cloud Admin approves the restaurant.
4. Admin selects Offline, Premium, Gold, or Android, sets expiry, and configures user capacity.
5. Cloud creates an active subscription.
6. Compatible Windows or Android applications may activate.

Plan/platform separation is enforced by the cloud. Android-only subscriptions cannot activate Windows, and Windows plans cannot activate the native Android plan unless the plan explicitly supports that platform.

## 4. User Capacity

- `subscriptions.max_users` controls active POS users for one restaurant tenant.
- `NULL` means Unlimited.
- A positive integer is a hard active-user limit.
- The owner counts as at least one active user.
- Cloud Admin configures the limit beside plan and expiry.
- Client Portal displays active usage and capacity.
- Cloud sync rejects an oversized staff directory without deleting existing cloud users.

This is an account/restaurant limit today; it is not branch-scoped.

## 5. Device Activation

1. Application sends account credentials and platform.
2. Cloud verifies account approval and active subscription.
3. Cloud verifies that the selected plan supports the requesting platform.
4. The application chooses the approved restaurant when applicable.
5. Cloud registers or transfers the device and returns device credentials plus subscription details.
6. The device stores its activation and uses device-authenticated sync afterward.

Offline activation returns a signed offline license. Cloud plans retain cloud synchronization and subscription checks.

## 6. Desktop Local Data Flow

1. UI changes are saved to SQLite first.
2. Syncable changes are added to `sync_outbox`.
3. Billing operations use local data and do not wait for the cloud.
4. Auto/manual sync pushes pending events when connectivity is available.
5. Accepted events are marked synced locally.
6. Pull sync applies newer cloud KV values and commands.

Key synchronized data includes menu, tables, customers, users, business configuration, bills/orders, dues, expenses, and related application settings.

## 7. Cloud Sync Storage

- `cloud_kv`: latest synchronized value per restaurant and key
- `sync_events`: event/audit stream used by push synchronization
- `devices`: device identity, platform, state, and last seen
- `subscriptions`: plan, status, expiry, device capacity, and user capacity

Synchronization is currently partitioned by `restaurant_id`. It has no `branch_id`.

## 8. Main PC and Local Users

1. Main PC starts the local POS server.
2. A LAN client connects using the Main PC address.
3. Client loads login options from the Main PC.
4. User authenticates with an allowed local POS account.
5. Main PC issues a session bound to device type/name.
6. Client reads tables/menu/orders and submits changes through authenticated endpoints.
7. Printing remains on the Main PC so configured restaurant printers are used.

The Main PC is the authority for local counters. Local clients should not keep an independent authoritative copy of the restaurant database.

## 9. Client Portal Today

The Client Portal currently provides:

- account and restaurant status
- plan, expiry, platform, active devices, and user-capacity usage
- Windows/Android application downloads where applicable
- device transfer/activation support
- portal password and recovery management
- POS user PIN reset after synchronized users are available

It does not currently provide operational sales, expense, due, tax, item, staff, or consolidated branch reports.

## 10. Cloud Admin Today

Cloud Admin currently provides:

- client search and status filtering
- approval, renewal, suspension, and portal-password reset
- plan and custom expiry selection
- per-client limited or Unlimited user capacity
- device and platform visibility
- Windows release upload and progress

It does not currently provide branch creation, branch limits, branch user assignments, or branch report access.

## 11. Reporting Today

- Detailed operational reports are generated from the local POS data.
- Reports support date/time periods, print, PDF, Excel, and CSV where implemented.
- Cloud PostgreSQL is primarily a synchronization/backup and account-control layer, not yet a normalized reporting warehouse.
- There is no All Branches report because branch identity has not been implemented.

## 12. Current Boundaries to Preserve

- SQLite remains authoritative for fast local desktop operations.
- PostgreSQL controls accounts, subscriptions, devices, and cloud synchronization.
- Local clients authenticate through the Main PC.
- Platform restrictions are enforced server-side.
- User limits are enforced server-side.
- Account changes must never reuse another account's SQLite or cloud tenant data.
- Printing and bill persistence must remain local-first and resilient to internet failure.

## 13. Gap Between Current and Planned Multi-Branch Model

The current `account -> restaurants` relationship is not sufficient by itself. Multi-branch implementation still requires:

- an explicit branch model and migration
- branch IDs on operational records and sync data
- branch-scoped sessions and permissions
- branch-aware devices and counters
- branch management and capacity controls
- normalized cloud report data
- branch and consolidated portal reports
- branch selection in Windows and Android applications

See `MULTI_BRANCH_PLAN.md` for the proposed implementation and acceptance criteria.

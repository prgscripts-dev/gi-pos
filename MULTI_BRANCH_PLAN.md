# GI POS Multi-Branch Plan

Status: Planned only. None of the changes in this document have been applied to the application or cloud database.

## Objective

Support one client account with multiple restaurant branches while keeping every branch operationally isolated. The account owner must be able to open the Client Portal, select one branch for detailed reports, or select All Branches for consolidated reporting.

## Target Structure

```text
Client Account
|- Main Branch
|  |- Users and permissions
|  |- Devices and counters
|  |- Menu, tables, printers, and settings
|  |- Bills, KOTs, payments, expenses, customers, and dues
|  `- Branch reports
|- Branch 2
`- Branch 3
```

Each operational record must belong to an `account_id` and a `branch_id`. Server-side authorization must verify both values on every request.

## Proposed Data Model

### Branches

Add a dedicated `branches` table instead of continuing to use `restaurants` ambiguously as both business and branch.

Suggested fields:

- `id`
- `account_id`
- `name`
- `code`
- `address`, `phone`, `email`, `tax_id`, `fssai_number`
- `timezone`
- `status`
- `is_main`
- timestamps

Branch codes must be unique inside an account. Existing restaurants should migrate to one Main Branch without changing their historical data.

### User Access

Add `account_user_branches`:

- `account_user_id`
- `branch_id`
- branch role
- branch-specific permissions
- active status

The owner automatically receives all-branch access. Other users may receive one branch, selected branches, or all branches. Permission checks must require both the requested permission and branch access.

### Subscription Capacity

Extend subscription configuration with:

- `max_branches`: `NULL` for Unlimited or a positive number
- `max_users`: existing account capacity
- `user_limit_scope`: `account` or `branch`
- device limits by platform
- optional feature flags by plan or account

Creating or reactivating a branch must be rejected when the branch limit is reached. Reducing a limit must never delete data; the account becomes over capacity until branches or users are disabled or the limit is increased.

### Operational Data

Every bill, KOT, payment, expense, customer transaction, due transaction, device, sync event, and report row must include `branch_id`. Recommended indexes include:

```sql
(branch_id, created_at)
(branch_id, bill_number)
(branch_id, kot_number)
(branch_id, customer_id)
(account_id, branch_id, created_at)
```

Bill and KOT counters must be maintained independently per branch. Existing financial-year bill reset and daily KOT reset behavior should continue within each branch.

## Cloud Sync Design

1. Device activation binds the device to one account and one branch.
2. Every device token includes `accountId`, `branchId`, platform, and device ID.
3. Push and pull APIs derive branch access from the authenticated token; clients must not be trusted to choose an arbitrary branch ID.
4. Cloud KV records and sync events are partitioned by branch.
5. Moving a device to another branch requires owner/admin approval and a fresh activation or transfer flow.
6. Offline queues retain branch identity and cannot upload into another branch after an account change.

## Client Portal Information Architecture

After login, show a persistent branch selector:

- **All Branches**: owner-level consolidated reporting only
- **Selected Branch**: full branch dashboard and branch operations

Recommended portal navigation:

1. Overview
2. Reports
3. Expenses
4. Customers and Dues
5. Users and Branch Access
6. Devices
7. Subscription and Capacity
8. Downloads and Security

### Branch Reports

For one branch, provide:

- sales, bill count, item quantity, discounts, tax, returns
- cash, UPI, card, due, and mixed-payment summaries
- opening cash, cash in hand, bank amount, and expenses
- order-type, item, staff, customer, and due reports
- PDF, Excel, and CSV export

### Consolidated Reports

All Branches should show:

- total sales and net amount
- branch comparison
- payment and expense totals
- due and tax totals
- best-performing branches and trends

Consolidated reports must aggregate normalized report rows. They should not download and merge entire SQLite backups or arbitrary KV snapshots in the browser.

## Cloud Admin Redesign

Organize each client into four areas:

1. **Account**: owner and contact details
2. **Subscription**: plan, expiry, branch/user/device capacities
3. **Branches**: add, suspend, rename, and inspect usage
4. **Access**: portal password reset, device management, and suspension

The client list should show `branches used / limit`, `users used / limit`, devices, plan, expiry, and account health. Branch creation belongs inside the client record, not in a separate registration flow.

## Desktop and Android Flow

- First activation: authenticate account, choose an allowed branch, then activate the device.
- Normal startup: use the saved branch binding and cached license/subscription information.
- Account owner switching branches must be an explicit action that closes or saves the current order first.
- Local Main PC counters inherit the Main PC branch and cannot silently access another branch.
- Android and Windows users see only their assigned branches and compatible plan/platform.

## Migration Strategy

1. Create branch tables and nullable branch references.
2. Create one Main Branch for every existing restaurant.
3. Backfill all existing cloud records with that branch ID.
4. Add indexes and branch-aware authorization.
5. Make branch IDs mandatory only after backfill validation.
6. Update activation and sync clients.
7. Add branch management to Cloud Admin.
8. Add the Client Portal branch selector and branch reports.
9. Add consolidated reports after branch-level reports are verified.

Migration must be idempotent and preserve existing UUIDs, bills, users, devices, and subscriptions.

## Implementation Phases

1. Schema and migration foundation
2. Branch-aware authentication and authorization
3. Branch-aware sync and device activation
4. Cloud Admin branch and capacity management
5. Client Portal branch shell and branch selector
6. Branch report APIs and exports
7. Consolidated reports
8. Desktop and Android branch selection
9. Security, migration, and load testing

## Acceptance Criteria

- Data from one account or branch cannot be read or modified by another.
- Existing customers become a Main Branch without data loss.
- Owners can see all branches; restricted users see only assigned branches.
- Branch, user, and device limits are enforced by the server.
- Bill/KOT numbering remains independent per branch.
- Branch reports match local POS totals for the same period.
- Consolidated totals equal the sum of authorized branch totals.
- Offline sync cannot move records into the wrong branch.

## Explicitly Not Implemented Yet

- Dedicated `branches` table
- Branch-scoped user assignments
- Branch limits
- Branch-aware activation or sync tokens
- Branch-specific or consolidated report APIs
- Client Portal branch selector
- Cloud Admin branch management
- Desktop or Android branch switching


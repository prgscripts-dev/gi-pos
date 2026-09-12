# GI POS Cloud Sync

This folder is the server-side part of the hybrid POS plan.

Desktop app:
- Saves bills, menu, customers, profile, and printer settings locally first.
- Stores pending cloud changes in the local SQLite `sync_outbox`.

Cloud API:
- Stores synced POS data in PostgreSQL.
- Supports client signup, GI admin approval, subscriptions, cloud-login device activation, transfer-code device move, push sync, and pull sync.

## Local Setup

1. Create a PostgreSQL database.
2. Copy `.env.example` to `.env` and update the values.
3. Install dependencies:

```powershell
npm install --prefix cloud
```

4. Run migration:

```powershell
npm run migrate --prefix cloud
```

Run this after every cloud update and before restarting the Node service. The current schema includes a per-account `max_users` subscription field. In GI Cloud Admin, leave **User Limit** blank for Unlimited or enter a positive whole number for a limited account. The limit counts active POS users, is shown in the Client Portal, and is enforced when the POS user directory syncs.

5. Start API:

```powershell
npm run start --prefix cloud
```

Health check:

```powershell
Invoke-RestMethod http://localhost:8080/health
```

Public domain check after VPS/Nginx/SSL setup:

```text
https://goldensea.gihostings.in/connect
https://goldensea.gihostings.in/api/v1/public/config
```

Set this on VPS so generated links always show the public domain:

```env
GI_PUBLIC_BASE_URL=https://goldensea.gihostings.in
```

## Public Signup

Open:

```text
https://goldensea.gihostings.in/signup
```

Client submits business name, owner, phone/email, and password. The account remains pending until GI admin approval.

## Client Portal

Open:

```text
https://goldensea.gihostings.in/portal
```

Client can log in with the phone/email and password used during signup. The portal currently supports:

- account and restaurant status
- subscription plan, expiry, and active device view
- Windows desktop app download
- active device list
- desktop device connection through client login after admin approval and active subscription
- device transfer code for moving the same counter to another PC
- change portal password
- generate portal recovery code
- forgot password reset using the recovery code
- reset desktop app user PIN; the desktop app applies it on the next cloud sync
- payment placeholder only; online payment is not enabled yet

Recovery code flow:

1. Client logs in to `/portal`.
2. Open `Account Security`.
3. Click `Generate Recovery Code`.
4. Save the displayed code safely. It is shown only once.
5. If password is forgotten later, click `Forgot Password?` on the portal login screen and enter phone/email, recovery code, and new password.

Download button:

- Client portal shows `Download Windows App` after login.
- It downloads from `/download/windows`.
- The server looks for the latest `Setup.exe` in `cloud/updates/win` using `latest.yml`.
- During local development only, it can also fall back to the project `release/` folder.

## Android APK download

The client portal also provides the native Android build at:

```text
https://goldensea.gihostings.in/download/android
```

Build the Android application, then copy the APK into `cloud/updates/android/` on the VPS. The newest `.apk` file in that directory is served. To keep APK files outside the deployed source tree, set:

```bash
GI_ANDROID_UPDATE_DIR=/var/lib/gipos/updates/android
```

The Android plan remains platform-isolated: Android-plan accounts activate only GI POS Mobile, while Premium, Gold, and Offline activate only the Windows app.

Desktop app user PIN reset flow:

1. Run `Manual Sync` once from the desktop app after this update. This publishes app users, permissions, and hashed PIN data for restore/login.
2. Client logs in to `/portal`.
3. Open `Desktop User PIN Reset`.
4. Select restaurant, app user, and enter a new 4 to 8 digit PIN.
5. Run `Manual Sync` in the desktop app again. The app applies the new PIN locally.

## Admin Control Panel

Open:

```text
https://goldensea.gihostings.in/admin
```

Use `GI_CLOUD_ADMIN_TOKEN` to load clients. From there:

1. Approve restaurant.
2. Set the compatible plan and expiry date. Premium and Offline allow one Windows device, Gold provides the Windows Main PC flow, and Android allows one native Android device.
3. Reset the client portal password if the client forgets it.
4. Ask the client to connect from desktop app `Home -> Cloud Sync` using phone/email and cloud password.

The desktop app receives and saves `restaurant.id`, `device.id`, and `apiKey` automatically. Customers do not need to copy these IDs.

### Android Plan

Select `Android` in GI Cloud Admin for the native mobile billing application. During activation the mobile app sends `platform: android`; the server rejects platform/plan mismatches and returns the mobile capability set with the subscription.

The initial Android plan includes:

- one Android phone or tablet
- offline-first SQLite billing after first activation
- tables, menu management, customers, dues, and reports
- Bluetooth and network POS58/POS80 printing
- cloud backup and sync whenever internet is available

Existing clients that do not send a platform remain backward compatible and are treated as Windows clients. The `devices.platform` column is created automatically when the cloud service starts; running `npm run migrate` remains recommended during deployment.

Main app transfer flow:

1. Make sure the old POS app has synced recently.
2. Client logs in to `/portal`.
3. Open `Main App Transfer Code`.
4. Choose restaurant and generate code.
5. On the new PC, login with cloud phone/email and password, enter that transfer code, then restore server data.
6. Existing active cloud connections for that restaurant are disabled, so the old main app logs out/stops syncing on its next cloud sync.

Admin password reset flow:

1. Open `/admin` and load clients with `GI_CLOUD_ADMIN_TOKEN`.
2. Click `Reset Password` on the restaurant row.
3. Enter a new client portal password with minimum 6 characters.
4. Ask the client to log in to `/portal` and generate a new recovery code.

This resets only the cloud client portal password. It does not change desktop staff PINs.

## Manual Device Registration Fallback

This route is still available for support/debug cases:

```powershell
Invoke-RestMethod `
  -Method Post `
  -Uri http://localhost:8080/api/v1/devices/register `
  -Headers @{ "x-admin-token" = "change-this-admin-token" } `
  -ContentType "application/json" `
  -Body '{"restaurantName":"Hotel Name","deviceName":"Main App"}'
```

## Desktop App Cloud Login And Restore

Recommended fresh install flow:

1. Install and open the Windows app.
2. On first setup, enter cloud URL, phone/email, and password.
3. Select the restaurant.
4. Enter `Transfer Code` only when moving the main app from another PC.
5. Click `Restore Server Data`. If no synced app users exist yet, set the new Owner PIN and click `Restore & Create Owner`.
6. The app activates the main cloud connection, saves sync credentials locally, pulls server data into SQLite, and enables auto sync.
7. Login with the restored desktop app user PIN or the newly created Owner PIN.

This restores synced server data after uninstall/new PC:

- business profile
- menu categories and items
- customers and due balances
- saved bills/orders
- desktop app users, permissions, and PIN data
- next bill number, calculated from restored bills

The cloud password is for restore/subscription/device setup. Daily billing login stays as local PIN.

## Desktop App Cloud Connection

In the Electron app:

- Cloud API URL: `https://goldensea.gihostings.in`
- Phone or Email: client portal login
- Cloud Password: client portal password
- Restaurant: select the approved restaurant
- Transfer Code: optional, only for moving the main app from another PC

Then click `Login Cloud`, `Connect & Sync`, and then use `Manual Sync` whenever needed.

Auto sync:

1. Open desktop app `Home -> Cloud Sync`.
2. Turn on `Auto Sync`.
3. Keep the app open. It syncs every 60 seconds after the device is connected.
4. Use `Manual Sync` when you want to sync immediately.

## Synced Desktop Data

Current desktop cloud sync includes these local app keys:

- `pos-business-profile`: billing business name, owner, branch, phone, address, GSTIN, receipt footer, billing logo
- `pos-categories`: menu categories and priority/order
- `pos-dining-table-groups`: dining area names and table lists used by POS seating
- `pos-menu-items`: menu item names, category, price, tags, item photos
- `pos-expenses`: restaurant cash/bank expenses used by reports and cash in hand
- `pos-customers`: customer profile, phone, address, credit balance
- `pos-orders`: saved bills/orders, cart lines, payment method, totals, due/paid status
- `pos-staff-users`: desktop app users, permissions, and hashed PIN data so login works after reinstall/new PC
- `pos-staff-user-directory`: app user names/IDs/status only, used by client portal PIN reset

These stay local per device and are not synced:

- printer profiles, printer ports, and KOT/bill printer selection
- theme and local display settings
- local database path and local audit log
- current open cart until it is saved/held as an order

Portal PIN reset sends a one-time cloud command with the new hashed PIN. The desktop app applies it on the next sync. This keeps full security/device-specific settings local while sharing billing, menu, customers, and reports.

## App Update Files

The desktop updater checks:

```text
https://goldensea.gihostings.in/updates/win/latest.yml
```

Recommended: open GI admin panel and upload the update files:

```text
https://goldensea.gihostings.in/admin
```

Use `Windows App Update` and select all three files from the desktop build `release/` folder:

- `latest.yml`
- `GI POS Restaurant Setup 1.1.24.exe`
- `GI POS Restaurant Setup 1.1.24.exe.blockmap`

The admin panel validates that `latest.yml` points to the selected setup `.exe` and that the `.blockmap` filename matches the setup file.

If the VPS shows `413 Request Entity Too Large`, increase Nginx `client_max_body_size` to `400m` and reload Nginx.

Manual fallback: copy those files into `cloud/updates/win/` on the VPS.

You can also set `GI_UPDATE_DIR=/absolute/path/to/updates/win` before starting the cloud server.

## GitHub Version Archive

Keep only the current update files in `cloud/updates/win` or the configured `GI_UPDATE_DIR`.
Previous setup files should be archived in GitHub Releases, one release per app version.

From the desktop repo:

```powershell
npm run dist:win
npm run release:archive -- --notes "Brief release notes for this version"
```

For longer notes:

```powershell
npm run release:archive -- --notes-file RELEASE_NOTES.md
```

The archive script validates `latest.yml`, creates/pushes tag `v<version>`, and uploads the setup exe, blockmap, `latest.yml`, and portable exe when available.

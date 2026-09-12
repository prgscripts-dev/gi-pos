# GI POS Restaurant

Desktop restaurant POS application built with Electron, React, TypeScript, and Vite.

## Version

- App owner: GI
- Version: 1.1.24

## Current Flow

- Home dashboard with POS Sale, Detail Report, Menu Setup, Printer Manage, and Account.
- POS sale screen with table selection, unclosed orders, hold orders, bill save, and thermal printing.
- Business profile for billing with logo, business name, owner, branch, phone, GSTIN, address, and receipt footer.
- Detailed report with payment summary, order type totals, top items, recent bills, open amount, due balance, discounts, and tax.
- Fresh install can restore server data using cloud phone/email and password, then login with restored app user PIN.
- Same counter can be moved to another PC with a portal transfer code; the old same-named device is logged out.
- Account menu groups Business Profile, Cloud Sync, and User Manage shortcuts; owner permissions are read-only from User Manage.
- Every staff PIN login verifies cloud access/subscription before opening billing screens; expired/offline states open limited Cloud Sync access only.
- Cloud Sync shows saved device status by default; phone/email and password appear only for first connect or reconnect/change account.

## Commands

```bash
npm run dev
npm run lint
npm run build
npm run dist:win
```

## Windows Auto Update

Auto update uses the installed NSIS setup build. Upload these files from `release/` to:

```text
https://goldensea.gihostings.in/updates/win/
```

Required files:

- `latest.yml`
- `GI POS Restaurant Setup 1.1.24.exe`
- `GI POS Restaurant Setup 1.1.24.exe.blockmap`

Portable `.exe` can be shared manually, but installed setup is the correct flow for automatic updates.

If you use the included cloud server for updates, copy those files on the VPS into:

```text
cloud/updates/win/
```

Or set `GI_UPDATE_DIR` to another folder and keep the URL as `/updates/win/`.

## Version Archive on GitHub

Do not keep every old setup file in the cloud update folder. The cloud update folder should contain only the current updater files.

Archive each finished version in GitHub Releases:

```bash
npm run dist:win
npm run release:archive -- --notes "Brief release notes for this version"
```

The archive script uploads these files from `release/` into a GitHub Release tagged with the app version:

- `latest.yml`
- `GI POS Restaurant Setup <version>.exe`
- `GI POS Restaurant Setup <version>.exe.blockmap`
- `GI POS Restaurant Portable <version>.exe`, when present

Use a notes file when the description is longer:

```bash
npm run release:archive -- --notes-file RELEASE_NOTES.md
```

Use dry run before publishing:

```bash
npm run release:archive:dry -- --notes "Test archive"
```

Requirement: GitHub CLI must be installed and logged in with `gh auth login`.

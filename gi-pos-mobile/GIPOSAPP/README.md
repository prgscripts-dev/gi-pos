# GI POS Mobile

Native Android restaurant billing app. It keeps restaurant data in SQLite on
the phone, works offline after activation, and synchronizes queued changes when
the cloud connection is available.

## Completed Modules

- Secure cloud activation with encrypted local session storage
- Offline dashboard with license and expiry information
- Floor and table setup, table selection, held and unclosed orders
- Category, product, availability, manual price, GST, and variant management
- Fast ordering with search, active item state, quantity controls, KOT, and hold
- Atomic billing with Cash, UPI, Card, Due, and Split payment configuration
- Financial-year bill numbers and daily KOT numbers
- Customer directory, due balance, and customer bill history
- Bluetooth SPP and network ESC/POS printing for POS 58mm and POS 80mm
- Durable receipt, KOT, and report print queue with retry handling
- Daily sales report with quantity, payment summary, print, and CSV export
- Manual SQLite backup and validated restore
- Background cloud backup and restore for mobile records

## Server Address

The default activation server is set in:

`app/src/main/java/com/GIHOSTINGS/giposapp/MainActivity.java`

Search for `https://goldensea.gihostings.in`. The activation screen also lets
the user edit this address before signing in.

## Build

Use JDK 21. In Android Studio, set **Gradle JDK** to the bundled JDK 21 or the
installed JetBrains Runtime 21.

From PowerShell:

```powershell
$env:JAVA_HOME='C:\Users\ANAS\.jdks\jbr-21.0.11'
$env:Path="$env:JAVA_HOME\bin;$env:Path"
.\gradlew.bat assembleDebug lintDebug testDebugUnitTest
```

Debug APK:

`app/build/outputs/apk/debug/app-debug.apk`

## Data Safety

- SQLite uses foreign keys, WAL, indexed searches, and a busy timeout.
- Checkout stores the bill, payments, due ledger, receipt snapshot, print job,
  and cloud queue in one database transaction.
- Print and cloud failures remain queued instead of losing the completed bill.
- Use **Settings > Create backup** before replacing a phone or making major
  operational changes.
- Reinstalling removes Android app-private data. Activate the same restaurant
  to restore synchronized cloud data, or use **Restore backup** for a manual
  SQLite backup.

## Printer Setup

Bluetooth thermal printers must be paired in Android settings first. Network
printers must be reachable on the same Wi-Fi/LAN, normally on TCP port 9100.
Configure the connection, paper width, and Bills/KOT role from **Settings >
Printer setup**, then run a test print.

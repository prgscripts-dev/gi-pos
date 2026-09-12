# GI POS Pilot for Android

This is the local waiter/counter pilot app for GI POS Restaurant. It is now a native Android app, not a WebView wrapper.

## Flow

1. Start **Local POS Server** on the Main PC.
2. Keep the Android phone and Main PC on the same Wi-Fi/LAN.
3. Open this project in Android Studio.
4. Build/install the app, then enter the Main PC address displayed by Local POS Server, for example `http://192.168.1.3:8080`.
5. The app checks `/api/health`, loads the active user list, and lets staff sign in with their POS PIN before downloading POS data.

The app connects only to the Main PC local API. It stores the server address locally, but menu, users, tables, orders, bills, and KOT printing stay on the Main PC.

Each pilot or browser counter signs in with its own active user. User Manage can limit an account to Windows, mobile, or both device types. If two users open the same table, a stale save is rejected after another device changes the order, preventing a newer cart from being silently overwritten.

## Included native screens

- Server connection
- Staff PIN login
- Table selection with open-order status
- Fast item search and category filtering
- Cart quantity controls
- Hold order
- Print KOT through the Main PC printer routing

## Build in Android Studio

1. Install Android Studio with Android SDK Platform 35 and its bundled JDK.
2. Choose **Open** and select the `android pilot` folder.
3. Let Gradle sync, then choose **Build > Build APK(s)**.
4. The debug APK is created under `app/build/outputs/apk/debug/`.

The Main PC URL can change when a router assigns a new DHCP address. Reserve the Main PC's address in the router to keep it stable. The app's **Server** menu lets staff reconnect when the address changes.

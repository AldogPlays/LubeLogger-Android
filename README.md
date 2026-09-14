# LubeLogger Android wrapper

A minimal native Kotlin WebView wrapper for any self-hosted LubeLogger instance. On first launch, enter a preferred server URL and an optional fallback URL. The settings are saved privately on the device.

Before loading either address, the app performs a short HTTP GET and verifies that the response is successful HTML identifying itself as LubeLogger. It tries the preferred address first, followed by the optional fallback. If neither passes validation, it displays Retry and Configure Servers buttons.

## Features

- Works with arbitrary HTTP or HTTPS LubeLogger installations, including LAN addresses, reverse proxies, and private overlay networks such as Tailscale.
- Restricts main-frame WebView navigation to the configured server origins.
- Does not bypass TLS certificate validation.
- Supports JavaScript, DOM storage, persistent cookies, file/photo uploads, Android back navigation, and system-bar insets.
- Silently detects network transitions and main-page connection failures, validates the active server in the background, and switches to the configured fallback when needed.
- Uses only the Android `INTERNET` permission, with no analytics, ads, accounts, API keys, Google Play Services, or third-party libraries.
- Compatible with GrapheneOS and the installed system WebView.

Because Android's network security configuration cannot dynamically allow cleartext traffic for user-entered hosts, HTTP support is enabled at the application level. Navigation remains limited in code to the origins entered by the user. HTTPS is strongly recommended whenever available.

## Debug build

With JDK 17 and Android SDK 35 installed:

```sh
./gradlew assembleDebug
```

The APK is written to `app/build/outputs/apk/debug/app-debug.apk`.

## Signed release build

Create a private keystore once (keep it outside this repository and back it up):

```sh
keytool -genkeypair -v -keystore lubelogger-release.jks -alias lubelogger \
  -keyalg RSA -keysize 4096 -validity 10000
```

Build the release APK, then sign and verify it with Android build-tools:

```sh
./gradlew assembleRelease
zipalign -p -f 4 app/build/outputs/apk/release/app-release-unsigned.apk app-release-aligned.apk
apksigner sign --ks lubelogger-release.jks --out LubeLogger-release.apk app-release-aligned.apk
apksigner verify --verbose LubeLogger-release.apk
```

Never commit the keystore or its passwords. Updates must always be signed with the same key.

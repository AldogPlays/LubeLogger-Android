# LubeLogger Android wrapper

A minimal native Kotlin WebView wrapper for a self-hosted LubeLogger instance. It tries the configured LAN server first, validates that the response is successful LubeLogger HTML, and then falls back to the configured Tailscale HTTPS server.

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

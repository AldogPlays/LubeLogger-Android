# LubeLogger Client 1.2.1

Private HTTPS certificate authorities installed in Android settings are now supported by native connectivity checks and Android System WebView. System roots remain available, and normal certificate and hostname verification stays enabled. No server URL or private CA is bundled.

This release also adds matching certificate setup guidance and consistent local release checks across LubeLogger-Android and Actual-Android. Preferred/fallback behavior, navigation, and uploads are unchanged. An API 27 annotation fixes a lint error on the existing Safe Browsing callback without changing its behavior.

## Updating

**This is a separate installation, not an update over the legacy app.** The original signing key is unavailable. Version 1.2.1 / versionCode 4 uses a new retained RSA-4096 release key and package `app.lubelogger.client`, with launcher name **LubeLogger Client**. The old `app.lubelogger.wrapper` app remains installed with its original settings, logins, and WebView data.

Open **LubeLogger Client**, enter your server URL, and sign in normally. The new app has its own settings and browser storage; it cannot read or migrate the old app's sandbox. **Keep the original app installed; do not uninstall it or clear its storage.** Future Client releases will use this new package and retained key for in-place updates.

Install your CA's public root certificate in the same Android profile as the app, use a hostname covered by the server certificate, and restart the app. See [HTTPS and private CAs](HTTPS.md).

## Release status

Release checks and certificate fingerprints are recorded in the [validation record](VALIDATION.md). Device HTTPS and data-retention checks must be recorded separately; they are not established by a successful build.

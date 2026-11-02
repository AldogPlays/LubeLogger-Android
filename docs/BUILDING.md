# Building and signing

Use JDK 17 and Android SDK platform 35. The pinned AGP 8.7.3 uses build-tools 34.0.0 by default; the release-signing script uses 35.0.0. The project retains Gradle 8.9 and Kotlin 2.0.21. Set `ANDROID_HOME` to your SDK, or configure an ignored `local.properties`. Builds run locally without hosted workflows.

```sh
./gradlew testDebugUnitTest lintDebug lintRelease assembleDebug assembleRelease
git diff --check
```

There are currently no JVM unit tests; `testDebugUnitTest` reports `NO-SOURCE`. This is not a passing device TLS test. Debug and unsigned release APKs are under `app/build/outputs/apk/`.

## Signing identity for LubeLogger Client

Version 1.2.1 / versionCode 4 starts the separate package `app.lubelogger.client`, shown as **LubeLogger Client**. Its retained password-protected PKCS12 RSA-4096 key uses alias `lubelogger-client`. The public certificate is pinned in [SIGNING_CERTIFICATE_SHA256](SIGNING_CERTIFICATE_SHA256):

```text
987274ab1fa8d18cc34142fcb0b286e6aa144b29e6974f49913baf8cbf691c32
```

All future Client updates must retain this package and key and increase versionCode. Keep the private keystore and password outside the repository. Do not generate another key or change the pin to work around an update conflict.

```sh
export ANDROID_HOME=/path/to/Android/Sdk
export LUBELOGGER_KEYSTORE=/private/path/to/lubelogger-client-release.p12
export LUBELOGGER_KEY_ALIAS=lubelogger-client
./scripts/build-release.sh
```

The signer prompts for passwords. For unattended signing, optionally set `LUBELOGGER_KS_PASS_FILE` to a private password file outside Git; never put a password directly on the command line. `ANDROID_BUILD_TOOLS_VERSION` selects another installed signing-tools version.

The script builds debug/release variants, runs lint, aligns and signs the release APK, verifies its signature and pinned certificate, checks 16 KiB archive alignment, and writes a versioned APK, `.sha256` checksum, and `SHA256SUMS` under ignored `release-artifacts/`. Failed certificate checks leave no new distributable APK. No release is uploaded automatically.

## Legacy package and data

The old package is `app.lubelogger.wrapper`. The verified 1.2.0 release was debug-signed with certificate `65cbfff2f2b2db9645d1a7056e1f30e13c6792b94f68bdace31894cce1d34244`; its private key is unavailable. Its public fingerprint is preserved in [LEGACY_SIGNING_CERTIFICATE_SHA256](LEGACY_SIGNING_CERTIFICATE_SHA256).

The new Client installs alongside the legacy app. Keep the old app installed to retain its settings, cookies, and local storage. The new Client has a separate Android sandbox and must be configured and signed into separately; it does not migrate the legacy app's private data. Do not uninstall or clear the old app to install the Client.

After signature/package checks, installing the Client uses:

```sh
adb install -r release-artifacts/lubelogger-1.2.1.apk
```

This targets the new package only. Future Client updates use the same command and retained signing identity to preserve Client data. A signed APK contains a public certificate, not a recoverable private key.

## Back up the retained key

Store the encrypted keystore, its password, alias, and public fingerprint in a password manager, and keep a second keystore copy off this computer. Restore a backup and verify `PrivateKeyEntry` and its SHA-256 fingerprint with `keytool -list -v -keystore /path/to/restored-keystore`. An archive on this laptop alone does not survive a wipe. Never publish a keystore or password as a GitHub asset.

Follow the [HTTPS acceptance checks](HTTPS.md) and record device results in [VALIDATION.md](VALIDATION.md). Build success alone does not establish GrapheneOS runtime trust or data retention.

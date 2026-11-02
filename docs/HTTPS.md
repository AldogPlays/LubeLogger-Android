# HTTPS and private certificate authorities

LubeLogger-Android is a native Kotlin app using Android System WebView. It is not a PWA or Trusted Web Activity. On GrapheneOS it uses the installed System WebView provider, rather than handing the page to an external browser.

Native connectivity checks and WebView use Android's standard [Network Security Configuration](https://developer.android.com/privacy-and-security/security-config), with both `system` and `user` trust anchors in the base configuration. This applies to all configured hosts in debug and release builds. No browser selection or domain-specific configuration is required.

## Using a private CA

1. Install the CA's **public root certificate** as a CA certificate in Android settings, in the same user or work profile as this app. Never install or distribute the CA's private key. For Caddy's internal issuer, use its public root certificate, not the server's leaf certificate.
2. Configure an HTTPS server URL whose hostname is covered by the certificate's Subject Alternative Name. A wildcard covers one label: `*.home.arpa` can cover `vehicles.home.arpa`, but not `home.arpa`, a deeper subdomain, or an IP address. Enter a concrete hostname in the app, not the wildcard.
3. Ensure DNS resolves that hostname from the device and the server sends its required intermediate certificate chain. A trusted root does not repair DNS, an expired certificate, an incorrect hostname, or a missing intermediate.
4. Fully close and reopen the app after changing Android's CA store. Do not clear app storage or reinstall.

No hostname, CA certificate, credential, or private key is bundled with the app. Public HTTPS servers and other private CAs are supported without rebuilding. Trusting the user CA store means trusting CAs deliberately installed in that Android profile, not arbitrary certificates presented by a server.

## Verification and data

Certificate-chain, validity, and hostname checks remain enabled. WebView retains its default TLS-error cancellation, consistent with [Android's guidance](https://developer.android.com/reference/android/webkit/SslErrorHandler). There is no accept-all trust manager, custom permissive hostname verifier, certificate exception, or TLS-error continuation. Existing configured HTTP and automatic failover behavior is unchanged.

Version 1.2.1 installs as **LubeLogger Client** (`app.lubelogger.client`) with a new retained release key. It runs alongside the legacy **LubeLogger** app (`app.lubelogger.wrapper`), whose signing key is unavailable. The original app's settings, cookies, and WebView storage remain in its existing sandbox. The new app has separate storage and must be configured and signed into separately; it does not import the original app's data. Do not uninstall or clear the original app to install this release. Future updates to LubeLogger Client must retain its package ID and signing certificate.

## Release acceptance checks

Use a disposable test server containing recognizable LubeLogger HTML. Record Android/GrapheneOS version, app version, and System WebView version. Verify both the native startup check and the rendered page; an app that never leaves setup has not passed the browser check.

| Certificate presented by HTTPS server | Expected result in both paths |
|---|---|
| Valid public CA chain, matching hostname | Accepted |
| Valid private CA chain installed in this Android profile, matching hostname | Accepted |
| Private CA absent from this Android profile | Rejected |
| Untrusted self-signed leaf | Rejected |
| Expired certificate issued by the installed CA | Rejected |
| Valid certificate issued by the installed CA, wrong hostname | Rejected |

Configure only one server when testing rejected certificates so a valid fallback cannot obscure a failure. Verify that the legacy app remains installed with its original settings/data, that the new app starts with separate settings, and that settings and logins persist across a cold restart. Also check uploads, navigation, and preferred/fallback behavior. Future same-package updates must retain the new app's data. Runtime checks remain pending until recorded in [VALIDATION.md](VALIDATION.md).

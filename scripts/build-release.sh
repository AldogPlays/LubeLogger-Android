#!/usr/bin/env bash
set -euo pipefail
# Passwords are prompted by apksigner unless supplied through a private password file.
# Never store a signing key or password in the repository.
: "${ANDROID_HOME:?Set ANDROID_HOME to your Android SDK}"
: "${LUBELOGGER_KEYSTORE:?Set LUBELOGGER_KEYSTORE to the existing private release keystore}"
: "${LUBELOGGER_KEY_ALIAS:?Set LUBELOGGER_KEY_ALIAS to its signing alias}"
project_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$project_dir"
pin_file="$project_dir/docs/SIGNING_CERTIFICATE_SHA256"
if [[ ! -f "$pin_file" ]]; then
    printf 'Missing signing certificate pin. Restore it from trusted Client release sources; see docs/BUILDING.md.\n' >&2
    exit 1
fi
expected_certificate="$(tr -d '\r\n' < "$pin_file")"
[[ "$expected_certificate" =~ ^[0-9a-f]{64}$ ]] || { printf 'Invalid signing certificate pin.\n' >&2; exit 1; }
version="$(sed -n 's/.*versionName = "\([^"]*\)".*/\1/p' app/build.gradle.kts)"
[[ "$version" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]] || { printf 'Cannot read release version.\n' >&2; exit 1; }
./gradlew testDebugUnitTest lintDebug lintRelease assembleDebug assembleRelease
release_dir="$project_dir/release-artifacts"
mkdir -p "$release_dir"
tools="$ANDROID_HOME/build-tools/${ANDROID_BUILD_TOOLS_VERSION:-35.0.0}"
staging="$(mktemp -d "$release_dir/.signing.XXXXXX")"
trap 'rm -rf -- "$staging"' EXIT
apk_name="lubelogger-$version.apk"
"$tools/zipalign" -P 16 -f 4 app/build/outputs/apk/release/app-release-unsigned.apk "$staging/aligned.apk"
password_args=()
if [[ -n "${LUBELOGGER_KS_PASS_FILE:-}" ]]; then
    password_args=(--ks-pass "file:$LUBELOGGER_KS_PASS_FILE")
fi
"$tools/apksigner" sign --ks "$LUBELOGGER_KEYSTORE" --ks-key-alias "$LUBELOGGER_KEY_ALIAS" "${password_args[@]}" --out "$staging/$apk_name" "$staging/aligned.apk"
"$tools/apksigner" verify --verbose "$staging/$apk_name"
actual_certificate="$("$tools/apksigner" verify --print-certs "$staging/$apk_name" | sed -n 's/^Signer #1 certificate SHA-256 digest: //p')"
if [[ "$actual_certificate" != "$expected_certificate" ]]; then
    printf 'Signing certificate mismatch. Restore the retained release key before distributing this APK.\n' >&2
    exit 1
fi
"$tools/zipalign" -c -P 16 4 "$staging/$apk_name"
mv -- "$staging/$apk_name" "$release_dir/$apk_name"
if [[ -f "$staging/$apk_name.idsig" ]]; then
    mv -- "$staging/$apk_name.idsig" "$release_dir/$apk_name.idsig"
fi
(cd "$release_dir" && sha256sum "$apk_name" > "$apk_name.sha256")
cp -- "$release_dir/$apk_name.sha256" "$release_dir/SHA256SUMS"
printf 'Release APK and checksum: %s\n' "$release_dir"

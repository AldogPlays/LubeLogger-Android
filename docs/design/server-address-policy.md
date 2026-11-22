# Server-address policy — PR 1

Status: approved for implementation; PR 1.

## Agreed scope

- Deliver as its own PR, before the connection lifecycle PR.
- Concentrate address parsing, identity, origin comparison, and equivalent-page mapping into one module.
- Correct inconsistent comparisons as part of this change, rather than preserving those inconsistencies during extraction.
- Preserve path case: `/Garage` and `/garage` are distinct paths.
- Respect path-segment membership: `/garage` must not prefix-match `/garage2` when transferring a page.
- Keep connection lifecycle behavior outside this PR.
- Accept HTTP/HTTPS server addresses with optional base paths; reject query strings and fragments in newly configured addresses. Page URLs may contain query strings and fragments.
- Preserve origin-wide WebView navigation to either configured origin; base paths govern page transfer, not navigation permissions.
- Preserve native connectivity redirects within the probed address's original origin.
- When existing settings contain now-invalid query strings or fragments, reopen setup with an explanation and the saved text available to edit. Do not silently rewrite or erase those values.
- Transfer a page's relative path, query, and fragment only when it is within the active base path on the active origin, respecting path segments and case. Preserve encoded path data. Otherwise load the replacement base address.

## Address rules

- Ignore scheme and hostname case; compare effective ports so an omitted port and its explicit default are equivalent.
- Preserve path case and encoded path data. Treat an absent/root path and trailing base-path delimiter consistently for address identity and page transfer.
- Reject configured dot-segment paths, including encoded dot segments, and encoded path separators rather than silently changing their meaning. Return a field-specific explanation with the entered value intact.
- Continue rejecting credentials and malformed authorities. Normalization performs no network access.
- Navigation checks either configured origin. Native redirects check the original probed origin throughout the redirect chain; these are deliberately different policies.
- Page transfer requires the active origin and segment-aligned membership in the active base path. Preserve the raw relative suffix, query, and fragment. Missing, malformed, unrelated, or ambiguous page URLs load the replacement base.
- Duplicate normalized preferred/fallback values represent one address for probing; retain the user's configured preference fields.

## Selected module interface

One pure Kotlin configuration parser returns validated preferred/fallback address values or field-specific errors. The same parser is used on settings submission and when reading saved settings.

Validated address values own normalized identity, origin comparison, and equivalent-page mapping. The configuration owns navigation permission across its configured origins. Constructors prevent callers from bypassing validation; malformed user input returns errors instead of throwing.

Illustrative shape, with naming adjustable during implementation:

```kotlin
ServerAddresses.parse(preferredText, fallbackText) // Valid or Invalid(field errors)
addresses.allowsNavigation(pageUrl)
address.sameOrigin(pageUrl)
address.equivalentPage(pageUrl, replacement)
// Address equality owns normalized identity.
```

The implementation hides parsing, effective ports, origin tuples, and raw-path membership. No parser adapter is needed for pure in-process computation. Depth comes from coherent address rules behind one seam, rather than relocating isolated helpers.

## Acceptance and verification

- JVM tests cross the module interface: valid/invalid fields; query/fragment and credential rejection; hostname/scheme case; default ports; root/trailing slash identity; case-sensitive paths; dot segments and encoded separators.
- Transfer tests include exact base, nested pages, `/garage` versus `/garage2`, different origins, encoded suffix preservation, query/fragment preservation, and safe replacement-base fallback.
- Verify navigation still permits either configured origin and redirects remain constrained to the probed origin.
- Verify existing invalid settings reopen setup with original editable values and an explanation, without silently modifying storage.
- Run the relevant JVM tests, debug/release lint, debug/release assembly, and whitespace checks. Device checks cover setup and browser navigation; record unavailable device verification honestly.

## Delivery

This PR contains the glossary, this design, address module and caller integration, and its regression tests. It excludes connection lifecycle ownership and timing changes. Open and review it independently; merge before basing PR 2 on the updated target branch.

## Evidence

`MainActivity.kt` currently uses full-string comparison for address identity, prefix matching for page transfer, and parsed origin triples for redirect and navigation decisions. Its normalizer accepts queries and fragments and trims trailing slashes from the entire URL string.

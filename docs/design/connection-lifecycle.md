# Connection lifecycle — PR 2

Status: approved for implementation; PR 2, based on merged server-address policy PR #2.

## Agreed scope

- Deliver as a separate PR after the server-address policy PR has merged.
- Build on the address rules established by PR 1.
- Concentrate connection selection, failover, retry timing, and stale-result rejection into one module.
- Prevent a late connection result from navigating the WebView after the app leaves the foreground.
- Revalidate connectivity when the app resumes.
- Keep Android rendering and lifecycle forwarding in the Activity.
- Retain a healthy active fallback even when the preferred address becomes reachable; fresh connection selection tries the preferred address first.
- Preserve usable restored pages. If the saved page or active address is unusable, recover through normal connection selection.
- Invalidate pending connection results at `onPause`, including when the upload picker opens. Preserve the WebView and file callback so a selected file can still be delivered; schedule fresh validation on resume.
- Preserve the 1.2-second settling delay, 4-second active-failure retry delay, existing probe timeouts, and manual Retry screen when initial selection fails.

## Selected module interface

One connection session exposes explicit operations such as `resume`, `pause`, `retry`, `connectionChanged`, and `close`. Network transitions and main-frame connection failures feed the same connection-change operation. Configuration changes close the old session and construct a new one from validated PR 1 address values.

The Activity owns Android rendering, the current WebView page, browser restoration, and file-upload callbacks. The module owns startup ordering, active-address stickiness, probe execution, retry timing, coalescing, and stale-result rejection. It delivers display decisions such as checking, unavailable, initial selection, and replacement selection.

All public calls and display decisions execute serially on the main thread. Replacement decisions use the current browser page at delivery time and PR 1's page-transfer rules. Do not capture an old page before a probe and later overwrite newer navigation.

Illustrative shape, with naming adjustable during implementation:

```kotlin
session.resume()
session.pause()
session.retry()
session.connectionChanged()
session.close()
```

The session keeps its dependencies behind internal seams: a real HTTP probe and controlled test probe; Android scheduling and deterministic test scheduling. Do not expose timers, generations, executor state, or probe completion events to the Activity. Tests exercise the same session interface as the Activity.

## Lifecycle and result delivery

- `pause` synchronously invalidates every pending result before returning and cancels delayed work. In-flight transport cancellation is best effort; correctness depends on rejecting stale results, not successful interruption.
- While paused, ignore incoming connection-change hints and suppress all connection-driven rendering, navigation, and retry scheduling. Preserve active state, WebView state, and upload callbacks.
- `resume` is idempotent while already resumed. A new foreground period starts fresh validation: immediate preferred-first selection if no usable active connection exists, otherwise validation after the settling delay.
- `close` is terminal and idempotent, invalidates results, and releases owned scheduling/probe resources. Destroyed or replaced sessions cannot emit display decisions.
- Coalesce connection-change hints and avoid concurrent logical probe attempts. A canceled old attempt must not reset a newer attempt's running state or emit decisions into the newer foreground period.

## Selection, restoration, and compatibility

- Fresh selection tries preferred then distinct fallback. Total failure displays unavailable with manual Retry.
- Active validation checks the active address first. A healthy fallback remains active. If active validation fails, try the distinct alternative; if both fail, retain the current page and retry after four seconds.
- Preserve the current 2.5-second initial and 1.5-second failover connect/read timeouts, 1.2-second settling delay, response-size limit, response recognition, and redirect-attempt limit. These remain per-operation limits, not a newly promised total attempt deadline.
- The Activity attempts browser restoration and reports success only when a usable page was restored and the saved active address belongs to current validated configuration. A usable page must be allowed by the configured navigation policy.
- Failed restoration or missing/invalid active state enters fresh selection. A usable restored page stays visible while validation runs.
- Pause caused by opening a file picker invalidates connection work without destroying the browser or canceling the upload callback. The Activity can deliver the picker result normally, and resume revalidates connectivity.
- Keep platform TLS validation, system/user trust anchors, navigation restrictions, cookie/storage behavior, upload behavior, and signing/package identity intact.

## Acceptance and verification

- Deterministic scenario tests drive public operations and inspect observable display decisions, using controlled completion order and virtual time.
- Cover preferred failure/fallback success; healthy fallback retention; both active addresses unavailable; retry timing; repeated network/error hints; and no concurrent logical checks.
- Cover late completion after pause, close, configuration replacement, and a subsequent resume; verify old work cannot navigate, schedule retries, or interfere with a newer attempt.
- Cover valid restoration without page reload, missing active state, failed restoration, manual Retry, and repeated resume/pause/close calls.
- Exercise the real probe against controlled HTTP responses for same-origin redirects, cross-origin rejection, response recognition, bounded reads, and failures. This does not establish device TLS trust.
- Run relevant JVM tests, debug/release lint, debug/release assembly, and whitespace checks. Device acceptance covers uploads across picker pause/resume, browser restoration/navigation, network transitions, and the existing HTTPS/private-CA matrix. Record any unavailable device verification.

## Alternatives considered

A tiny command interface with nullable foreground snapshots reduces method count but hides lifecycle intent. A generic event/state protocol supports extension but increases caller knowledge and risks replaying navigation decisions. Explicit lifecycle operations provide better locality for the current Activity without requiring speculative extensibility.

## Delivery

This PR contains this design, connection-session implementation, Activity integration, probe/scheduler adapters, and scenario tests. Base it on PR 1 after that PR merges; do not include PR 1's implementation in the second review diff. Keep it independently reviewable and exclude address-policy redesign or timing retuning.

## Evidence

`MainActivity.kt` coordinates executor work, Handler callbacks, generation checks, WebView identity, and lifecycle cleanup across distant methods. `onStop` removes delayed checks but does not invalidate in-flight results. Saved-state recreation creates a WebView without checking whether restoration succeeds or an active address exists.

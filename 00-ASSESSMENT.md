# 00 — Feasibility & Permissibility Assessment — Mound City Transit

This is a PER-TOOL assessment: does this specific tool clear the SDK's technical bar
and Light's approval bar. Every claim below was verified during Phase 0 (2026-08-05)
against SDK commit `9aed6ff` or by measurement; citations name the file or the
`docs/phase0/` artifact.

## Required capabilities

- **Network fetch, foreground and scheduled**: the GTFS zip (~3.7 MB, quarterly) and
  three GTFS-Realtime `.pb` files (~240 KB per full poll, `If-Modified-Since` → 304
  supported; gzip not honoured — measured, `docs/phase0/m2_headers.txt`).
- **Local storage**: a ~3.25 MB binary schedule index in `filesDir` plus DataStore for
  saved stops (≤12) and refresh timestamps.
- **Daily background refresh**: one `@LightJob` with a 24 h period.
- **Full-screen numeric text entry** for stop numbers (3–5 digits).
- **Nothing else.** No location, no camera, no audio, no notifications, no calls.

## SDK surface verification

- **Typed text entry**: `LightTextInputEditor` exists and works — driven by hand on
  the emulator via `examples/weather` (Phase 0.2), which is the module this tool's
  entry screen is modeled on.
- **Background jobs**: `@LightJob` on a top-level `LightJobHandler` property is
  KSP-validated (`plugin/.../LightSdkProcessor.kt:42-99`); scheduling floor is 15 min,
  far below the 24 h this tool needs.
- **Phone calls from a tool: not possible.** `sdk/shared/.../LightServiceMethod.kt`
  at the pin defines exactly 8 RPC methods (GetToken, GetVersion, SetRingtone,
  GetKeyboardOptions, GetUserPreferences, GetPermission, RequestPermissionComponent,
  DeviceKeyEvent) — nothing dials or opens contacts. The reference screen's contact
  numbers are therefore **read-only text** (M6, settled).
- **Intents are unreachable anyway**: `android.content.Intent` is a blocked import and
  `startActivity(` a blocked code pattern (`plugin/.../LightSdkPlugin.kt:72-121`), so
  there is no side door to a dialer and the design does not want one.

## Permission allow-list check

`tool/lighttool.toml` will request exactly two (Phase 2.1):

- `android.permission.INTERNET` — in `ALLOWED_PERMISSIONS`
  (`plugin/.../LightToolMetadata.kt:147-159`). Fetches the schedule and realtime.
- `android.permission.ACCESS_NETWORK_STATE` — same list. Lets the tool say "offline —
  showing schedule" instead of spinning.

Nothing else is requested. (The file currently commits `permissions = []` because no
network code exists yet; the flip to the two-permission list is build task 2.1.)

## Third-party dependency allow-list check

Both planned non-SDK dependencies are on `ALLOWED_DEPENDENCIES`
(`plugin/.../LightSdkPlugin.kt:17-40`; matching is prefix-based, so versions are
unconstrained) **and were build-verified through the plugin's declared and resolved
gates** in a throwaway module on 2026-08-05 (Phase 0.8, BUILD SUCCESSFUL):

- `androidx.datastore:datastore-preferences` — saved stops and refresh timestamps.
- `com.squareup.okhttp3:okhttp` — conditional GETs with a descriptive User-Agent.

No allow-list additions are needed; no upstream request required. The realtime
decoder is hand-rolled (~200 lines, decision D4) precisely so this list stays at two.

## Ethos argument

The tool answers one question — *when is my bus or train coming?* — and ends. It is
finite by construction: a departure board bounded at 8 rows, no infinite scroll, no
feed, no notifications, no engagement mechanics, nothing to check compulsively.
Offline by default (the schedule is bundled and refreshed daily in the background);
realtime is fetched only when the user asks, at most every 30 s (measured publication
cadence is 21 s bus / 60 s alerts — `docs/phase0/m1_analysis.txt`). Every screen
carries its data's age, and an expired schedule replaces the list rather than
masquerading as current. Monochrome throughout; live vs scheduled is weight and
glyph, never hue. The full category defense is `docs/05-VETTING-DEFENSE.md`.

## Verdict

**GO.** No blockers. The two open questions (M4 holiday encoding, M5 `stop_id`
stability across picks) are content questions that cannot be measured before the
2026-08-31 pick change and do not block v1 code — Phase 4 tasks 4.7/4.8 own them.

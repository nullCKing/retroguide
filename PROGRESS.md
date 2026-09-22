# Progress

Written so a session with no memory of this one can pick it up. Read `PLAN.md` for the milestones
and `DECISIONS.md` for why things are the way they are.

**Status: it builds, every test passes, and everything an emulator can verify has been verified on an
Android TV emulator against the mock server. It has not yet run on a real Fire TV Stick, and there
is still no real Xtream account.**

---

## The constraint that shapes everything

This was built in a container whose egress policy returns 403 for `dl.google.com`,
`maven.google.com`, `repo1.maven.org`, `services.gradle.org`, `plugins.gradle.org` and
`repo.maven.apache.org`, and which has no hardware virtualisation (`/dev/kvm` absent, no `vmx`/`svm`
CPU flags). Four routes were tried for the SDK — direct download, the Ubuntu `android-sdk`
packages, alternate Maven mirrors, GitHub. Only GitHub, PyPI and npm are reachable.

So the source was written blind, and the first Gradle build, the first screenshot tests and the
first emulator run all happened afterwards, on a Windows machine, on 2026-09-21. What that first
contact changed is in `DECISIONS.md` under "First build and first run on a device". The short
version: every dependency version resolved as written, the app compiled after two wrong imports,
and the things that were actually broken were the ones only a screen can show — a PRAGMA that
throws on Android, outlined text drawn twice stroked, and D-pad focus on the login and settings
screens.

---

## What is done and verified here

| Area | Evidence |
| --- | --- |
| ✅ Filter engine | 107 unit tests pass (`tools/run-core-tests.sh`). Every prefix style in the spec, every country token, whole-token matching, allowed and disallowed markets, national channels kept, unknown country dropped. |
| ✅ Filter accuracy against ground truth | `reports/discovery.md`: precision 1.0000, recall 1.0000 over 5,181 labelled channels, zero disagreements, zero markets mis-assigned. |
| ✅ Import efficiency | The category short-circuit avoids downloading 4,629 of 5,181 channels — 89% of the catalogue never fetched. |
| ✅ Tokeniser, name cleaner | Unit tested, including `LATINO`/`LA`, `SONY`/`NY`, `L.A.` vs `US.ESPN`, unicode `ᴴᴰ`. |
| ✅ Channel numbering | Stability across refreshes, across a channel disappearing and returning, alphabetical determinism, block overflow. |
| ✅ XMLTV time parsing | Offsets in every form, half-hour and 45-minute zones, truncated stamps, malformed input, the manual offset setting. |
| ✅ Short-EPG base64 | Round trip, padding, UTF-8, plain-text passthrough. |
| ✅ Guide geometry | Notches on both edges, clipping, minimum cell width at the right edge, gap filling with no holes or overlaps. |
| ✅ Guide navigation | Every D-pad and media-key rule in spec section 5.3, as a pure state machine. |
| ✅ Streaming JSON reader | 20,000-object array streamed; large-integer handling. |
| ✅ Mock Xtream server | Runs; serves login, categories, streams, short EPG, a 187 MB / 542,046-programme XMLTV (gzip verified byte-identical), an endless MPEG-TS and a sliding-window HLS playlist over FFmpeg-generated media. Bad credentials answer `auth: 0`. |
| ✅ Discovery tool | Runs against the mock server using the shipping filter and parser. |
| ✅ Launcher art | 320×180 TV banner and five launcher icon densities, generated and visually checked. |
| ✅ Gradle build | `./gradlew build` is green: `:core` (107 tests), `:app` debug and minified release APKs, lint, and the seven Roborazzi screenshot tests. Needs a JDK 17+ and the Android SDK; the Foojay plugin fetches the JDK 17 that `core` asks for. |
| ✅ Screenshot tests | Baselines recorded under `app/src/test/screenshots/`. The guide, the details dialog, the future-programme box, the empty state, a six-row theme and the banner all render as intended. |
| ✅ Emulator run | `tools/verify-on-device.ps1` runs end to end on an API 30 Android TV AVD with 1 GB of RAM: sign-in, import, playback, banner, guide navigation, the details dialog, channel up/down, last channel, settings and an instant filter change. `reports/device-verification.md` and `screenshots/` are its output. |

## What the first build found

The predictions above the line were close: every dependency version resolved as written, the
Compose text APIs compiled unchanged, and the one `@UnstableApi` call site outside
`ExoPlayerController` (the `PlayerView` setup in `MainActivity`) was caught by lint, not the
compiler. Two imports were wrong (`Modifier.focusable` lives in `androidx.compose.foundation`;
`item` is a `LazyListScope` member). The Robolectric qualifier string had its parts in the wrong
order, and the release variant cannot run the screenshot tests at all, so it no longer tries.

What only running it could find, all fixed and all recorded in `DECISIONS.md`: the database
crashed on first open (`execSQL("PRAGMA journal_mode=WAL")` throws on Android), every white cell
title was drawn stroked (the measurer's cached paint kept the outline pass's stroke), the login
fields could not be left with a D-pad, and the settings rows could not be reached because the root
held focus.

## Definition of Done, as it stands

| Definition-of-Done item | State |
| --- | --- |
| ⬜ Signed release APK builds with one command into `dist/` | `./gradlew build` produces a minified release APK signed with the debug key. `tools/make-keystore.ps1` has not been run, so no release key exists yet and `./gradlew release` has not been exercised with one. |
| ✅ Login works | Verified on the emulator against the mock server. The bad-credentials path is written and the mock server answers `auth: 0`, but the error has not been seen on screen. |
| ⬜ Guide matches the reference style | The guide has now been seen — `screenshots/04_guide.png` and the Roborazzi baselines — and reads as a cable guide. **It has still not been compared with `reference/`.** `GuideTheme.kt` remains the one file to edit. |
| ✅ Guide navigation on a device | Up/Down/Left/Right, Rewind/Fast Forward paging, Select on a current programme (tunes) and on a future one (details dialog), Back. |
| ✅ Smooth scrolling | Measured on the emulator only; see the table below and its caveat. |
| ✅ Playback, banner, last channel | Plays the mock server's MPEG-TS; the banner shows number, name, now-with-progress and next; Play/Pause returns to the previous channel. Auto-reconnect is written but has not been provoked. |
| ✅ Only one stream open at a time | One socket to the stream port throughout, including with the guide's preview window up. |
| ✅ Peak memory measured | See the table below. |
| ✅ Screenshot tests pass | Seven Roborazzi tests, recorded and passing in `./gradlew build`. |
| ⬜ Real-server check | **Still no Xtream account.** Everything above is against the mock server. |
| ⬜ Real Fire TV Stick | Not yet. The emulator is a 1 GB API 30 Android TV image; Fire OS 6 (API 25) in particular is untested. `.\tools\verify-on-device.ps1 -Device <ip>:5555` is ready for it. |
| ✅ No credentials in git history or the APK | `secrets/` is gitignored, nothing is compiled in, and `reports/discovery.md` contains no URLs, hosts or credentials. |

---

## Next session: start here

1. **Run it on a real Fire TV Stick.** `adb connect <ip>:5555` then
   `.\tools\verify-on-device.ps1 -Device <ip>:5555`. The emulator numbers below come from a
   software-rendered AVD and say little about a Stick; the frame timing in particular needs real
   hardware, and Fire OS 6 (API 25) has never run the app.
2. **Get `reference/` in front of you** and compare it with `screenshots/04_guide.png` and the
   Roborazzi baselines. `ui/theme/GuideTheme.kt` is the only file that should need editing.
3. **Create the release key** with `tools\make-keystore.ps1`, then `.\gradlew.bat release`, and back
   the `.jks` up somewhere other than this machine.
4. **When a real Xtream account exists**, drop `secrets/xtream.json` in, re-run
   `tools/discover/run.sh`, and read the "Unrecognised prefixes" table at the end of the report.
   That table is the whole point of the discovery step: whatever appears there often is a naming
   convention `CountryDetector` or `ForeignCountries` should learn. Add the tokens, re-run, repeat.
5. **Provoke the reconnect path**: stop the mock server mid-stream and watch for the
   "Reconnecting…" label, the error panel after thirty seconds, and Select retrying.

Building on a new machine needs a JDK 17 or newer on `JAVA_HOME` and an Android SDK on
`ANDROID_HOME` (platform 35, build-tools 35.0.0); the verify script installs the SDK itself.

### Measurements

Taken by `tools/verify-on-device.ps1` on 2026-09-21 against an API 30 Android TV AVD with 1 GB of
RAM, `-gpu swiftshader_indirect` (software rendering), on a Ryzen 7 5800H under WHPX. The full
`dumpsys` output is in `reports/`.

| Measurement | Target | Actual (emulator) |
| --- | --- | --- |
| Peak memory, import of 5,181 channels and 539,944 programmes | well under a 1 GB device | 94.8 MB total PSS after the import (Dalvik heap 19 MB, native 3 MB) |
| Peak memory, guide open and scrolling | — | not separately measured; the windowed query keeps the programme map at a few dozen rows |
| Time to first frame, channel change | as low as possible | 125–360 ms for a channel change (five tunes); 1,434 ms for the first play after launch (cold decoder) |
| Janky frames while scrolling the guide | low | 22 of 35 frames (63%) over 25 rapid D-pad presses; 50th percentile 18 ms, 90th 30 ms, 99th 32 ms. **Software-rendered emulator; a real device is the only meaningful number.** |
| Filter change round trip | under 100 ms | **70 ms** from the toggle to the new channel list (392 channels), timed in the app; 72 ms on the previous run |
| Import duration | — | ~15 s for the channels (89% of the catalogue never fetched), ~23 s for the 187 MB XMLTV |

---

## Known limitations

- **The guide has been seen on an emulator, not compared with the reference images.** Built from
  prose descriptions; `reference/` is still the thing to check it against.
- **All measurements are from a software-rendered emulator**, not a Fire TV Stick.
- **A cold start took about five seconds on the emulator** right after boot (`Displayed` in
  logcat). Keystore setup for the encrypted credentials, Room and WorkManager all happen on the
  first launch; worth measuring on a Stick before deciding it matters.
- **Exclusion keywords are chosen from a fixed list** in settings rather than typed. Editing free
  text with a D-pad is miserable; the underlying setting is plain text and accepts anything, so a
  future version could add a keyboard or a phone companion.
- **Channel logos are not drawn.** `stream_icon` is stored and `GuideChannel.logoUrl` carries it,
  but the channel column shows number-over-call-sign only. Coil is already a dependency; drawing
  them into the Canvas needs an image cache with a hard size cap, which on a 1 GB stick is worth
  doing deliberately rather than by default.
- **Looping test media resets its timestamps** each time round, so a long soak against the mock
  server's `.ts` endpoint may show a hiccup at the loop point. That is the mock server, not the
  player.
- **Fire OS 6 (API 25) is untested** and is the riskiest of the three targets — `minSdk 25` is
  declared and core library desugaring is on, but nothing has run there. The emulator was API 30.
- **The on-screen keyboard drives the login form on a real remote.** Its Next and Done keys move
  between fields and sign in; with the keyboard dismissed, Up, Down and Select do the same. The
  verify script switches the keyboard off because it types with `input text`.
- **No captions or subtitles**, by design. See below.

---

## Suggested next steps, in order

1. **Captions and subtitles.** The first thing anyone will want. `PlayerController` exists as an
   interface for exactly this: add a `tracks: StateFlow<List<TextTrack>>` and a
   `selectTextTrack(id)`, implement them over ExoPlayer's `TrackSelectionParameters`, and add a
   selector to the player overlay. Nothing in the UI needs restructuring.
2. **Channel logos in the guide**, with a size-capped Coil cache and a hard ceiling on decoded
   bitmap size.
3. **Favourites and a channel-list filter in the guide**, which is the other thing a filtered
   guide immediately wants.
4. **Catch-up**, since `tv_archive` is already stored per channel.
5. **VOD and series**, which is a much larger piece of work and deliberately out of scope here.

# CLAUDE.md

Orientation for a Claude Code session picking this project up.

## What this is

An Android TV / Fire TV IPTV player for Xtream Codes accounts, with a guide styled after a 2000s
DirecTV / Comcast cable box. Full build spec behind it; `PLAN.md` has the milestones.

## Read these first, in order

1. **`PROGRESS.md`** — the handover. States precisely which Definition-of-Done items are verified
   and which are not, and has a "Next session: start here" section. Do what it says.
2. **`DECISIONS.md`** — every non-obvious choice and its reason. Check here before "fixing"
   something that looks odd; most of the odd-looking things are deliberate and the reason is
   written down.
3. **`README.md`** — build, test and run instructions.

## The single most important fact

**No Android code in this repo has ever been compiled.** It was written in an environment whose
egress policy blocks `dl.google.com` and `maven.google.com`, so Gradle could not resolve a single
dependency, and which had no hardware virtualisation, so no emulator could run.

The `core` module *is* tested — 107 unit tests pass — because it is plain Kotlin with no Android
and no third-party dependencies, and was compiled with `kotlinc` directly.

So: **`./gradlew build` is the first thing to run, and it will probably fail.** Expect compile
errors. The likeliest cause is dependency versions that do not resolve; they are all in
`gradle/libs.versions.toml`, one file. Fix what the compiler says before touching anything else.

## Priorities from the spec

1. A fast, stable full-screen player with instant channel switching.
2. The guide.
3. Everything else.

Out of scope, deliberately: captions and subtitles, VOD, series, recording, catch-up, M3U,
multiple accounts, external EPG sources. `PlayerController` is an interface so captions can be
added later without restructuring the UI — that is the intended next feature.

## Layout

```
core/          Plain Kotlin, no Android, fully unit-tested. Filter engine, name cleaning,
               channel numbering, XMLTV time parsing, guide geometry, guide navigation,
               streaming JSON reader. Put logic here whenever you can — it is the only
               code that can be tested without a device.
app/data/      Room, Xtream HTTP client, streaming import, XMLTV, settings, credentials
app/domain/    Guide repository
app/player/    PlayerController and its ExoPlayer implementation
app/ui/        Compose screens. The grid is drawn on a single Canvas, not composed.
tools/         Mock Xtream server, discovery tool, device verification, art, keystore
```

## Commands

```powershell
# Tests
.\gradlew.bat :core:test
.\gradlew.bat :app:testDebugUnitTest
.\gradlew.bat recordRoborazziDebug     # re-record screenshot baselines
.\gradlew.bat verifyRoborazziDebug     # check against them

# Build
.\gradlew.bat release                  # signed APK into dist\

# Mock server (needed for anything end to end)
cd tools\mock-xtream ; python server.py --port 8080
# user testuser / pass testpass
# emulator reaches it at http://10.0.2.2:8080

# Everything on a device, in one command
.\tools\verify-on-device.ps1
.\tools\verify-on-device.ps1 -Device 192.168.1.50:5555   # a real Fire Stick

# The filter's accuracy against the mock server's ground truth
bash tools/discover/run.sh             # or run Discover.kt however is convenient
```

`tools/run-core-tests.sh` compiles and runs the core tests with `kotlinc` and a JUnit jar, no
Gradle. It exists only because the originating environment could not run Gradle at all. On a
normal machine, use `./gradlew :core:test`.

## Things to be careful about

- **Do not weaken the filter to make a test pass.** It is deliberately asymmetric: wrongly calling
  a national channel "local" deletes something the user wanted, while wrongly calling a local
  "national" leaves one extra row. `DECISIONS.md` explains each guard (`LA` vs `LA LIGA`, `WAVE`
  vs the Louisville call sign, `NETWORK` vs `USA NETWORK`). Currently precision and recall are both
  1.0000 against 5,181 labelled channels; keep it that way.
- **Memory is the binding constraint.** Target is a Fire TV Stick Lite with 1 GB of RAM shared
  with everything else. The import filters as it parses and writes in batches of 250; the XMLTV
  parser holds one `<programme>` at a time; the guide queries a window of visible rows. Do not
  introduce a `.toList()` over a provider's stream list or a `body.string()` on XMLTV.
- **One ExoPlayer, ever.** Xtream accounts are commonly sold with a single connection, so a second
  player would lock the user out of their own service. The guide's preview and the full-screen
  view share one instance.
- **The guide has never been seen by anyone.** It was built from written descriptions because the
  reference screenshots were unreachable. If the user has them, compare and adjust —
  `ui/theme/GuideTheme.kt` holds every colour, size and count in one place, and a screenshot test
  proves changing it works.
- **Never commit `secrets/`.** Credentials, the signing keystore and `keystore.properties` live
  there and it is gitignored. The signing key must never change, or updates stop installing over
  existing versions.

## Measurements still to take

`PROGRESS.md` has a table with blanks for peak memory during import, peak memory with the guide
open, time to first frame on a channel change, janky frame percentage, and the filter-change round
trip. `tools/verify-on-device.ps1` collects all of them. Fill the table in when you have numbers.

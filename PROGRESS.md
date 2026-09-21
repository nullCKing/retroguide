# Progress

Written so a session with no memory of this one can pick it up. Read `PLAN.md` for the milestones
and `DECISIONS.md` for why things are the way they are.

**Status: the source is complete; the Android build and all on-device verification are unrun.**

---

## The constraint that shapes everything

This was built in a container whose egress policy returns 403 for `dl.google.com`,
`maven.google.com`, `repo1.maven.org`, `services.gradle.org`, `plugins.gradle.org` and
`repo.maven.apache.org`, and which has no hardware virtualisation (`/dev/kvm` absent, no `vmx`/`svm`
CPU flags). Four routes were tried for the SDK — direct download, the Ubuntu `android-sdk`
packages, alternate Maven mirrors, GitHub. Only GitHub, PyPI and npm are reachable.

So: **no Gradle build has ever run, no APK has ever been produced, and no emulator has ever
started.** Everything that could be run without those was run, and is marked ✅ below. Everything
that needs them is marked ⬜ and is what `tools/verify-on-device.ps1` exists to do.

This is not a guess about what is untested. It is the precise line between the two.

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

## What is written but never compiled

Every Android source file. Room schema and DAOs, the OkHttp Xtream client, the filtered streaming
import, the XMLTV pull-parser, `ExoPlayerController`, the Compose screens, the custom-drawn grid,
the WorkManager refresh, the screenshot tests, the Gradle build files.

It has been reviewed by hand and the pure-Kotlin logic it leans on is tested, but **expect the
first `./gradlew build` to surface compile errors.** The likeliest sources, in order:

1. **Dependency versions.** `gradle/libs.versions.toml` pins AGP 8.7.3, Kotlin 2.0.21, KSP
   2.0.21-1.0.28, Compose BOM 2024.12.01, Media3 1.5.1, Room 2.6.1, Roborazzi 1.36.0. These were
   chosen as known-good pairings but could not be resolved to check. Gradle will name any that do
   not exist; bump them in that one file.
2. **Compose API drift.** The grid uses `TextMeasurer`, `DrawScope.drawText`,
   `TextStyle(drawStyle = Stroke(...))` and `Path.addRoundRect`. All are stable, but signatures
   move between versions.
3. **Media3 `@UnstableApi`.** `ExoPlayerController` is annotated `@OptIn(UnstableApi::class)`;
   if a call site outside it touches an unstable type the compiler will say so.

## What has not been verified at all

| Definition-of-Done item | State |
| --- | --- |
| ⬜ Signed release APK builds with one command into `dist/` | Wiring is written (`./gradlew release`, `tools/make-keystore.ps1`); never run. |
| ⬜ Login works; bad credentials show a clear error | The error path is written and the mock server answers `auth: 0` correctly; never seen on screen. |
| ⬜ Guide matches the reference style | Built from the written descriptions in the spec. **`reference/` was never reachable, so no screenshot has ever been compared against the real images.** This is the item most likely to need work. |
| ⬜ Guide navigation on a device | The logic is unit-tested; the key routing in `MainActivity.handleKey` is not. |
| ⬜ Smooth scrolling on the emulator | No `gfxinfo` measurement exists. |
| ⬜ Playback, banner, last channel, auto-reconnect | Never run against either server. |
| ⬜ Only one stream open at a time | Guaranteed structurally — one `ExoPlayerController`, one `ExoPlayer`, preview and full screen share it — but never observed. |
| ⬜ Peak memory measured and recorded | The design bounds it (batch of 250 channels, 500 programmes, streaming parsers, windowed queries), but no number has been taken. |
| ⬜ Screenshot tests pass | Written; Roborazzi has never run. |
| ⬜ Real-server check | **No Xtream account was ever provided.** `secrets/xtream.json` does not exist. The discovery report, playback checks and channel counts are all against the mock server. |
| ✅ No credentials in git history or the APK | `secrets/` is gitignored, nothing is compiled in, and `reports/discovery.md` contains no URLs, hosts or credentials. |

---

## Next session: start here

1. **Build it.** `./gradlew build` on a machine that can reach Google's Maven. Fix what the
   compiler says. Nothing below is worth doing first.
2. **Run `tools/verify-on-device.ps1`.** It does the SDK install, the mock server, the tests, the
   APK, the AVD, the D-pad driving, the screenshots and the measurements, and writes
   `reports/device-verification.md`.
3. **Get `reference/` in front of you** and compare it with `screenshots/`. The theme object
   (`ui/theme/GuideTheme.kt`) is the only file that should need editing for look-and-feel.
4. **Fill in the measurements** in the table below and in this file's summary.
5. **When a real Xtream account exists**, drop `secrets/xtream.json` in, re-run
   `tools/discover/run.sh`, and read the "Unrecognised prefixes" table at the end of the report.
   That table is the whole point of the discovery step: whatever appears there often is a naming
   convention `CountryDetector` or `ForeignCountries` should learn. Add the tokens, re-run, repeat.

### Measurements to fill in

| Measurement | Target | Actual |
| --- | --- | --- |
| Peak memory, import of 5,000 channels | well under a 1 GB device | *not measured* |
| Peak memory, guide open and scrolling | — | *not measured* |
| Time to first frame, channel change | as low as possible | *not measured* |
| Janky frames while scrolling the guide | low | *not measured* |
| Filter change round trip | under 100 ms | *not measured* |

---

## Known limitations

- **The guide has never been seen.** Built from prose descriptions of the reference images.
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
  declared and core library desugaring is on, but nothing has run there.
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

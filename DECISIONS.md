# Decisions

Every non-obvious choice, with the reason. Newest section last.

## Environment

- **The APK is not built in the development container.** The container's egress policy returns 403
  for `dl.google.com`, `maven.google.com`, `repo1.maven.org`, `services.gradle.org`,
  `plugins.gradle.org` and `repo.maven.apache.org`. Four routes were tried: direct download, the
  Ubuntu `android-sdk` packages, alternate Maven mirrors (Aliyun, Tencent, Sonatype), and GitHub.
  Only GitHub, PyPI and npm are reachable. Without maven.google.com there is no Android Gradle
  Plugin, Compose, Media3 or Room, so the Gradle build runs on the developer's own machine.
- **Kotlin compiler from a GitHub release instead of Gradle.** `kotlinc` 2.1.21 downloads from
  `github.com/JetBrains/kotlin/releases`, which the policy allows, and the distribution's
  `junit4` package supplies JUnit. That is enough to compile and run the `core` module, which has
  no Android or third-party dependencies. `tools/run-core-tests.sh` does this; `./gradlew :core:test`
  is the normal path once the build runs on a machine with network access to Google.
- **No Android emulator in the container.** `/dev/kvm` does not exist and `/proc/cpuinfo` reports
  no `vmx`/`svm` flags, so no AVD can start. On-device verification is done by
  `tools/verify-on-device.ps1`, run on the developer's Windows machine.
- **No real Xtream account.** `secrets/xtream.json` was never provided, so the discovery report,
  playback checks and import measurements run against the mock server and legal public test
  streams. Everything that needs the real provider is called out in `PROGRESS.md`.
- **Reference screenshots were not available.** `reference/` could not be reached, so the guide is
  built from the written descriptions in the build spec. The theme object exists partly so the
  look can be corrected in one place once the images are to hand.

## Architecture

- **As much logic as possible lives in a pure-Kotlin `core` module.** The spec only asks for the
  filter engine to be Android-free. Name cleaning, channel numbering, XMLTV time parsing, short-EPG
  decoding, programme categorisation, guide geometry and guide navigation are Android-free too.
  That is good design on its own, and in this environment it is also the difference between logic
  that is actually tested and logic that is only written.
- **Guide navigation is a pure state machine.** D-pad rules are the easiest part of a TV app to get
  subtly wrong and the hardest to verify from a screenshot. `GuideNavigator` turns every rule in
  spec section 5.3 into an assertion.
- **Base64 is implemented by hand in `ShortEpg`.** `java.util.Base64` needs API 26 and `minSdk` is
  25; `android.util.Base64` would pull an Android dependency into a module that is deliberately
  free of them.
- **XMLTV time parsing uses plain arithmetic, not `java.time`.** Keeps `core` free of API-level
  constraints and testable on any JVM. Howard Hinnant's `days_from_civil` is exact for every date
  this app will see.

## Filtering

- **Tokenising records whether a token was closed by punctuation.** This is what separates a
  country prefix from an English word. `DE| SPORT` has a hard boundary after `DE` and is German;
  `IN THE MIX` has a space and is a sentence. Without it, every category starting with "In", "It",
  "At" or "No" would be mistaken for India, Italy, Austria or Norway and skipped. Country codes of
  three characters or fewer therefore require a punctuation boundary; full country names do not.
- **Periods are resolved per token.** `L.A.` and `U.S.A.` are initialisms and collapse to `LA` and
  `USA`; `US.ESPN` splits, because its fragments are multi-letter. This is what lets
  `ST. PETERSBURG` still match as a two-word phrase.
- **`NETWORK` is not treated as a locals indicator, despite the spec listing it.** `USA NETWORK`,
  `CARTOON NETWORK` and `FOOD NETWORK` are national channels. The plural `NETWORKS` is kept, along
  with `LOCAL`, `LOCALS`, `AFFILIATE`, `DMA` and `OTA`.
- **The filter is deliberately asymmetric.** Wrongly calling a national channel "local" deletes
  something the user wanted; wrongly calling a local "national" leaves one extra row in the guide.
  So local verdicts need real evidence, and ambiguous city names are omitted from the "other
  market" lists entirely (Mobile, Jackson, Columbia, Charleston, Springfield, Madison).
- **`LA` and `NY` require corroboration.** Whole-token matching is not enough: `LA LIGA` and
  `LA CASA` are whole-token `LA`. They only count as markets when a call sign, a network name or a
  locals category agrees, and an explicit veto list covers the common Spanish-language phrases.
- **Word-like call signs are gated on the category.** `WAVE` (Louisville), `KING` (Seattle),
  `WISH` (Indianapolis) and `WOOD` (Grand Rapids) are real call signs and ordinary words. Outside a
  locals category the word reading wins, so `WAVE MUSIC` is not mistaken for a Louisville affiliate.
- **`AMERICA` is vetoed by `LATIN AMERICA`, `SOUTH AMERICA` and `AMERICA LATINA`; `KOREA` is vetoed
  by `NORTH KOREA`.** The spec's token lists are correct but incomplete on their own.
- **Recognising countries outside the allowlist is worth the extra list.** Telling "this is
  Germany" apart from "no idea what this is" is what lets a `DE | SPORT` category be skipped
  without downloading it, which is where the import saves most of its bandwidth. A category with no
  country marker at all must still be fetched, because its channel names may carry the country
  individually.

## Numbering

- **Channel numbers are never reclaimed.** Numbers are keyed on `stream_id` and, once handed out,
  survive a channel disappearing entirely, so a channel that comes back gets its old number. A
  viewer who has learned that 1042 is their channel is not served by dense numbering. Blocks are
  US 1000–4999, UK 5000–6999, JP 7000–8499, KR 8500–9999, with overflow from 10000.
- **New channels are numbered alphabetically.** Makes a fresh install's guide readable and makes
  two installs against the same provider produce identical numbering.

## Data layer

- **A streaming JSON reader was written rather than pulling in Moshi.** `android.util.JsonReader`
  is Android-only, and `core` has to stay Android-free so the filter is testable without an
  emulator and reusable by the offline discovery tool. Writing the parser also means the discovery
  report exercises the shipping code path rather than a second implementation of it.
- **Stream ids are `Long`, not `Int`.** Found by the ground-truth scoring: ids past
  `Int.MAX_VALUE` were clamping onto a single value and collapsing half the catalogue onto one
  key — a corruption that looks like working code. `JsonReader.nextInt` now returns 0 out of range
  rather than clamping, so the same mistake fails loudly next time.
- **Every imported channel stays in the database with its country and market.** That is what makes
  removing a filter a query rather than a re-import. Only adding one needs the network, and
  `CategoryEntity.imported` says which categories still need fetching.
- **Programmes are keyed on `channelKey`**, the provider's `epg_channel_id` where there is one and
  `sid:<stream id>` otherwise, so channels served only by the short-EPG fallback join the same way.
  Indexed on `(channelKey, startMs)`, because the windowed overlap query is the only one the guide
  makes and a full scan of a 450,000-row table per frame is not survivable on a Stick.
- **The window query is an overlap test, not containment.** `startMs < to AND endMs > from`. A
  three-hour film that began before the window opened still has to be drawn, with its left notch.
- **Blocking DAO variants exist alongside the suspending ones.** The streaming parsers hand over
  one record at a time through plain callbacks that cannot suspend, so flushing a batch *during* a
  category or a document needs a blocking write. Without it, peak memory would be bounded by the
  largest category a provider happens to have rather than by the batch size.
- **Room uses write-ahead logging.** The spec requires the guide to stay usable during an EPG
  refresh; WAL is what stops the writer blocking the readers.
- **`fallbackToDestructiveMigration`.** The database is a cache of the provider's data and is
  rebuildable by re-importing. Migrating a table the user has no unique data in would be work in
  exchange for nothing.

## Player

- **One `ExoPlayer`, created once, shared by full screen and the guide's preview.** Not an
  implementation detail: Xtream accounts are commonly sold with a single connection, so a second
  player would lock the user out of their own service. It also makes a channel change
  `setMediaItem` + `prepare` rather than a teardown.
- **Short live buffers** (600 ms for playback, 6 s maximum). The only thing a large buffer buys on
  a live stream is a longer wait after every channel change.
- **`STATE_ENDED` is treated as an error.** A live stream should never end; when one does, the
  source went away, and sitting on a frozen frame is worse than reconnecting.
- **Reconnect budget of 30 seconds with a doubling backoff** — about six attempts, frequent enough
  that a blip recovers unnoticed and spaced enough not to hammer a dead stream.
- **`FLAG_KEEP_SCREEN_ON` rather than `setWakeMode`**, which would need the `WAKE_LOCK` permission
  for no benefit on a mains-powered stick.
- **Playback sits behind `PlayerController`.** Captions are out of scope for this build and are the
  first thing anyone will want next; the interface is what makes that a change to one class.

## UI

- **The grid is drawn on a Canvas, not composed.** Nested lazy lists of variable-width cells give
  every cell its own layout and recomposition scope, and cell widths depend on programme durations
  so nothing can be reused between rows. Drawing makes the per-frame cost about forty rectangles
  and forty strings, with no view hierarchy and no recomposition when the highlight moves.
- **Outlined cell text is two draws, not eight.** A stroked pass then a filled pass, rather than
  drawing the string at eight offsets.
- **The guide cursor is anchored on a time, not on a programme id.** That is what makes Up and Down
  behave like a cable guide: moving down a row keeps you at the same moment in the evening even
  though the programme boundaries do not line up between rows.
- **Single activity.** The ExoPlayer surface lives in the composition; a separate guide activity
  would tear it down and rebuild it on the most common action a viewer takes.
- **Exclusion keywords are picked from a list rather than typed.** Free-text entry with a D-pad is
  miserable. The stored setting is plain text and accepts anything, so a keyboard can be added
  later without a data change.

## Signing and secrets

- **One release key, generated locally, never committed.** Android identifies an app by package
  name and certificate together, so changing the key means users must uninstall and lose their
  settings. `tools/make-keystore.{sh,ps1}` refuses to overwrite an existing keystore for that
  reason.
- **A missing keystore warns and falls back to the debug key** rather than failing the build. A
  developer who only wants to run the app should not have to create a signing key first.
- **Cleartext HTTP is allowed**, because most Xtream panels have no TLS at all and refusing it
  would make the app useless for its purpose. The trade-off is stated on the login screen rather
  than buried: on such a server the credentials travel in the clear.

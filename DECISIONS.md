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

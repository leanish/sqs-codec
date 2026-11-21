# Changelog

All notable changes to this project are documented in this file.

## Unreleased

### Security
- Decompression is bounded. A small gzip or zstd payload could decode to gigabytes and exhaust a consumer's memory on
  receive, since decoding had no byte limit. Decoding now fails with `DecompressedPayloadTooLargeException` once it
  passes `maxDecodedPayloadBytes` (default 16 MiB, set with `withMaxDecodedPayloadBytes(...)`), on receive and when
  validating already encoded outbound messages; the payload's raw length (`l`), which the sender controls, doesn't
  bound it. A fresh send that the interceptor is configured to compress or encode is refused with the same exception,
  before compressing or encoding, when the payload's UTF-8 bytes exceed the limit (0.5.x attempted the send without
  such a check; SQS still applies its own size limits), so a producer
  doesn't enqueue what receivers with its limit refuse; the request fails before reaching SQS (a batch fails as a
  whole), even when `skipCompressionWhenLarger` would have sent the payload as is. Checksum-only sends aren't
  checked. Snappy's declared length is checked before the decoded output array is allocated. zstd's buffered
  streaming decoder accepts a frame's declared window only up to what the limit needs, rounded up to a power of two
  (at least 1 KiB), which caps the window, not native memory; until 0.7.0, when it refuses a frame's window (as for
  0.5.x's streamed `MAXIMUM` frames, which can declare 128 MiB), the payload is decoded again in one step into an
  output array of at most the limit, which zstd uses as its history instead of a separate window buffer.
- For [GHSA-xxph-c9ww-hj94](https://github.com/google/guava/security/advisories/GHSA-xxph-c9ww-hj94) and
  [GHSA-v5x9-67m3-7m56](https://github.com/google/guava/security/advisories/GHSA-v5x9-67m3-7m56): java-conventions
  `0.6.5` raised the Guava floor for Error Prone and Checkstyle to `33.8.0-jre`, later carried by `0.7.0`, which the
  build moved to during this release cycle. That covers those two build tools only. The published library declares no
  Guava dependency.
- Upgraded `org.xerial.snappy:snappy-java` from `1.1.10.8` to `1.1.10.12`. `1.1.10.10`, released on 2026-10-03 and
  adopted on 2026-10-07, is the first version with fixes for all seven advisories published in snappy-java's
  repository that day (out-of-bounds accesses, unbounded allocations, stream recursion and pooled-buffer reuse);
  CVE-2026-90559, one of them, was fixed in `1.1.10.9`.
- Upgraded `com.github.luben:zstd-jni` from `1.5.7-20` to `1.5.7-28`, for advisories published in zstd-jni's
  repository:
  - three published on 2026-10-28, fixed in `1.5.7-25` (GHSA-5p5w-hg9r-gx2p, GHSA-8v5f-66rc-52f5,
    GHSA-h5m7-4hv9-cvp3: memory-safety bugs in dictionary training and multi-dictionary decompression, which this
    library doesn't use);
  - one published on 2026-11-08, fixed in `1.5.7-26` (GHSA-8hfr-vm4w-9gfg: an integer underflow in FASTCOVER
    dictionary training, which this library doesn't use);
  - three published on 2026-11-14 (GHSA-779h-x4xj-2f4f; GHSA-c62c-wjcx-gjxx, a follow-up to the October
    multi-dictionary fix; GHSA-fh94-9rh7-3mwc), in response to which the project adopted `1.5.7-28`, whose tag
    contains the changes addressing them (the advisories don't all name a patched version).

  The moves to `1.5.7-25` and `1.5.7-28` were taken before their seven-day release cooldown ended.
- The workflows use `gradle/actions` `v6.4.1`, which fixes
  [GHSA-chpw-2mqm-95cc](https://github.com/gradle/actions/security/advisories/GHSA-chpw-2mqm-95cc): the dependency
  graph's submit job, which holds a write token, trusted the graph file handed over by the job that runs the build.

### Changed
- The build moved to java-conventions `0.7.0` (from `0.6.2`, through `0.6.5`), for newer build checks and its publishing workflow; no
  code changes were needed.
- The repository's copy of an early java-conventions pre-commit hook (`scripts/git-hooks/pre-commit`) is removed, so
  `installGitHooks` (run by `./gradlew build`) installs the conventions' bundled hook instead, when the conventions'
  installation rules allow it (they leave a configured `core.hooksPath` and hooks they don't recognize alone);
  java-conventions documents what it checks.
- The repository's own Checkstyle files (`config/checkstyle/`) are removed, so Checkstyle uses the conventions'
  configuration, which adds a Jackson 2 import rule to the removed one; Checkstyle passes without the removed
  suppressions. Without it, Spotless also orders imports like Checkstyle's `ImportOrder`; no file needed changes.
- The build no longer configures `JavaExec` tasks, which this library doesn't have.
- Upgraded the Gradle wrapper from `9.8.0` to `9.9.1`, and `software.amazon.awssdk:bom` (compile-only and tests) from
  `2.55.10` to `2.57.13`.
- `Compressor.decompress(byte[] payload, int maxLength)` is new, and the built-in compressors bound every decompression
  with it; a class that implements `Compressor` itself needs a compatible implementation of it for callers to use it.
  The one-argument `decompress(byte[])` is kept, deprecated, as a default method that the built-in compressors still
  declare, so existing calls from code built against 0.5.x still link; it applies the same refusal rules as
  `decompress(payload, maxLength)` with a 16 MiB limit (failing with `DecompressedPayloadTooLargeException`), where
  0.5.x had no limit.
- Fresh compressed sends always write the raw payload length (`l`), which 0.7.0 will require on compressed messages.
  `withIncludeRawPayloadLength(boolean)` is kept, deprecated, with no effect: `false` no longer omits `l`, so such
  clients' compressed messages carry `;l=` and its digits, which count toward SQS's size limit (the body and message
  attributes) and can take a message that filled it, or a batch that filled its total limit, over (SQS rejects a
  message or an oversized batch; check the per-entry results of batch sends). The metadata version stays `v=1`, so no producer has to wait
  for its consumers: once retained and incoming traffic passes the README's checks, clients can be upgraded from
  0.5.x, or rolled back to it, in any order (rolling back also needs every message that can reach a 0.5.x consumer to
  be one it reads; the README's "Upgrading from 0.5.x" says what to check).
- `CodecMetadataAttributeHandler.forOutbound(configuration, payloadBytes)` is new and writes `l` exactly when the
  configuration compresses; it refuses a configuration whose version isn't `1`, which readers would refuse. The
  three-argument form is kept, deprecated, ignores `includeRawPayloadLength`, and refuses such a version too (0.5.x
  wrote any version).
- A compressed payload that carries a usable `l` (a non-negative integer that fits in a Java `int`) must decode to
  exactly that length, on receive and when an already encoded message is validated before sending (0.5.x ignored a
  mismatch); a missing, blank, negative, non-numeric or overflowing `l` still reads as absent, and `l` is still
  ignored when `c=none`. The version, once trimmed, must be `1` or `1.<minor>` (see Added), spelled exactly (0.5.x also accepted other
  spellings of `1`, such as `01` or `+1`). A message this version refuses fails the whole receive, as any decoding
  error already did. The README's "Upgrading from 0.5.x" says what to check in retained messages, and "Before 0.7.0"
  what has to happen before `l` becomes required.
- Zstd compresses in one pass instead of streaming, so frames record their content size and declare a window no larger
  than their payload (0.5.x's streamed frames, of unknown size, declared the level's window: 128 MiB at `MAXIMUM`, even
  for a few bytes of input); 0.5.x reads them. Compressing in one pass holds an output buffer about the size of the raw payload.
  Until 0.7.0, when streaming refuses a frame's window as larger than the configured limit needs, as for such 0.5.x
  frames, the payload is decoded again in one step into an output array that starts at 1 MiB (or the limit, if
  smaller) and grows up to the limit (or a conservative Java array ceiling, if lower), which zstd uses as its history
  instead of a separate window buffer; 0.7.0 drops this retry (see the README's "Before 0.7.0"). So with the default
  16 MiB limit, 0.6.0 reads valid 0.5.x `MAXIMUM` frames whose decoded payload fits it, when the message also passes
  the metadata and payload checks. One-pass compression has an input ceiling just below 2 GiB. Because zstd bodies
  differ from 0.5.x's for the same payload, a FIFO queue with content-based deduplication can deliver an event twice
  when it's resubmitted through the other version's writer within the deduplication interval; the README's
  "Upgrading from 0.5.x" says how to avoid it.
- Tests and PIT load Mockito at JVM startup (`-javaagent`), avoiding the dynamic-attach warning JDK 21+ prints and
  environments that forbid dynamic attach.
- Removed the scheduled Dependabot version updates.
- Resolved dependency graph submission moved to its own workflow (`dependency-graph.yml`): one job builds the graph with
  read access only, and a separate job submits it, skipped for pull requests from forks.
- Publishing to GitHub Packages delegates to java-conventions' reusable publishing workflow, after `Build` and the
  legacy-JDK tests: it stages sqs-codec's publication in a read-only build and uploads it from a separate job that holds
  the package write token (java-conventions documents its checks). Dispatch final releases from their matching
  `vX.Y.Z` tag (the workflow enforces it) and, by project convention, SNAPSHOTs from `main`.

### Added
- Minor metadata versions: `v=1.<minor>` (`1.0`, `1.1`, ...; the minor is `0` or `[1-9][0-9]*`, ASCII digits only) is
  read as `v=1`, so that a later minor, which may only add what earlier readers can ignore, is meant not to break 0.6.0
  and later. Fresh sends still write `1`, and an already encoded message's version is sent unchanged; sending a minor
  needs every consumer to accept it (0.6.x accepts every minor; 0.5.x refuses them all). A later reader may require a minimum minor (with
  release notes, since that refuses what earlier readers accepted) and accepts every minor from that minimum through
  `2147483647`; this version requires none. The README's "Metadata versions" gives the rules.
- Public API for the bound and the raw length: `SqsCodecInterceptor.withMaxDecodedPayloadBytes(int)` and
  `DEFAULT_MAX_DECODED_PAYLOAD_BYTES`, `DecompressedPayloadTooLargeException`, `Compressor.decompress(byte[], int)`,
  and `CodecMetadataAttributeHandler.rawLength()` and `forOutbound(configuration, payloadBytes)` (see Changed).
- Added a `supply-chain` workflow that runs the pinned
  [leanish/leanish-development](https://github.com/leanish/leanish-development) supply-chain check on pull requests,
  pushes to `main`, a daily schedule and manual runs.

### Fixed
- A decoded (compressed or encoded) payload that isn't valid UTF-8 fails with `InvalidPayloadException` (once its usable `l`
  and any checksum have passed), on receive
  and when an already encoded message is validated before sending. It used to be delivered with the invalid bytes replaced by U+FFFD, after its
  checksum had passed on the original bytes. Bodies the interceptor encodes are always valid UTF-8, so only messages
  built elsewhere are affected; like any decoding error, such a message fails the whole receive.
- Upgrading: retained messages built outside the interceptor that don't decode to valid UTF-8
  must be corrected and replayed, or drained with 0.5.x consumers, before consumers move to this version; raising
  `maxDecodedPayloadBytes` doesn't help.

### Documentation
- `docs/ascii85.md` and the README said the experimental ASCII85 decoder accepts only the canonical form it writes. It
  also accepts other spellings of a final partial group that decode to the same bytes (`!"` for the byte `00`, whose
  form is `!!`); the docs now say so.
- The README's client examples say why the SDK's MD5 check has to be disabled on clients that receive decoded bodies
  (it would check SQS's MD5 of the wire body against the decoded one), and that this also turns off the SDK's other
  SQS MD5 checks, instead of calling it handled by the interceptor, the error-handling section covers
  `SqsAsyncClient`, and the note on refused messages follows SQS's visibility timeout and redrive policy.

## 0.5.1 - 2026-10-03

### Added
- Added PIT mutation testing (`./gradlew pitest`), excluding `integration`-tagged tests, with the conventions' 95 % mutation threshold.
  A separate, non-required "Mutation testing" workflow runs it on pull requests and `main`.

### Changed
- Upgraded `io.github.leanish.java-conventions` from `0.5.4` to `0.6.2`, which now configures PIT (including the 95 % threshold).
- Upgraded the Gradle wrapper from `9.4.1` to `9.8.0`.
- Upgraded dependencies:
  - `com.github.luben:zstd-jni` from `1.5.7-7` to `1.5.7-20`
  - `software.amazon.awssdk:bom` (compile-only and tests) from `2.42.36` to `2.55.10`
  - `org.mockito:mockito-bom` (tests) from `5.23.0` to `5.24.0`
- Upgraded the LocalStack test image from `3.0.2` to `4.14.0`, the last one that runs without an auth token.
- Upgraded GitHub Actions to `actions/checkout@v7` and `actions/setup-java@v6`.

### Documentation
- Fixed two README errors and removed a duplicated bullet from the README's defaults list.

## 0.5.0 - 2026-04-21

### Added
- Added configurable `CompressionLevel` support for `GZIP` and `ZSTD`, including
  `withCompressionLevel(...)` and `withoutCompressionLevel()` on `SqsCodecInterceptor`.
- Added experimental `ASCII85` payload encoding with an in-repo strict canonical implementation.
- Documented the ASCII85 dialect decisions and transport-focused scope in `docs/ascii85.md`.

### Changed
- Reintroduced `e` in `x-codec-meta` so payload encoding stays explicit in the wire format.
- Reintroduced `EncodingAlgorithm` and interceptor encoding configuration.
- Metadata readers now require explicit `e`; messages without it are rejected.

## 0.4.0 - 2026-04-18

### Changed
- Removed configurable payload encoding from the interceptor API.
- `SqsCodecInterceptor` now only configures compression and checksum.
- Compression now always applies unpadded URL-safe Base64 to compressed payload bytes.
- Decompression failures are now normalized to `CompressionException`, including native-library runtime failures.
- Checksum values are now emitted as unpadded URL-safe Base64.
- Added `includeRawPayloadLength` (default `true`) to control whether compressed messages emit raw length metadata (`l`).
- `x-codec-meta` now requires `v`, rejects no-op metadata (`c=none;h=none`), and omits `l` from canonical metadata when compression resolves to `none`.
- Canonical metadata no longer invents `l=0` when raw length is absent or invalid on read.
- Added `skipCompressionWhenLarger` (default `true`): when compressed output is not smaller, send the original payload and write `c=none`.
- When send-side processing resolves to `c=none;h=none`, the interceptor skips emitting `x-codec-meta`.

### Removed
- `EncodingAlgorithm` and encoding-specific interceptor configuration.
- Standard/no-op encoding implementations that were only used by configurable encoding.

### Notes
- This is an intentional breaking change while the library is still pre-release.
- Upgrading from `0.3.0` is not wire-compatible with messages still carrying the legacy `x-codec-conf` attributes; drain or rewrite queued messages before switching receivers to `0.4.x`.

## 0.3.0 - 2026-02-14

### Changed
- Adopted `io.github.leanish.java-conventions` and simplified build configuration.
- Continued dependency/tooling refreshes (Gradle wrapper, Spotless, NullAway, Error Prone, AssertJ, zstd-jni).
- Added dependency-management policy to ignore AWS SDK patch churn.

## v0.2.0-beta - 2026-01-14

### Changed
- Renamed project/artifact from `sqs-payload-codec` to `sqs-codec`.
- Renamed core types to the shorter API (`SqsPayloadCodecInterceptor` -> `SqsCodecInterceptor`, `PlayloadCodec` -> `Codec`).

### Dependency updates
- AWS SDK BOM `2.41.6` -> `2.41.7`.

## 0.2.0 - 2026-01-14

### Changed
- Collapsed multi-attribute codec configuration into a single SQS attribute (`x-codec-conf`) with key/value metadata.
- Improved receive-side behavior when `ReceiveMessageRequest` does not initially request all attributes.
- Added stricter double-encoding protection and validation for already codec-marked outbound messages.

### Dependency updates
- AWS SDK BOM `2.41.5` -> `2.41.6`.
- Mockito BOM `5.15.2` -> `5.21.0`.
- Lombok `1.18.36` -> `1.18.42`.
- JetBrains annotations `24.1.0` -> `26.0.2-1`.

## 0.1.0 - 2026-01-12

### Added
- Initial public release of `sqs-payload-codec`.
- AWS SDK v2 SQS execution interceptor for outbound encode/inbound decode.
- Compression algorithms: `ZSTD`, `SNAPPY`, `GZIP`, `NONE`.
- Encoding algorithms: `BASE64`, `BASE64_STD`, `NONE`.
- Checksum algorithms: `MD5`, `SHA256`, `NONE`.
- Initial metadata model using dedicated `x-codec-*` message attributes.
- Unit tests, integration tests, CI checks, coverage target, and publishing workflow.

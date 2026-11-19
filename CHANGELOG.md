# Changelog

All notable changes to this project are documented in this file.

## Unreleased

### Security
- Decompression is bounded. A small gzip or zstd payload could decode to gigabytes (1 GiB of zeros is about 33 KB with
  zstd and 1 MB with gzip) and exhaust a consumer's memory on receive. Decoding now stops with
  `DecompressedPayloadTooLargeException` as soon as it passes `maxDecodedPayloadBytes` (default 16 MiB, set with
  `withMaxDecodedPayloadBytes(...)`) or the payload's raw length, on receive and when validating already encoded
  outbound messages. Snappy's declared length is checked before it allocates, and zstd's decoder never allocates a
  window larger than the raw length (`v=2`) or the configured limit (`v=1`) needs, rounded up to a power of two (at
  least 1 KiB): a frame that declares a larger one is refused, unless zstd decodes it in a single step straight into
  its output, which allocates no window.
- The build tools no longer use a Guava affected by
  [GHSA-xxph-c9ww-hj94](https://github.com/google/guava/security/advisories/GHSA-xxph-c9ww-hj94) or
  [GHSA-v5x9-67m3-7m56](https://github.com/google/guava/security/advisories/GHSA-v5x9-67m3-7m56). sqs-codec doesn't
  depend on Guava: the published library and its runtime dependencies don't change.
- Upgraded `org.xerial.snappy:snappy-java` from `1.1.10.8` to `1.1.10.12`. `1.1.10.10`, the lowest stable version
  fixing all seven advisories published in snappy-java's repository on 2026-10-03, including CVE-2026-90559
  (out-of-bounds accesses, unbounded allocations, stream recursion and pooled-buffer reuse), was taken before the
  usual seven-day release cooldown.
- Upgraded `com.github.luben:zstd-jni` from `1.5.7-20` to `1.5.7-28`, which fixes seven advisories published in
  zstd-jni's repository:
  - three published on 2026-10-28, fixed in `1.5.7-25` (GHSA-5p5w-hg9r-gx2p, GHSA-8v5f-66rc-52f5,
    GHSA-h5m7-4hv9-cvp3: memory-safety bugs in dictionary training and multi-dictionary decompression, which this
    library doesn't use);
  - one published on 2026-11-08, fixed in `1.5.7-26` (GHSA-8hfr-vm4w-9gfg: an integer underflow in FASTCOVER
    dictionary training, which this library doesn't use);
  - three published on 2026-11-14, fixed in `1.5.7-28` (GHSA-779h-x4xj-2f4f, GHSA-c62c-wjcx-gjxx,
    GHSA-fh94-9rh7-3mwc).

  The moves to `1.5.7-25` and `1.5.7-28` were taken before the seven-day release cooldown, on the owner's decision.
- The workflows use `gradle/actions` `v6.4.1`, which fixes
  [GHSA-chpw-2mqm-95cc](https://github.com/gradle/actions/security/advisories/GHSA-chpw-2mqm-95cc): the dependency
  graph's submit job, which holds a write token, trusted the graph file handed over by the job that runs the build.

### Changed
- The build moved to java-conventions `0.7.0`, for the Guava fix above and newer build checks; no code changes were
  needed.
- The repository's copy of an early java-conventions pre-commit hook (`scripts/git-hooks/pre-commit`) is removed, so
  `installGitHooks` (run by `./gradlew build`) installs the conventions' current hook instead. Besides running Spotless
  and Checkstyle, it refuses a commit when a staged file differs from its working copy after formatting, including a
  partially staged file. With Git's default hooks path, the next build replaces an unmodified copy of the old hook
  installed by an earlier version, when no checksum record says otherwise.
- The repository's own Checkstyle files (`config/checkstyle/`) are removed, so Checkstyle uses the conventions'
  configuration. The removed one was the conventions' configuration without the Jackson 2 import rule, and its
  suppressions matched nothing here. Without it, Spotless also orders imports like Checkstyle's `ImportOrder`; no file
  needed changes.
- The build no longer configures `JavaExec` tasks, which this library doesn't have.
- Upgraded the Gradle wrapper from `9.8.0` to `9.9.1`, and `software.amazon.awssdk:bom` (compile-only and tests) from
  `2.55.10` to `2.57.13`.
- Breaking: `Compressor.decompress(byte[])` is now
  `decompress(byte[] payload, int maxLength)`, so no decompression path is unbounded.
- Breaking: `CodecMetadataAttributeHandler.forOutbound(configuration, payloadBytes, includeRawPayloadLength)` is now
  `forOutbound(configuration, payloadBytes)`, which writes `l` exactly when the configuration compresses.
  `CodecAttributes.VERSION_VALUE` is now `2`, and `LEGACY_VERSION_VALUE` (`1`) is new; code compiled against 0.5.x
  keeps the inlined `1` until it's recompiled.
- Zstd compresses in one pass instead of streaming, so frames record their content size and declare a window no larger
  than their payload (streamed frames declared the level's default window, 128 MiB at `MAXIMUM`, even for a few bytes).
  For `v=2`, a frame is never given a window larger than its `l` needs: one that declares a larger window is refused,
  unless zstd decodes it in a single step, which allocates none.
- Every compressed `v=2` payload carries its raw length (`l`), and must decode to exactly that length, on receive and when an
  already encoded message is validated before sending: one without a valid `l` fails, and an `l` over the limit fails
  before decompressing. `withIncludeRawPayloadLength` is gone. `l` is still ignored when `c=none`. A message over the
  limit, or whose `l` doesn't match, fails the whole receive, as any decoding error already did.
- Breaking: `SqsCodecInterceptor.defaultInterceptor()` is now `defaultInterceptor(MetadataVersion)`, with no default,
  so an upgrade can't start writing a version 0.5.x consumers refuse without anyone choosing it; code compiled against
  0.5.x that calls `defaultInterceptor()` fails with `NoSuchMethodError` and must be rebuilt. `MetadataVersion.V2`
  writes the new format; `V1` writes 0.5.x's `v=1`, with the same payload, checksum and `l`, for the upgrade, and is
  deprecated and removed in 0.8.0, while the argument stays required. `withOutboundMetadataVersion(...)` switches it.
  While writing `v=1`, a pre-encoded message whose otherwise valid metadata is `v=2` is refused on send.
- The metadata format is now `v=2`, the one above, which 0.5.x consumers refuse. `v=1` messages are still read,
  deprecated until 0.8.0: their `l` is optional and read leniently, the configured limit bounds their decompression,
  and a zstd frame streamed by 0.5.x whose window is larger than that limit needs is refused. Any other version
  fails, and a message this version refuses fails the whole receive. The README's "Upgrading from 0.5.x" gives the
  upgrade order (with `V1` first when writers and consumers form a cycle), what to check in retained messages, and the
  path to 0.8.0, through 0.6.x or 0.7.x.
- Tests and PIT load Mockito as a `-javaagent` instead of letting it attach itself at runtime, which JDK 21+ warns about
  and sandboxed environments refuse.
- Publishing to GitHub Packages now separates building from uploading. The build runs with read access only and stages
  sqs-codec's publication; a separate job holds the package write token and runs none of the repository's code. The
  upload job restricts publication paths to the configured coordinates and the expected files for the build's version,
  and rejects files it finds already published with different bytes. Publication still relies on the build's output and
  version information. Dispatch releases from their matching `vX.Y.Z` tag and SNAPSHOTs from `main`. Both jobs come from
  java-conventions' reusable publishing workflow.

### Added
- Added the [leanish/leanish-development](https://github.com/leanish/leanish-development) supply-chain gate as a
  `supply-chain` workflow: it compares each pull request's base and head against one advisory snapshot, and scans
  `main`.

### Fixed
- A decoded payload that isn't valid UTF-8 fails with `InvalidPayloadException`, on receive and when an already encoded
  message is validated before sending. It used to be delivered with the invalid bytes replaced by U+FFFD, after its
  checksum had passed on the original bytes. Bodies the interceptor encodes are always valid UTF-8, so only messages
  built elsewhere are affected; like any decoding error, such a message fails the whole receive.
- Upgrading: retained messages built outside the interceptor, `v=1` ones included, that don't decode to valid UTF-8
  must be corrected and replayed, or drained with 0.5.x consumers, before consumers move to this version; raising
  `maxDecodedPayloadBytes` doesn't help.

### Documentation
- `docs/ascii85.md` and the README said the experimental ASCII85 decoder accepts only the canonical form it writes. It
  also accepts other spellings of a final partial group that decode to the same bytes (`!"` for the byte `00`, whose
  form is `!!`); the docs now say so.

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

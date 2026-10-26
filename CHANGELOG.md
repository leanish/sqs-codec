# Changelog

All notable changes to this project are documented in this file.

## Unreleased

### Security
- Decompression is bounded. A small gzip or zstd payload could decode to gigabytes (1 GiB of zeros is about 33 KB with
  zstd and 1 MB with gzip) and exhaust a consumer's memory on receive. Decoding now stops with
  `DecompressedPayloadTooLargeException` as soon as it passes `maxDecodedPayloadBytes` (default 16 MiB, set with
  `withMaxDecodedPayloadBytes(...)`) or the payload's raw length, on receive and when validating already encoded
  outbound messages. Snappy's declared length is checked before it allocates, and zstd's decoder refuses a frame whose
  declared window is larger than the raw length needs before allocating it.
- Floor Guava at `33.7.2-jre` in Error Prone and Checkstyle configurations to address
  [GHSA-xxph-c9ww-hj94](https://github.com/google/guava/security/advisories/GHSA-xxph-c9ww-hj94), preserving the configured Checkstyle tool version.
- Upgraded `org.xerial.snappy:snappy-java` from `1.1.10.8` to `1.1.10.10`, the lowest stable version fixing all seven
  advisories published on 2026-10-03, including CVE-2026-90559 (out-of-bounds accesses, unbounded allocations,
  stream recursion and pooled-buffer reuse). This security fix skips the usual seven-day release cooldown.

### Changed
- Breaking, no compatibility with earlier versions kept: `Compressor.decompress(byte[])` is now
  `decompress(byte[] payload, int maxLength)`, so no decompression path is unbounded.
- Zstd compresses in one pass instead of streaming, so frames record their content size and declare a window no larger
  than their payload (streamed frames declared the level's default window, 128 MiB at `MAXIMUM`, even for a few bytes).
- Every compressed payload carries its raw length (`l`), and must decode to exactly that length, on receive and when an
  already encoded message is validated before sending: one without a valid `l` fails, and an `l` over the limit fails
  before decompressing. `withIncludeRawPayloadLength` is gone. `l` is still ignored when `c=none`. A message over the
  limit, or whose `l` doesn't match, fails the whole receive, as any decoding error already did.
- The metadata version is now `v=2`, the format above, written on every send. `v=1` messages (0.5.x) are still read,
  deprecated until 0.7.0: their `l` is optional and read leniently, and the configured limit bounds their decompression
  (a 0.5.x zstd frame whose window is larger than it is refused). Any other version fails.
- Tests and PIT load Mockito as a `-javaagent` instead of letting it attach itself at runtime, which JDK 21+ warns about
  and sandboxed environments refuse.

### Added
- Added the [leanish/supply-chain](https://github.com/leanish/supply-chain) gate as a `supply-chain` workflow: it checks every
  pull request against one advisory snapshot of base and head, scans `main` on pushes and daily, and rescans open pull requests daily.

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

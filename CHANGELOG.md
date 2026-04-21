# Changelog

All notable changes to this project are documented in this file.

## Unreleased

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

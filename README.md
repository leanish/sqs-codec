# sqs-codec

AWS SDK v2 execution interceptor for SQS that compresses and encodes message
bodies, stores codec metadata in a single message attribute, and reverses it on
receive.

When compression is enabled and encoding is left at `NONE`, the compressed
binary bytes are encoded as unpadded URL-safe Base64.

## Features
- Compression: `ZSTD`, `SNAPPY`, `GZIP`, `NONE`
- Compression levels: `MINIMUM`, `LOW`, `MEDIUM`, `HIGH`, `MAXIMUM`
- Encoding: `BASE64`, `ASCII85` (experimental), `NONE`
- Checksums: `MD5`, `SHA256`, `NONE`
- Config-driven compression/encoding/checksum on send
- Metadata-driven decompression/validation on receive (using message attribute `x-codec-meta`)

## Usage

Gradle:
```kotlin
dependencies {
    implementation("io.github.leanish:sqs-codec:<version>")
}
```

Java:

`defaultInterceptor(...)` takes the codec metadata version that sends write, with no default: `MetadataVersion.V2`,
unless a consumer of the queues still runs 0.5.x (then `V1`; see [Upgrading from 0.5.x](#upgrading-from-05x)).
Receiving reads both.

Send with defaults (no compression, MD5 checksum); decode/validate based on metadata:
```java
SqsAsyncClient client = SqsAsyncClient.builder()
        .overrideConfiguration(config -> config.addExecutionInterceptor(SqsCodecInterceptor.defaultInterceptor(MetadataVersion.V2)))
        .checksumValidationEnabled(false) // handled by SqsCodecInterceptor
        .build();
```

Send with explicit Zstd compression + default MD5 checksum; decode/validate based on metadata:
```java
SqsAsyncClient client = SqsAsyncClient.builder()
        .overrideConfiguration(config -> config.addExecutionInterceptor(SqsCodecInterceptor.defaultInterceptor(MetadataVersion.V2)
                .withCompressionAlgorithm(CompressionAlgorithm.ZSTD)))
        .checksumValidationEnabled(false) // handled by SqsCodecInterceptor
        .build();
```

Send with explicit compression and checksum; decode/validate based on metadata:
```java
SqsClient client = SqsClient.builder()
        .overrideConfiguration(config -> config.addExecutionInterceptor(SqsCodecInterceptor.defaultInterceptor(MetadataVersion.V2)
                .withCompressionAlgorithm(CompressionAlgorithm.GZIP)
                .withCompressionLevel(CompressionLevel.HIGH)
                .withChecksumAlgorithm(ChecksumAlgorithm.SHA256)))
        .checksumValidationEnabled(false) // handled by SqsCodecInterceptor
        .build();
```

Send without compression but with explicit Base64 payload encoding:
```java
SqsCodecInterceptor interceptor = SqsCodecInterceptor.defaultInterceptor(MetadataVersion.V2)
        .withCompressionAlgorithm(CompressionAlgorithm.NONE)
        .withEncodingAlgorithm(EncodingAlgorithm.BASE64)
        .withChecksumAlgorithm(ChecksumAlgorithm.NONE);
```

Send with explicit experimental ASCII85 payload encoding:
```java
SqsCodecInterceptor interceptor = SqsCodecInterceptor.defaultInterceptor(MetadataVersion.V2)
        .withEncodingAlgorithm(EncodingAlgorithm.ASCII85);
```

ASCII85 design notes: see [`docs/ascii85.md`](docs/ascii85.md).

Defaults:
- Compression: `NONE`
- Compression level: unset
- Encoding: `NONE`
- Checksum: `MD5`
- When compression is enabled and encoding remains `NONE`, the effective payload encoding is `BASE64`.
- `ASCII85` is experimental; it's written in a canonical dialect without `<~ ~>` framing, whitespace, or `z`/`y` shorthands, which the decoder also refuses.
- `ASCII85` is intentionally transport-focused between `sqs-codec` endpoints rather than interoperability-focused with third-party ASCII85 tools.
- `ASCII85` reduces encoded-body overhead versus Base64 in theory, but the practical size win is small enough that it should be treated as a niche transport option rather than a new default.
- `MD5` and `SHA256` detect accidental corruption. They're unkeyed, so neither authenticates the producer: whoever can send to the queue can attach a matching checksum to any body, or send `h=none`. Control who can send with the queue's access policy.
- `skipCompressionWhenLarger`: `true`
- `maxDecodedPayloadBytes`: `16 MiB` (`SqsCodecInterceptor.DEFAULT_MAX_DECODED_PAYLOAD_BYTES`)
- Outbound metadata version: none; `defaultInterceptor(...)` requires one, and `withOutboundMetadataVersion(...)`
  changes it.
- The interceptor rejects a decoded payload over `maxDecodedPayloadBytes` with `DecompressedPayloadTooLargeException`, wherever it decodes one: on receive, and when it validates an already encoded message before sending. Decompression stops as soon as it passes the limit, so a small compressed message can't expand without bound; an encoding-only payload is bounded by its encoded size, and its decoded bytes are checked once decoded. Set it with `withMaxDecodedPayloadBytes(...)`; it must be positive. It bounds only what the interceptor decodes, that is compressed or encoded (`e` not `none`) payloads: a checksum-only (`c=none,e=none`) or metadata-free body is returned as received, and a fresh outbound body isn't checked against it. A producer can therefore send a compressed message whose `l` is over a consumer's limit, and that consumer refuses it; keep consumers' limits at least as large as what producers send.
- Every compressed `v=2` payload declares its raw length (`l`) and must decode to exactly that many bytes, on receive and when an already encoded message is validated before sending. A compressed `v=2` payload without a valid `l` fails with `UnsupportedCodecMetadataException`; an `l` over the limit fails with `DecompressedPayloadTooLargeException` before decompressing; decompression stops with `DecompressedPayloadTooLargeException` as soon as it passes `l`, and a payload that decodes to fewer bytes fails with `CompressionException`. `l` is ignored when `c=none`.
- A message that fails these checks fails the whole receive, as any decoding error does: it isn't deleted, so SQS redelivers it until a dead-letter queue takes it (once it reaches the queue's
  `maxReceiveCount`) or its retention period ends.
- The limit bounds decoded bytes, not total memory: each received message also becomes a `String`, and a receive returns up to ten.
- A decoded payload must be valid UTF-8, since it becomes the message's `String` body; one that isn't fails with `InvalidPayloadException`, on receive and when an already encoded message is validated before sending.
- Zstd compresses in one pass, so each frame records its content size and declares a window no larger than its payload. For `v=2`, the decoder never allocates a window larger than `l` needs (rounded up to a power of two, at least 1 KiB): a frame that declares a larger one is refused with `DecompressedPayloadTooLargeException`, unless zstd decodes it in a single step straight into its output, which allocates no window; the output stays bounded by `l` either way. Snappy's declared length is checked against `l` (`maxDecodedPayloadBytes` for `v=1`) before it allocates.
- `DecompressedPayloadTooLargeException` means the payload was refused against the limit in force (`maxDecodedPayloadBytes` for an encoded, uncompressed payload (`c=none`, `e` not `none`) or an `l` over it, `l` itself for a compressed `v=2` one, `maxDecodedPayloadBytes` for a compressed `v=1` one): decoding produced more than it, a declared length is over it, or a zstd frame's window is larger than it needs. It doesn't establish the payload's actual decoded size.
- When `withSkipCompressionWhenLarger(true)` (default) and compression is enabled, if the compressed and encoded body would be the same size as the original body or larger, the interceptor sends the original body and falls back to `c=none,e=none`.
- When outbound processing resolves to `c=none`, `e=none` and `h=none`, the interceptor does not add `x-codec-meta`.
- Compression level is local send-side configuration only; it does not change codec metadata.
- When unset, each compression algorithm uses its own built-in default.
- `GZIP` and `ZSTD` honor configured compression levels. `SNAPPY` and `NONE` reject them.

Disable `skipCompressionWhenLarger` and always use configured compression:
```java
SqsCodecInterceptor interceptor = SqsCodecInterceptor.defaultInterceptor(MetadataVersion.V2)
        .withSkipCompressionWhenLarger(false);
```

Tune compression effort with a named level:
```java
SqsCodecInterceptor interceptor = SqsCodecInterceptor.defaultInterceptor(MetadataVersion.V2)
        .withCompressionAlgorithm(CompressionAlgorithm.ZSTD)
        .withCompressionLevel(CompressionLevel.HIGH);
```

Clear a configured compression level and go back to the algorithm default:
```java
SqsCodecInterceptor interceptor = SqsCodecInterceptor.defaultInterceptor(MetadataVersion.V2)
        .withCompressionAlgorithm(CompressionAlgorithm.ZSTD)
        .withCompressionLevel(CompressionLevel.HIGH)
        .withoutCompressionLevel();
```

## Attributes

Codec metadata is stored in a single attribute:
- `x-codec-meta` (String), for example: `v=2;c=zstd;e=base64;h=md5;s=t2tngCwK9b7C9eqVQunqfg;l=12`

Keys:
- `v`: codec version
- `c`: compression (`zstd`, `gzip`, `snappy`, `none`)
- `e`: payload encoding (`base64`, `ascii85`, `none`)
- `h`: checksum (`md5`, `sha256`, `none`)
- `s`: checksum value (present only when `h` is not `none`; always unpadded URL-safe Base64)
- `l`: raw payload byte length (before compression); written only when `c` is not `none`

Notes:
- Order does not matter; metadata keys and algorithm ids are case-insensitive. Checksum value `s` is always an unpadded URL-safe Base64 string and is independent of `e`.
- `ascii85` is emitted only in its canonical form: unframed ASCII85, no whitespace, and no `z`/`y` shorthand blocks. The decoder refuses framing, whitespace and shorthands, but accepts other spellings of a final partial group that decode to the same bytes ([details](docs/ascii85.md)).
- Messages built outside this interceptor, `v=1` ones included, must decode to valid UTF-8 (0.5.x accepted ones that
  don't; see [Upgrading from 0.5.x](#upgrading-from-05x)).
- `v` is required, exactly `2` or `1`. The interceptor writes the configured `MetadataVersion` and reads both: `1`
  (what 0.5.x writes) is deprecated until 0.7.0. A `v=1` payload may lack `l` or carry one that isn't a number (it's
  then ignored), but a valid `l` must match. Its decompression is bounded by `maxDecodedPayloadBytes`, since 0.5.x
  streamed zstd frames with the level's window: a frame whose window is larger than that limit needs (rounded up to a
  power of two, at least 1 KiB) is refused, so a 3 MiB limit admits a 4 MiB window. An already encoded `v=1` message
  sent through the interceptor is validated the same way and sent unchanged. Any other version fails with
  `UnsupportedCodecMetadataException`.
- Missing `c` or `h` defaults to `none` on read.
- `e` is required.
- Explicit `e=none` is valid only when `c=none`.
- Metadata is invalid when `c`, `e`, and `h` all resolve to `none`.
- On send, the default interceptor configuration uses checksum `md5`.
- On send, the default interceptor configuration uses encoding `none`, which resolves to `base64` when compression is enabled.
- `s` is required when `h` is not `none`.
- `s` must be absent when `h=none`.
- `s` is emitted as unpadded URL-safe Base64 on send.
- If checksum values are produced or compared outside this library, use the canonical unpadded form; padded values are not normalized on read.
- `l` is required for `v=2`, a non-negative byte count, when `c` is not `none`, and ignored when `c=none`; optional for `v=1`.
- On send, `l` is emitted exactly when compression is actually used (`c!=none` in final metadata).
- Unknown keys are ignored for forward compatibility.
- When `x-codec-meta` is already present on send, the interceptor validates that body and checksum match the declared metadata before skipping re-encoding.
- If send-side processing (including `skipCompressionWhenLarger`) ends with `c=none`, `e=none` and `h=none`, no metadata attribute is emitted.

Outbound metadata emission matrix (final per-message decision):
- `c=none,e=none,h=none`: no `x-codec-meta`
- `c=none,e=none,h!=none`: `v;c;e;h;s` (no `l`)
- `c=none,e!=none,h=none`: `v;c;e;h` (no `l`)
- `c=none,e!=none,h!=none`: `v;c;e;h;s` (no `l`)
- `c!=none,h=none`: `v;c;e;h;l`
- `c!=none,h!=none`: `v;c;e;h;s;l`

SQS attribute limit:
- SQS supports at most 10 message attributes per message.
- `sqs-codec` adds at most one attribute (`x-codec-meta`) and skips it for no-op messages (`c=none,e=none,h=none`).
- The interceptor fails fast on send when the final attribute count would exceed the SQS limit.

## Upgrading from 0.5.x

A 0.5.x consumer refuses `v=2` ("Unsupported codec version: 2") and fails its whole receive; this version reads both
`v=1` and `v=2`. So a queue's writers may write `MetadataVersion.V2` once all its consumers run this version:

- Without cycles, upgrade consumers first, with `V2` throughout: in a pipeline `A` → `B` → `C`, upgrade `C`, then `B`,
  then `A`. A send-only client whose consumers already run this version uses `V2` from the start.
- When writers and consumers form a cycle (services that send to each other, or a client that sends to a queue it
  also consumes), no order works, so upgrade in two steps:
  1. Deploy this version everywhere with `MetadataVersion.V1`. It writes `v=1` with the same payload, checksum and `l`
     as `v=2`, which 0.5.x consumers read, so clients can be upgraded, or rolled back to 0.5.x, in any order.
  2. Once every consumer of every queue runs this version, switch every writer to `V2`, in any order (recreating
     clients that read the setting only when they're built).

Read the version from a setting that's required, so step 2 can be a configuration change; falling back to `V2` when
it's missing would bring back the silent switch the required argument prevents:
```java
// config: your application's configuration, failing when the setting is missing
SqsCodecInterceptor interceptor = SqsCodecInterceptor.defaultInterceptor(
                MetadataVersion.valueOf(config.getRequiredString("sqs.codec.metadata-version")))
        .withCompressionAlgorithm(CompressionAlgorithm.ZSTD);
```

This version reads `v=1` more strictly than 0.5.x, and one message it refuses fails the whole receive. Before
replacing consumers, check retained messages, and traffic from 0.5.x clients that keep sending during the upgrade:
- the payload must decode to valid UTF-8;
- a numeric, non-negative `l` must equal the decoded length (raising the limit doesn't bypass this);
- the version, once trimmed, must be exactly `1` (0.5.x also accepted `01` or `+1`; it always wrote `1`, so only
  metadata built elsewhere is affected);
- zstd frames must fit the window `maxDecodedPayloadBytes` allows: 0.5.x's `MAXIMUM` level declares 128 MiB, refused
  under the 16 MiB default. To read such frames, set `withMaxDecodedPayloadBytes(128 * 1024 * 1024)` (which also raises
  the decoded-output allowance, so account for memory) until that traffic has stopped and been drained.

Pause producers and drain incompatible messages with 0.5.x consumers, or correct and replay them, before cutover.

While writing `v=1`, a pre-encoded message whose otherwise valid metadata is `v=2` is refused on send with
`UnsupportedCodecMetadataException`, and so is the whole batch it's in: correct that entry before retrying.

To roll back to 0.5.x after step 2, switch every writer back to `V1` (or stop the `v=2` writers), drain the queued
`v=2` messages with this version, counting delayed and in-flight messages and any dead-letter queue or replay that
can bring them back, and only then bring back 0.5.x consumers.

0.7.0 drops `MetadataVersion.V1` and reading `v=1`, so upgrade from 0.5.x through 0.6.x, never straight to 0.7.0.
Before moving to it, complete step 2, remove or update any stored `V1` setting (`valueOf("V1")` fails at runtime once
the constant is gone), stop or convert any source of pre-encoded `v=1` messages, and drain the retained `v=1` ones.
The version argument stays required, so `defaultInterceptor(MetadataVersion.V2)` keeps working, and a later version
is added without changing calls that pass `V2`.

## Error handling

All codec failures extend `CodecException`, with subtypes for targeted
responses.

On receive, the SDK reports a failure in the interceptor as an `SdkClientException` ("Unable to unmarshall
response"), with the codec exception in its cause chain, so catch that and look for the `CodecException`:
```java
try {
    ReceiveMessageResponse response = client.receiveMessage(request);
    // use decoded payloads
} catch (SdkClientException e) {
    Throwable cause = e;
    while (cause != null && !(cause instanceof CodecException)) {
        cause = cause.getCause();
    }
    if (cause instanceof InvalidPayloadException) {
        // bad payload data, consider DLQ or logging
    } else if (cause instanceof DecompressedPayloadTooLargeException) {
        // refused: too much output, a declared length over the limit, or a zstd window too large for it
    } else if (cause instanceof CompressionException) {
        // payload decompression failed
    } else if (cause instanceof ChecksumValidationException checksumFailure) {
        // missing algorithm/attribute or checksum mismatch; inspect checksumFailure.detail()
    } else if (cause instanceof UnsupportedCodecMetadataException) {
        // malformed/duplicate/unsupported codec metadata
    } else if (cause instanceof UnsupportedAlgorithmException) {
        // unsupported compression/checksum values
    } else if (cause instanceof CodecException) {
        // any other codec issue
    } else {
        throw e; // not a codec failure
    }
}
```

## Development

Build target is Java 17 bytecode.
Toolchain comes from `java-conventions` (default compile/runtime JDK 25).
CI (`ci.yml`) runs full `build` on JDK 25.
The [leanish/leanish-development](https://github.com/leanish/leanish-development) supply-chain gate (`supply-chain.yml`) checks PRs for new advisories, young versions, and unpinned new actions, comparing base and head against one advisory snapshot, and scans `main`.
Legacy runtime checks (`testing-legacy-jdk.yml`) run tests on JDK 17 and 21, and can be run manually or are required by publishing.
The build, mutation testing, GitHub publishing, and legacy-JDK workflows use GitHub-hosted `ubuntu-26.04` runners.
Publishing (`publishing-github.yml`, run by hand: releases from their `vX.Y.Z` tag, SNAPSHOTs from `main`) keeps the build away from the package write token: after the checks above, the build runs with read access only and stages the publication in `build/publication-staging` (the build's `Staging` repository), then a job that runs none of the repository's code uploads only sqs-codec's artifacts and checksums for that version. Both jobs come from [java-conventions' reusable publishing workflow](https://github.com/leanish/java-conventions#plugin-project-ci-maintainers). Publication still relies on the build's output and version information.

Run full checks (tests, checkstyle, spotless, jacoco):
```bash
./gradlew check
```

Run mutation testing (PIT, excludes `integration`-tagged tests and fails below the conventions' 95 % mutation threshold; report in `build/reports/pitest`):
```bash
./gradlew pitest
```

CI runs it on pull requests and `main` in its own "Mutation testing" workflow. Its `PIT` job is a required check, like `Build`: a pull request merges only once both pass.

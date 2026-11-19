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

Send with defaults (no compression, MD5 checksum); decode/validate based on metadata:
```java
SqsAsyncClient client = SqsAsyncClient.builder()
        .overrideConfiguration(config -> config.addExecutionInterceptor(SqsCodecInterceptor.defaultInterceptor()))
        .checksumValidationEnabled(false) // handled by SqsCodecInterceptor
        .build();
```

Send with explicit Zstd compression + default MD5 checksum; decode/validate based on metadata:
```java
SqsAsyncClient client = SqsAsyncClient.builder()
        .overrideConfiguration(config -> config.addExecutionInterceptor(SqsCodecInterceptor.defaultInterceptor()
                .withCompressionAlgorithm(CompressionAlgorithm.ZSTD)))
        .checksumValidationEnabled(false) // handled by SqsCodecInterceptor
        .build();
```

Send with explicit compression and checksum; decode/validate based on metadata:
```java
SqsClient client = SqsClient.builder()
        .overrideConfiguration(config -> config.addExecutionInterceptor(SqsCodecInterceptor.defaultInterceptor()
                .withCompressionAlgorithm(CompressionAlgorithm.GZIP)
                .withCompressionLevel(CompressionLevel.HIGH)
                .withChecksumAlgorithm(ChecksumAlgorithm.SHA256)))
        .checksumValidationEnabled(false) // handled by SqsCodecInterceptor
        .build();
```

Send without compression but with explicit Base64 payload encoding:
```java
SqsCodecInterceptor interceptor = SqsCodecInterceptor.defaultInterceptor()
        .withCompressionAlgorithm(CompressionAlgorithm.NONE)
        .withEncodingAlgorithm(EncodingAlgorithm.BASE64)
        .withChecksumAlgorithm(ChecksumAlgorithm.NONE);
```

Send with explicit experimental ASCII85 payload encoding:
```java
SqsCodecInterceptor interceptor = SqsCodecInterceptor.defaultInterceptor()
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
- `legacyMetadataWrites`: `false` (transitional, removed in 0.7.0; see "Upgrading from 0.5.x" below)
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
SqsCodecInterceptor interceptor = SqsCodecInterceptor.defaultInterceptor()
        .withSkipCompressionWhenLarger(false);
```

Tune compression effort with a named level:
```java
SqsCodecInterceptor interceptor = SqsCodecInterceptor.defaultInterceptor()
        .withCompressionAlgorithm(CompressionAlgorithm.ZSTD)
        .withCompressionLevel(CompressionLevel.HIGH);
```

Clear a configured compression level and go back to the algorithm default:
```java
SqsCodecInterceptor interceptor = SqsCodecInterceptor.defaultInterceptor()
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
- Messages built outside this interceptor, `v=1` ones included, must decode to valid UTF-8; 0.5.x accepted ones that don't, and this version refuses them. Before upgrading consumers, correct and replay such retained messages, or drain them with 0.5.x consumers; raising `maxDecodedPayloadBytes` doesn't help.
- `v` is required, exactly `2` or `1`. Metadata the interceptor writes is `2`, or `1` while legacy metadata writes are
  enabled (see "Upgrading from 0.5.x" below). Reading accepts `2` and, deprecated
  until 0.7.0, `1` (what 0.5.x writes):
  a `v=1` payload may lack `l` or carry one that isn't a number (it's then ignored), and its decompression is bounded by
  `maxDecodedPayloadBytes` rather than by `l`, since 0.5.x streamed zstd frames with the level's window; a valid `l` it
  declares must still match. A 0.5.x zstd frame whose window is larger than `maxDecodedPayloadBytes` needs, rounded up
  to a power of two (at least 1 KiB), is refused: the `MAXIMUM` level's 128 MiB under the 16 MiB default, say. A 3 MiB
  limit admits a 4 MiB window; the output stays bounded by the limit either way. An already encoded `v=1` message sent through the interceptor is validated the same
  way and sent unchanged. Any other version fails with `UnsupportedCodecMetadataException`.
- Upgrading from 0.5.x: before replacing consumers, check that retained compressed `v=1` messages fit the new
  consumers' decoded-size and zstd-window limits. A numeric, non-negative declared `l` must equal the decoded byte
  length; 0.5.x ignored mismatches, while this version rejects them. Raising the limit does not bypass that check. The
  version, once trimmed, must be exactly `1`: 0.5.x also accepted spellings such as `01` or `+1`, which this version
  refuses. 0.5.x itself always wrote `1`, so only metadata built elsewhere is affected.

  One incompatible message fails the whole receive (see above), so if retained messages are incompatible, pause
  producers and drain them with 0.5.x consumers, or arrange their correction and replay before cutover. To consume
  legacy zstd `MAXIMUM` frames with this version, temporarily configure `withMaxDecodedPayloadBytes(128 * 1024 * 1024)`
  before receiving them. This admits their 128 MiB window and also raises the decoded-output allowance, so account for
  consumer memory. Restore the intended limit only after incompatible legacy traffic has stopped and those messages are
  drained.

  A 0.5.x consumer refuses `v=2` ("Unsupported codec version: 2") and fails its whole receive, while this version reads
  both. So a client writes `v=2` only once every consumer of its queues runs this version. Upgrading consumers before
  producers achieves that only when no client does both: when the same clients send and receive, or services send to
  each other, no order works on its own. Upgrade in two steps instead:

  1. Deploy this version everywhere with `withLegacyMetadataWrites(true)`. Fresh sends keep writing `v=1` (with the
     same payload, checksum and `l`), which 0.5.x consumers read, and every upgraded client reads both versions, so
     clients can be upgraded, or rolled back to 0.5.x, in any order.
  2. Once every consumer of every queue runs this version, set it to `false` on every running writer, in any order
     (recreate clients where the setting is read only when they're built). Fresh sends then write `v=2`.

  Pass the setting from your own configuration, so that step 2 can be a configuration change:
  ```java
  SqsCodecInterceptor interceptor = SqsCodecInterceptor.defaultInterceptor()
          .withCompressionAlgorithm(CompressionAlgorithm.ZSTD)
          .withLegacyMetadataWrites(config.getBoolean("sqs.codec.legacy-metadata-writes"));
  ```

  While it's enabled, a pre-encoded message whose metadata isn't `v=1` is refused on send with
  `UnsupportedCodecMetadataException`, and so is the whole batch it's in: correct that entry before retrying.

  To roll back to 0.5.x after step 2, first enable it again on every writer (or stop the `v=2` writers), then drain
  the queued `v=2` messages with consumers of this version, counting delayed and in-flight messages and any
  dead-letter queue or replay that can bring them back, and only then bring back 0.5.x consumers. 0.7.0 removes the
  option and stops reading `v=1`: before moving to it, complete step 2, stop or convert any source of pre-encoded `v=1`
  messages, and drain the retained `v=1` ones the same way.
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

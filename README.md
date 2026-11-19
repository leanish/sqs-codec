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
        // Required: the SDK would check SQS's MD5 of the encoded body against the decoded one. The interceptor checks
        // its own checksum (h) over the payload instead, when one is set.
        .checksumValidationEnabled(false)
        .build();
```

Send with explicit Zstd compression + default MD5 checksum; decode/validate based on metadata:
```java
SqsAsyncClient client = SqsAsyncClient.builder()
        .overrideConfiguration(config -> config.addExecutionInterceptor(SqsCodecInterceptor.defaultInterceptor()
                .withCompressionAlgorithm(CompressionAlgorithm.ZSTD)))
        .checksumValidationEnabled(false) // required, as above
        .build();
```

Send with explicit compression and checksum; decode/validate based on metadata:
```java
SqsClient client = SqsClient.builder()
        .overrideConfiguration(config -> config.addExecutionInterceptor(SqsCodecInterceptor.defaultInterceptor()
                .withCompressionAlgorithm(CompressionAlgorithm.GZIP)
                .withCompressionLevel(CompressionLevel.HIGH)
                .withChecksumAlgorithm(ChecksumAlgorithm.SHA256)))
        .checksumValidationEnabled(false) // required, as above
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
- `ASCII85` encodes four bytes as five characters, where Base64 encodes three as four, so it's somewhat smaller; treat it as a niche transport option rather than a new default.
- `MD5` and `SHA256` detect accidental corruption. They're unkeyed, so neither authenticates the producer: whoever can send to the queue can attach a matching checksum to any body, or send `h=none`. Control who can send with the queue's access policy.
- `skipCompressionWhenLarger`: `true`
- `maxDecodedPayloadBytes`: `16 MiB` (`SqsCodecInterceptor.DEFAULT_MAX_DECODED_PAYLOAD_BYTES`)
- The interceptor rejects a decoded payload over `maxDecodedPayloadBytes` with `DecompressedPayloadTooLargeException`, wherever it decodes one: on receive, and when it validates an already encoded message before sending. The streaming decoders check the output after each read (of up to 8 KiB) and refuse a read that would pass the limit, so a small compressed message can't expand without bound; an encoding-only payload is bounded by its encoded size, and its decoded bytes are checked once decoded. Set it with `withMaxDecodedPayloadBytes(...)`; it must be positive. It bounds only what the interceptor decodes, that is compressed or encoded (`e` not `none`) payloads: a checksum-only (`c=none,e=none`) or metadata-free body is returned as received, and a fresh outbound body isn't checked against it. A producer can therefore send a compressed message whose `l` is over a consumer's limit, and that consumer refuses it; keep consumers' limits at least as large as what producers send.
- A compressed payload's raw length (`l`) is the sender's claim, so it never bounds decompression: `maxDecodedPayloadBytes` does. When `l` is usable (a non-negative integer that fits in a Java `int`), the payload must decode to exactly that many bytes, or it fails with `CompressionException`, on receive and when an already encoded message is validated before sending. 0.6.x still decodes a compressed payload whose `l` is missing or not a usable number, as 0.5.x writers could send; 0.7.0 will refuse it (see [Before 0.7.0](#before-070)). `l` is ignored when `c=none`.
- A message that fails these checks fails the whole receive, as any decoding error does: it isn't deleted, so it can be
  received again once its visibility timeout ends, until the queue's retention period expires it or, if the queue has a
  redrive policy, it moves to the dead-letter queue (after `maxReceiveCount` receives).
- The limit bounds decoded bytes, not total memory: each received message also becomes a `String`, and a receive returns up to ten.
- A decoded payload (compressed or encoded) must be valid UTF-8, since it becomes the message's `String` body; one that isn't fails with `InvalidPayloadException`, on receive and when an already encoded message is validated before sending. A checksum-only or metadata-free body isn't decoded, so it isn't checked.
- Zstd compresses in one pass, so each frame records its content size and declares a window no larger than its payload. The streaming decoder accepts a window up to what `maxDecodedPayloadBytes` needs (rounded up to a power of two, at least 1 KiB, so a 3 MiB limit admits a 4 MiB window). Until 0.7.0, a frame that declares a larger one, as 0.5.x's streamed frames do whatever their size (128 MiB at the `MAXIMUM` level), is decoded again in one step into a buffer that starts at 1 MiB (or the limit, if smaller) and grows, decoding again, up to the limit: zstd then uses that output as its window and allocates none. The output stays bounded by the limit either way. Snappy refuses a header it can't read itself (`CompressionException`); a declared length over `maxDecodedPayloadBytes` is refused before it allocates.
- `DecompressedPayloadTooLargeException` means the payload was refused against `maxDecodedPayloadBytes`: decoding produced more than it, or snappy's declared length is over it. It doesn't establish the payload's actual decoded size.
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
- `x-codec-meta` (String), for example: `v=1;c=zstd;e=base64;h=md5;s=t2tngCwK9b7C9eqVQunqfg;l=12`

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
- Compressed or encoded messages built outside this interceptor must decode to valid UTF-8 (0.5.x accepted ones that don't; see
  [Upgrading from 0.5.x](#upgrading-from-05x)).
- `v` is required: `1`, which is what the interceptor writes, or a minor of it, `1.<minor>` (`1.0`, `1.1`, ...),
  read as `1` (see [Metadata versions](#metadata-versions)). It's matched exactly as written once trimmed: `01`, `+1`
  (other spellings of `1` that 0.5.x accepted), `1.01` or `2` fail with `UnsupportedCodecMetadataException`.
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
- `l`, when `c` is not `none` and usable (a value `Integer.parseInt` reads, once trimmed, as a non-negative `int`, so
  `+12` or `012` count as `12`), is a byte count the decoded payload must match. A missing, blank, negative, non-numeric or overflowing `l` reads as absent, as in 0.5.x,
  and such a payload decodes under `maxDecodedPayloadBytes` alone; 0.7.0 will refuse a compressed payload without a
  usable `l`. `l` is ignored when `c=none`.
- On a fresh send, `l` is emitted exactly when compression is actually used (`c!=none` in final metadata). The deprecated
  `withIncludeRawPayloadLength(boolean)`, kept for 0.5.x callers, no longer changes that.
- Unknown keys are ignored for forward compatibility, once the metadata passes the syntax checks: `key=value` pairs
  separated by `;`, each key at most once, ignoring case (a duplicate key fails with
  `UnsupportedCodecMetadataException`).
- When `x-codec-meta` is already present on send, the interceptor validates that body and checksum match the declared metadata before skipping re-encoding.
- If send-side processing (including `skipCompressionWhenLarger`) ends with `c=none`, `e=none` and `h=none`, no metadata attribute is emitted.

Outbound metadata emission matrix for fresh sends (final per-message decision; an already encoded message is sent
with its metadata unchanged):
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

### Metadata versions

`v` is `<major>` or `<major>.<minor>`, where the minor is `0` or an ASCII number without a leading zero (`1.0`, `1.1`,
`1.10`; not `1.01`, `1.+1` or `1.`). This version writes `1` and reads `1` and every such `1.<minor>` as `1`, so that
a later minor is meant not to break it:
- A minor may only add what a reader of an earlier `1.x` can ignore: that reader must still accept the message,
  recover the same payload, and validate it with the rules it already knows. A guarantee from the writer, such as
  always carrying a valid `l`, fits; a new compression or checksum id, or a reader that refuses what earlier ones
  accepted (such as a missing `l`), doesn't fit a minor, and its release notes say what the upgrade needs. While
  sqs-codec is 0.x, the project handles such changes that way rather than by moving to a new major. Keys a minor adds
  follow the same syntax, unique ignoring case, or earlier readers refuse the metadata.
- A reader may require a minimum minor of its major, once it relies on what that minor guarantees; a bare `1` counts as
  `1.0`. Its version check then accepts every minor from that minimum through `2147483647`, and the message is still
  validated as usual. Writers that originate a dotted version keep its minor within that range, so a reader that
  compares minors as numbers may refuse a larger one; this version accepts any minor spelled as above, and a relay
  forwards whatever it received. Requiring a minimum refuses messages earlier readers accepted (a
  minimum of `1.1` would refuse the `v=1` that 0.6.0 sends), so its release notes say so, as above, and a consumer
  gets it only once every message it can receive, through a relay or redrive included, meets it. This version requires
  none, and neither does the change planned for 0.7.0 (see [Before 0.7.0](#before-070)), which requires a valid `l`
  instead.
- A minor only reaches readers that accept it, so sending `1.<minor>` needs every consumer of the queue on 0.6.0 or
  later, whoever sends it: a writer, or a relay or redrive that forwards an already encoded message, which the
  interceptor sends unchanged. 0.5.x refuses every dotted version.
- `CodecConfiguration.version()` is the major: metadata read as `1.y` reports `1`. To find messages with a minor, look
  at the raw `x-codec-meta`. `CodecMetadataAttributeHandler.applyTo` writes only the keys this version knows and
  writes the version as `v=1`, so copying metadata through it drops a minor and any key this version doesn't know
  (a usable `l` and the checksum text as parsed are kept); to forward a message, keep its encoded body and its attributes as they are, as the
  interceptor does.

## Upgrading from 0.5.x

0.6.0's fresh sends write the same metadata version as 0.5.x (`v=1`), so no producer has to wait for its consumers
(a relay forwarding an already encoded message sends its version unchanged; see
[Metadata versions](#metadata-versions)): once the traffic they read passes the checks below, clients can be
upgraded, or rolled back to 0.5.x, in any order, including
when writers and consumers form a cycle (services that send to each other, or a client that sends to a queue it also
consumes). Fresh compressed sends always write `l`, a few bytes more than 0.5.x clients that turned it off sent: a
message that filled SQS's message-size limit exactly can now go over it, and the send fails.
Calls from code built against 0.5.x to the methods it used still link without recompiling: the 0.5.x methods that
changed are kept, deprecated (`withIncludeRawPayloadLength(boolean)` and the three-argument
`CodecMetadataAttributeHandler.forOutbound` ignore their flag and write `l` whenever they compress), and the built-in
compressors still declare the one-argument `decompress(byte[])`, now bounded like a receive with the 16 MiB default.
A class implementing `Compressor` itself has to implement the two-argument `decompress` once recompiled.

This version reads metadata more strictly than 0.5.x in the ways below (it also accepts minor versions, which 0.5.x
refused), and one message it refuses fails the whole receive. Before
replacing consumers, check retained messages, and traffic from 0.5.x clients that keep sending during the upgrade:
- a compressed or encoded payload must decode to valid UTF-8 (raising `maxDecodedPayloadBytes` doesn't help);
- a compressed or encoded payload must decode to at most `maxDecodedPayloadBytes`;
- a numeric, non-negative `l` must equal the decoded length in bytes (0.5.x ignored mismatches; raising the limit
  doesn't bypass this);
- the version, once trimmed, must be `1` or `1.<minor>`, spelled exactly (0.5.x also accepted other spellings of `1`,
  such as `01`, `+1` or non-ASCII digits; it always wrote `1`, so only metadata built elsewhere is affected).

Pause producers and drain incompatible messages with 0.5.x consumers, or correct and replay them, before cutover.

Rolling back a consumer to 0.5.x also needs that no message with a minor version (`v=1.<minor>`) can still reach it,
counting retained, delayed and in-flight messages, dead-letter queues and replay: 0.5.x refuses them. 0.6.x's fresh
sends write only `1`, so such messages come only from a later writer or from metadata built elsewhere, possibly
forwarded by a relay. Look for them in the raw `x-codec-meta`, since `CodecConfiguration.version()` reports `1`.

### Before 0.7.0

0.7.0 will refuse a compressed message without a valid `l`, which 0.5.x and 0.6.x read as optional, and will stop
decoding in one step the zstd frames whose window is larger than its limit needs. Before upgrading any consumer to
0.7.0, make sure that:
- no client sends compressed messages without `l`: 0.5.x clients that call `withIncludeRawPayloadLength(false)` stop
  doing so or are upgraded to 0.6.0, and code that builds codec metadata itself writes an `l` equal to the raw
  payload's UTF-8 byte length;
- relays and other senders that forward already encoded messages don't pass on any without a valid `l`: the
  interceptor validates such a message and sends it unchanged, so 0.6.x doesn't add a missing `l`;
- retained compressed messages without a valid `l` are drained, counting delayed and in-flight messages and any
  dead-letter queue or replay that can bring them back;
- no zstd frame whose window is larger than the consumer's limit needs can still reach it, valid `l` or not: such frames
  come from 0.5.x writers (128 MiB at the `MAXIMUM` level, whatever the message's size) and from encoders outside this
  library, so those writers stop or upgrade, and retained, forwarded and replayed ones are drained as above.

## Error handling

All codec failures extend `CodecException`, with subtypes for targeted
responses.

On receive, a failure in the interceptor reaches the caller with the codec exception in its cause chain. With
`SqsClient`, the SDK throws an `SdkClientException` ("Unable to unmarshall response"); with `SqsAsyncClient`, the
returned future completes exceptionally, so `join()` throws a `CompletionException` and `get()` an
`ExecutionException`. Either way, look for the `CodecException` among the causes:
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
        // refused: too much output, or a declared length over the limit
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
The supply-chain workflow (`supply-chain.yml`) runs the pinned [leanish/leanish-development](https://github.com/leanish/leanish-development) supply-chain check on pull requests, pushes to `main`, a daily schedule and manual runs; that repository documents what it checks.
Legacy runtime checks (`testing-legacy-jdk.yml`) run tests on JDK 17 and 21, and can be run manually or are required by publishing.
The build, mutation testing, dependency graph and legacy-JDK workflows use GitHub-hosted `ubuntu-26.04` runners; the publishing jobs' runners are set by java-conventions' workflow.
Publishing (`publishing-github.yml`, run by hand: releases from their `vX.Y.Z` tag, SNAPSHOTs from `main`) runs after `Build` and the legacy-JDK tests and delegates to [java-conventions' reusable publishing workflow](https://github.com/leanish/java-conventions#plugin-project-ci-maintainers), which stages the publication in `build/publication-staging` (the build's `Staging` repository) and uploads it from a separate job. Locally, use `./gradlew publishToMavenLocal`: a plain `./gradlew publish` also targets GitHub Packages, which java-conventions configures for the owner it infers.

Run full checks (tests, checkstyle, spotless, jacoco):
```bash
./gradlew check
```

Run mutation testing (PIT, excludes `integration`-tagged tests and fails below the conventions' 95 % mutation threshold; report in `build/reports/pitest`):
```bash
./gradlew pitest
```

CI runs it on pull requests and `main` in its own "Mutation testing" workflow. Its `PIT` job is a required check, like `Build`: a pull request merges only once both pass.

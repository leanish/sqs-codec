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
        // Required on a client that receives bodies the interceptor decodes: the SDK would check SQS's MD5 of the wire
        // body against the decoded one. This also turns off the SDK's other SQS MD5 checks (message attributes, sends
        // and batch sends). The interceptor checks its own checksum value (s) over the payload, using the algorithm
        // in h, when one is set.
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
- `ASCII85` is experimental. The encoder writes unframed ASCII85 without `<~ ~>` framing, whitespace or `z`/`y` shorthands. The decoder rejects framing, whitespace and shorthands, and accepts equivalent spellings of a final partial group (see [docs/ascii85.md](docs/ascii85.md)).
- `ASCII85` is intentionally transport-focused between `sqs-codec` endpoints rather than interoperability-focused with third-party ASCII85 tools.
- `ASCII85` encodes four bytes as five characters, where Base64 encodes three as four, so it's somewhat smaller for full groups (some short inputs tie); treat it as a niche transport option rather than a new default.
- `MD5` and `SHA256` detect accidental corruption. They're unkeyed, so neither authenticates the producer: whoever can send to the queue can attach a matching checksum to any body, or send `h=none`. Control who can send with the queue's access policy.
- `skipCompressionWhenLarger`: `true`
- `maxDecodedPayloadBytes`: `16 MiB` (`SqsCodecInterceptor.DEFAULT_MAX_DECODED_PAYLOAD_BYTES`)
- The interceptor rejects a decoded payload over `maxDecodedPayloadBytes` with `DecompressedPayloadTooLargeException`, wherever it decodes one: on receive, and when it validates an already encoded message before sending. The streaming decoders check the output after each read (of up to 8 KiB) and refuse a read that would pass the limit, so a small compressed message can't expand without bound; an encoding-only payload is bounded by its encoded size, and its decoded bytes are checked once decoded. Set it with `withMaxDecodedPayloadBytes(...)`; it must be positive. It bounds only what the interceptor decodes, that is compressed or encoded (`e` not `none`) payloads: a checksum-only (`c=none,e=none`) or metadata-free body is returned as received.
- On a fresh send, a payload the interceptor is configured to compress or encode is refused with `DecompressedPayloadTooLargeException` when its UTF-8 bytes exceed `maxDecodedPayloadBytes`, before it's compressed or encoded, since receivers with that limit would refuse it once compressed or encoded. The configured algorithms decide, not the final metadata: such a payload is refused even when `skipCompressionWhenLarger` would have sent it as is. The interceptor fails the request before SQS sees it, so for a batch send the whole batch fails, not only that entry (unlike the per-entry failures SQS itself can report for a batch it received). A checksum-only send isn't checked. A consumer whose limit is smaller than the producer's still refuses what's over its own; keep consumers' limits at least as large as producers'.
- A compressed payload's raw length (`l`) is the sender's claim, so it never bounds decompression: `maxDecodedPayloadBytes` does. When `l` is usable (a non-negative integer that fits in a Java `int`), the payload must decode to exactly that many bytes, or it fails with `CompressionException`, on receive and when an already encoded message is validated before sending. 0.6.x still decodes a compressed payload whose `l` is missing or not a usable number, as 0.5.x writers could send; 0.7.0 will refuse it (see [Before 0.7.0](#before-070)). `l` is ignored when `c=none`.
- A message that fails these checks fails the whole receive, as any decoding error does: the codec doesn't delete it,
  so it can become visible again once its visibility timeout ends, subject to the queue's retention period and, if the
  queue has one, its redrive policy (which moves it to the dead-letter queue based on SQS's receive count).
- The limit bounds each payload's decoded bytes, not memory: a receive returns up to ten messages, so its decoded payloads together can reach ten times the limit, before counting their `String` bodies, the encoded bodies, temporary buffers, native memory and concurrent receives.
- A decoded payload (compressed or encoded) must be valid UTF-8, since it becomes the message's `String` body; one that isn't fails with `InvalidPayloadException`, checked only after the `l` check and any checksum have passed (a checksum mismatch is reported first, as `ChecksumValidationException`), on receive and when an already encoded message is validated before sending. A checksum-only or metadata-free body isn't decoded, so it isn't checked.
- Zstd compresses in one pass, so each frame records its content size and declares a window no larger than its payload. Compressing holds an output buffer about the size of the raw payload besides the payload itself, even when the result is small, and one-pass zstd compression has an input ceiling just below 2 GiB.
- On receive, zstd's buffered streaming decoder accepts a frame whose declared window is at most what `maxDecodedPayloadBytes` needs (rounded up to a power of two, at least 1 KiB, so a 3 MiB limit admits a 4 MiB window; a limit above 128 MiB admits a larger window than 0.5.x's default did), where the loaded native build supports the required window log. That caps the window, not the memory: the frame decides what is actually allocated, and native buffers add block overhead. Until 0.7.0, when streaming refuses a frame's window as larger, as with streamed frames from 0.5.x's `MAXIMUM` level, which can declare a 128 MiB window even for a few bytes of input, the payload is decoded again in one step into an output array that starts at 1 MiB (or the limit, if smaller) and grows fourfold, decoding again, up to the limit or a conservative Java array ceiling (`Integer.MAX_VALUE - 64`), whichever is lower: zstd uses that output as its history instead of a separate window buffer, besides its decoder context, and an output shorter than the array is copied into a trimmed one. The decoded output stays bounded by the limit either way.
- Snappy parses and checks the declared length itself, refusing a header it can't read (`CompressionException`); the codec then refuses a declared length over `maxDecodedPayloadBytes` before allocating the decoded output array.
- `DecompressedPayloadTooLargeException` means the payload was refused against `maxDecodedPayloadBytes`: decoding (decompressing, or decoding an encoding-only payload) passed it, zstd's one-step retry couldn't fit the output in its largest permitted array (the limit, or the array ceiling for a limit within 64 bytes of `Integer.MAX_VALUE`), or snappy's declared length is over it; on a fresh send, the payload to compress or encode is over it. On decode, it doesn't establish the payload's actual decoded size.
- When `withSkipCompressionWhenLarger(true)` (default) and compression is enabled, for a fresh send within the limit, if the compressed and encoded body would be the same size as the original body or larger, the interceptor sends the original body and falls back to `c=none,e=none`.
- When outbound processing resolves to `c=none`, `e=none` and `h=none`, the interceptor does not add `x-codec-meta`.
- Compression level is local send-side configuration only; it does not change codec metadata.
- When unset, each compression algorithm uses its own built-in default.
- `GZIP` and `ZSTD` honor configured compression levels. `SNAPPY` and `NONE` reject them.

Disable `skipCompressionWhenLarger` and always use configured compression:
```java
SqsCodecInterceptor interceptor = SqsCodecInterceptor.defaultInterceptor()
        .withCompressionAlgorithm(CompressionAlgorithm.ZSTD)
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
- `h`: checksum algorithm (`md5`, `sha256`, `none`)
- `s`: checksum value (present only when `h` is not `none`; written as unpadded URL-safe Base64)
- `l`: raw payload byte length (before compression); written only when `c` is not `none`

Notes:
- Order does not matter; metadata keys and algorithm ids are case-insensitive. Checksum value `s` is written as an unpadded URL-safe Base64 string and is independent of `e`.
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
- `s` must be absent when `h=none`; a present `s`, even blank, is refused, as is a blank `s` when `h` is set.
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

`v`, once trimmed, is `<major>` or `<major>.<minor>`, where the minor is `0` or an ASCII number without a leading zero (`1.0`, `1.1`,
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
  compares minors as numbers may refuse a larger one; this version accepts any minor spelled as above, and the
  interceptor, once it has validated an already encoded message, sends its version unchanged. Requiring a minimum refuses messages earlier readers accepted (a
  minimum of `1.1` would refuse the `v=1` that 0.6.0 sends), so its release notes say so, as above, and a consumer
  gets it only once every message it can receive, through a relay or redrive included, meets it. This version requires
  none, and neither does the change planned for 0.7.0 (see [Before 0.7.0](#before-070)), which requires a valid `l`
  instead.
- Before `1.<minor>` is sent, every consumer that may receive it must accept that minor and what the message uses (an
  older consumer can still receive the message, and then refuses it) (0.6.x accepts every minor; 0.5.x refuses every dotted version), whoever sends it: a
  writer, or a relay or redrive that forwards an already encoded message, which the interceptor validates and then
  sends unchanged.
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
consumes), except for the FIFO deduplication caveat on zstd producers below. Fresh compressed sends always write `l`: `;l=` and its digits count toward SQS's message-size limit, which
counts the body and the message attributes (not message system attributes; batch sends have a total limit too), so a message from a 0.5.x client
that turned `l` off and filled the limit can now go over it: SQS rejects the message, or an oversized batch (check
the per-entry results of batch sends too); the one-pass zstd writer can also change the compressed body's size.
0.6.0 keeps the public method signatures of 0.5.x's API, and selected calls compiled against 0.5.1 were exercised on it: the 0.5.x methods that changed are kept, deprecated
(`withIncludeRawPayloadLength(boolean)` has no effect, since fresh compressed sends always write `l`; the
three-argument `CodecMetadataAttributeHandler.forOutbound` ignores its flag, writes `l` whenever the configuration
compresses and refuses a version other than `1`, as the two-argument form does), and the one-argument `decompress(byte[])` is still declared by the built-in compressors, and by `Compressor` as
a default method, now bounded like a receive with the 16 MiB default.
A producer configured to compress or encode now refuses, before sending, a payload whose UTF-8 bytes exceed its
`maxDecodedPayloadBytes` (16 MiB by default), which 0.5.x attempted to send without a decoded-size check (SQS still
applies its own size limits); raise the limit on producers and consumers together if such payloads are expected.
A concrete class implementing `Compressor` itself must implement (or inherit) the two-argument
`decompress(byte[], int)` to compile against 0.6.x, since it's abstract; one written for 0.5.x, with only `compress` and
`decompress(byte[])`, doesn't compile until it does. A class compiled against 0.5.x without that overload, run
unchanged on 0.6.x, throws `AbstractMethodError` if it's called on it (the interceptor never calls it: it uses only the
built-in compressors).

On a FIFO queue with content-based deduplication, SQS derives the deduplication ID from the message body, and 0.6.0's
zstd writer can produce a different body than 0.5.x's for the same payload, at any compression level (it did at the
default and `MAXIMUM` levels; a payload both send uncompressed keeps the same body). So an event that an application
resubmits (after a lost acknowledgement, say) through the other version's writer, within SQS's five-minute
deduplication interval, can be delivered twice; rolling a producer back has the same effect. Before mixing zstd
writer versions, send the same explicit `MessageDeduplicationId` on the original send and every resubmission,
starting before the cutover (the interceptor keeps it); or, where producers rely on body hashes, let outstanding
retries finish on the writer that made the first attempt before switching it.

This version reads metadata more strictly than 0.5.x in the ways below (it also accepts minor versions, which 0.5.x
refused), and one message it refuses fails the whole receive. A relay that forwards already encoded messages validates
them with the same checks before sending, so an upgraded relay's send fails where 0.5.x forwarded the message. Before
replacing consumers or relays, check retained messages, and traffic from 0.5.x clients that keep sending during the upgrade:
- a compressed or encoded payload must decode to valid UTF-8 (raising `maxDecodedPayloadBytes` doesn't help);
- a compressed or encoded payload must decode to at most `maxDecodedPayloadBytes`;
- on a compressed message, an `l` that parses as a non-negative Java `int` must equal the decoded length in bytes
  (0.5.x ignored mismatches; raising the limit doesn't bypass this);
- the version, once trimmed, must be `1` or `1.<minor>`, spelled exactly (0.5.x also accepted other spellings of `1`,
  such as `01`, `+1` or non-ASCII digits; its fresh sends wrote `1`, so only metadata built elsewhere, possibly
  forwarded by a 0.5.x relay, is affected).

Pause producers and drain incompatible messages with 0.5.x consumers, or correct and replay them, before cutover.

Rolling back a consumer to 0.5.x needs every message that can still reach it to be one 0.5.x reads, counting retained,
delayed and in-flight messages, dead-letter queues and replay, and what was sent during the upgrade. In particular:
- no message with a minor version (`v=1.<minor>`), which 0.5.x refuses. 0.6.x's fresh sends write only `1`, so such
  messages come only from a later writer or from metadata built elsewhere, possibly forwarded by a relay. Look for
  them in the raw `x-codec-meta`, since `CodecConfiguration.version()` reports `1`;
- no zstd frame, from an encoder outside this library, that the target 0.5.x reader can't decode: check retained and
  incoming external frames against it, in particular any that 0.6.x reads only through its one-step retry (0.5.x's
  streaming decoder can refuse their window; one within its 128 MiB default can pass that check, but verify decoding
  with that reader).

Rolling a producer back to 0.5.x on a FIFO queue with content-based deduplication has the deduplication caveat
described above, in the other direction.

### Before 0.7.0

0.7.0 will refuse a compressed message without a valid `l`, which 0.5.x and 0.6.x read as optional, and will drop the
one-step retry for zstd frames whose window streaming refuses, so such frames will be refused. Before upgrading any consumer to
0.7.0, make sure that:
- no client sends compressed messages without `l`: 0.5.x clients that call `withIncludeRawPayloadLength(false)` stop
  doing so or are upgraded to 0.6.0, and code that builds codec metadata itself writes an `l` equal to the raw
  payload's UTF-8 byte length;
- relays and other senders that forward already encoded messages don't pass on any without a valid `l`: the
  interceptor validates such a message and sends it unchanged, so 0.6.x doesn't add a missing `l`;
- retained compressed messages without a valid `l` are drained, counting delayed and in-flight messages and any
  dead-letter queue or replay that can bring them back;
- no zstd frame whose decoded payload fits the consumer's limit but whose window is larger than that limit needs can
  still reach it, valid `l` or not (a frame whose payload is over the limit is refused either way; keep producers' and
  consumers' limits aligned, since a 0.6.x producer with a higher limit can already send payloads a lower-limit
  consumer refuses): such frames come from 0.5.x writers (which can declare 128 MiB at the `MAXIMUM` level, even for a few bytes of input) and from encoders outside this
  library, so those writers stop or upgrade, and retained, forwarded and replayed ones are drained as above. This is
  a conservative check: zstd can still decode some such frames in a single pass, but which ones depends on the frame.

## Error handling

Every exception the codec throws for a message it refuses extends `CodecException`, with subtypes for targeted
responses. Invalid configuration fails when it's set (a non-positive limit with `IllegalArgumentException`, a null
algorithm with `NullPointerException`; a null compression level is how `withoutCompressionLevel()` clears it), and JVM errors such as `OutOfMemoryError` aren't
translated. A send without a body (a null `messageBody`) isn't a refused message: it can fail with a
`NullPointerException` from the send-side processing instead of a `CodecException`.

On receive, a codec rejection reaches the caller with the codec exception in its cause chain. With AWS SDK for Java v2
`2.57.13`, the sync response handler wraps it: `SqsClient` throws an `SdkClientException` ("Unable to unmarshall
response"). With `SqsAsyncClient`, per that version's source, the returned future completes exceptionally, so `join()` throws a
`CompletionException` and `get()` an `ExecutionException`; inspect its cause chain. Either way, look for the
`CodecException` among the causes:
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
        // decompression failed, or the decoded length doesn't match a usable l
    } else if (cause instanceof ChecksumValidationException checksumFailure) {
        // missing algorithm/attribute or checksum mismatch; inspect checksumFailure.detail()
    } else if (cause instanceof UnsupportedCodecMetadataException) {
        // malformed/duplicate/unsupported codec metadata
    } else if (cause instanceof UnsupportedAlgorithmException) {
        // unsupported compression, encoding or checksum ID
    } else if (cause instanceof CodecException) {
        // any other codec issue
    } else {
        throw e; // not a codec failure
    }
}
```

On send, a codec rejection (such as a fresh payload over `maxDecodedPayloadBytes`, or an invalid already encoded
message) fails the request before it reaches SQS. With `2.57.13`, `SqsClient` throws the codec exception itself,
not wrapped; with `SqsAsyncClient`, the future completes exceptionally with it as the cause.

## Development

Build target is Java 17 bytecode.
Toolchain comes from `java-conventions` (default compile/runtime JDK 25).
CI (`ci.yml`) runs full `build` on JDK 25.
The supply-chain workflow (`supply-chain.yml`) runs the pinned [leanish/leanish-development](https://github.com/leanish/leanish-development) supply-chain check on pull requests, pushes to `main`, a daily schedule and manual runs; that repository documents what it checks.
Legacy runtime checks (`testing-legacy-jdk.yml`) run tests on JDK 17 and 21, and can be run manually or are required by publishing.
The build, mutation testing, dependency graph and legacy-JDK workflows use GitHub-hosted `ubuntu-26.04` runners; the publishing jobs' runners are set by java-conventions' workflow.
Publishing (`publishing-github.yml`, run by hand: final releases from their matching `vX.Y.Z` tag, which the workflow enforces, and, by project convention, SNAPSHOTs from `main`) runs after `Build` and the legacy-JDK tests and delegates to [java-conventions' reusable publishing workflow](https://github.com/leanish/java-conventions#plugin-project-ci-maintainers), which stages the publication in `build/publication-staging` (the build's `Staging` repository) and uploads it from a separate job. Locally, use `./gradlew publishToMavenLocal`: a plain `./gradlew publish` also targets GitHub Packages, which java-conventions configures for the owner it infers.

Run full checks (tests, checkstyle, spotless, jacoco):
```bash
./gradlew check
```

Run mutation testing (PIT, excludes `integration`-tagged tests and fails below the conventions' 95 % mutation threshold; report in `build/reports/pitest`):
```bash
./gradlew pitest
```

CI runs it on pull requests and `main` in its own "Mutation testing" workflow. Its `PIT` job is a required check, like `Build`: a pull request merges only once both pass.

# sqs-codec

AWS SDK v2 execution interceptor for SQS that compresses message bodies,
stores codec metadata in a single message attribute, and reverses it on receive.

When compression is enabled, the compressed binary bytes are encoded as unpadded URL-safe Base64.

## Features
- Compression: `ZSTD`, `SNAPPY`, `GZIP`, `NONE`
- Checksums: `MD5`, `SHA256`, `NONE`
- Config-driven compression/checksum on send
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
                .withChecksumAlgorithm(ChecksumAlgorithm.SHA256)))
        .checksumValidationEnabled(false) // handled by SqsCodecInterceptor
        .build();
```

Defaults:
- Compression: `NONE`
- Checksum: `MD5`
- `skipCompressionWhenLarger`: `true`
- `includeRawPayloadLength`: `true`
- When `withSkipCompressionWhenLarger(true)` (default) and compression is enabled, if compressed payload would be larger than the original body, the interceptor sends the original body and writes `c=none`.
- When outbound processing resolves to `c=none` and `h=none`, the interceptor does not add `x-codec-meta`.

Disable `skipCompressionWhenLarger` and always use configured compression:
```java
SqsCodecInterceptor interceptor = SqsCodecInterceptor.defaultInterceptor()
        .withSkipCompressionWhenLarger(false);
```

Disable raw payload length metadata (`l`) on send:
```java
SqsCodecInterceptor interceptor = SqsCodecInterceptor.defaultInterceptor()
        .withIncludeRawPayloadLength(false);
```

## Attributes

Codec metadata is stored in a single attribute:
- `x-codec-meta` (String), for example: `v=1;c=zstd;h=md5;s=t2tngCwK9b7C9eqVQunqfg;l=12`

Keys:
- `v`: codec version
- `c`: compression (`zstd`, `gzip`, `snappy`, `none`)
- `h`: checksum (`md5`, `sha256`, `none`)
- `s`: checksum value (present only when `h` is not `none`)
- `l`: raw payload byte length (before compression); written only when `c` is not `none`

Notes:
- Order does not matter; metadata keys and algorithm ids are case-insensitive. Checksum value `s` is an opaque Base64 string.
- `v` is required and must be the current supported version (`1`).
- Missing `c` or `h` defaults to `none` on read.
- Metadata is invalid when both `c` and `h` resolve to `none`.
- On send, the default interceptor configuration uses checksum `md5`.
- `s` is required when `h` is not `none`.
- `s` must be absent when `h=none`.
- `s` is emitted as unpadded URL-safe Base64 on send.
- If checksum values are produced or compared outside this library, use the canonical unpadded form; padded values are not normalized on read.
- `l` is ignored if missing/invalid on read.
- On send, `l` is emitted only when compression is actually used (`c!=none` in final metadata) and `withIncludeRawPayloadLength(true)` is enabled.
- Unknown keys are ignored for forward compatibility.
- When `x-codec-meta` is already present on send, the interceptor validates that body and checksum match the declared metadata before skipping re-encoding.
- If send-side processing (including `skipCompressionWhenLarger`) ends with `c=none` and `h=none`, no metadata attribute is emitted.

Outbound metadata emission matrix (final per-message decision):
- `c=none,h=none`: no `x-codec-meta`
- `c=none,h!=none`: `v;c;h;s` (no `l`)
- `c!=none,h=none`: `v;c;h;l` (or `v;c;h` when raw length metadata is disabled)
- `c!=none,h!=none`: `v;c;h;s;l` (or `v;c;h;s` when raw length metadata is disabled)

SQS attribute limit:
- SQS supports at most 10 message attributes per message.
- `sqs-codec` adds at most one attribute (`x-codec-meta`) and skips it for no-op messages (`c=none,h=none`).
- The interceptor fails fast on send when the final attribute count would exceed the SQS limit.

## Error handling

All codec failures extend `CodecException`. You can catch the base type
to handle any codec error, or specific exceptions when you want targeted
responses.

Catch specific decode failures surfaced by the interceptor:
```java
try {
    ReceiveMessageResponse response = client.receiveMessage(request);
    // use decoded payloads
} catch (InvalidPayloadException e) {
    // bad payload data, consider DLQ or logging
} catch (CompressionException e) {
    // payload decompression failed
} catch (CodecException e) {
    // fallback for any other codec issue
}
```

Handle attribute/config errors on receive:
```java
try {
    ReceiveMessageResponse response = client.receiveMessage(request);
    // interceptor validates checksum/attributes during receive
} catch (ChecksumValidationException e) {
    // missing algorithm/attribute or checksum mismatch; inspect e.detail()
} catch (UnsupportedCodecMetadataException e) {
    // malformed/duplicate/unsupported codec metadata
} catch (UnsupportedAlgorithmException e) {
    // unsupported compression/checksum values
} catch (CodecException e) {
    // catch-all for other codec errors
}
```

## Development

Build target is Java 17 bytecode.
Toolchain comes from `java-conventions` (default compile/runtime JDK 25).
CI (`ci.yml`) runs full `build` on JDK 25.
Legacy runtime checks (`legacy-jdk-check.yml`) run tests on JDK 17 and 21, and can be run manually or are required by publishing.

Run full checks (tests, checkstyle, spotless, jacoco):
```bash
./gradlew check
```

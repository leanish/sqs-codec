# sqs-codec

AWS SDK v2 execution interceptor for SQS that compresses message bodies,
stores codec metadata in a single message attribute, and reverses it on receive.

When compression is enabled, the compressed binary bytes are URL-safe Base64 encoded.

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
- When `withSkipCompressionWhenLarger(true)` (default) and compression is enabled, if compressed payload would be larger than the original body, the interceptor sends the original body and writes `c=none`.

Disable `skipCompressionWhenLarger` and always use configured compression:
```java
SqsCodecInterceptor interceptor = SqsCodecInterceptor.defaultInterceptor()
        .withSkipCompressionWhenLarger(false);
```

## Attributes

Codec metadata is stored in a single attribute:
- `x-codec-meta` (String), for example: `v=1;c=zstd;h=md5;s=t2tngCwK9b7C9eqVQunqfg==;l=12`

Keys:
- `v`: codec version
- `c`: compression (`zstd`, `gzip`, `snappy`, `none`)
- `h`: checksum (`md5`, `sha256`, `none`)
- `s`: checksum value (present only when `h` is not `none`)
- `l`: raw payload byte length (before compression; debug metadata)

Notes:
- Order does not matter; keys and values are case-insensitive.
- When parsing `x-codec-meta` on receive, missing `v` defaults to `1` and missing `c` defaults to `none`.
- On receive, when `h` is missing and `s` is present with a non-blank value, `h` is implicitly treated as `md5`.
- On receive, when both `h` and `s` are missing, `h` defaults to `none` and checksum validation is skipped.
- On send, the default interceptor configuration uses checksum `md5`.
- `s` is required when `h` is not `none`.
- `h=none` with `s` present is rejected.
- `l` is always written by the interceptor, but ignored if missing/invalid on read.
- Unknown keys are ignored for forward compatibility.
- When `x-codec-meta` is already present on send, the interceptor validates that body and checksum match the declared metadata before skipping re-encoding.

SQS attribute limit:
- SQS supports at most 10 message attributes per message.
- `sqs-codec` adds exactly one attribute: `x-codec-meta`.
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

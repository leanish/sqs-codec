# sqs-codec

AWS SDK v2 execution interceptor for SQS that compresses/encodes message bodies
and records codec metadata in SQS message attributes, then reverses it on receive.

## Features
- Compression: `ZSTD`, `SNAPPY`, `GZIP`, `NONE`
- Encoding: `BASE64`, `BASE64_STD`, `NONE`
- Checksums: `MD5`, `SHA256`, `NONE`
- Attribute-driven decoding on receive (attributes override interceptor config)

## Usage

Gradle:
```kotlin
dependencies {
    implementation("io.github.leanish:sqs-codec:<version>")
}
```

Java:

Send with defaults (no compression/encoding, MD5 checksum); decode/validate based on message attributes:
```java
SqsAsyncClient client = SqsAsyncClient.builder()
        .overrideConfiguration(config -> config.addExecutionInterceptor(SqsCodecInterceptor.defaultInterceptor()))
        .checksumValidationEnabled(false) // handled by SqsCodecInterceptor
        .build();
```

Send with explicit Zstd compression + default Base64 encoding (when compression is present) + default MD5 checksum; decode/validate based on message attributes:
```java
SqsAsyncClient client = SqsAsyncClient.builder()
        .overrideConfiguration(config -> config.addExecutionInterceptor(SqsCodecInterceptor.defaultInterceptor()
                .withCompressionAlgorithm(CompressionAlgorithm.ZSTD)))
        .checksumValidationEnabled(false) // handled by SqsCodecInterceptor
        .build();
```

Send with explicit compression, encoding, and checksum; decode/validate based on message attributes:
```java
SqsClient client = SqsClient.builder()
        .overrideConfiguration(config -> config.addExecutionInterceptor(SqsCodecInterceptor.defaultInterceptor()
                .withCompressionAlgorithm(CompressionAlgorithm.GZIP)
                .withEncodingAlgorithm(EncodingAlgorithm.BASE64_STD)
                .withChecksumAlgorithm(ChecksumAlgorithm.SHA256)))
        .checksumValidationEnabled(false) // handled by SqsCodecInterceptor
        .build();
```

Defaults:
- Compression: `NONE`
- Encoding: `NONE`
- Checksum: `MD5`
- When `withPreferSmallerPayloadEnabled(true)` (default), if a compressed+encoded payload would be larger than the original body, the interceptor sends the original body and writes `c=none;e=none`.

Disable payload-size optimization and always use the configured compression/encoding:
```java
SqsCodecInterceptor interceptor = SqsCodecInterceptor.defaultInterceptor()
        .withPreferSmallerPayloadEnabled(false);
```

## Attributes

Codec metadata is stored in a single attribute:
- `x-codec-meta` (String), for example: `v=1;c=zstd;e=base64;h=md5;s=t2tngCwK9b7C9eqVQunqfg==;l=12`

Keys:
- `v`: codec version
- `c`: compression (`zstd`, `gzip`, `snappy`, `none`)
- `e`: encoding (`base64`, `base64-std`, `none`)
- `h`: checksum (`md5`, `sha256`, `none`)
- `s`: checksum value (present only when `h` is not `none`)
- `l`: raw payload byte length (before compression/encoding)

Notes:
- Order does not matter; keys and values are case-insensitive.
- Missing `v` defaults to `1`, and missing `c/e/h` keys default to `none`.
- `s` is required when `h` is not `none`.
- `l` is always written by the interceptor, but ignored if missing/invalid on read (debug-only metadata).
- The interceptor defaults to `h=md5` when encoding.
- Unknown keys are ignored for forward compatibility.
- When `x-codec-meta` is already present on send, the interceptor validates that body and checksum match the declared metadata before skipping re-encoding.
- If compression is not `none` and encoding is `none`, the effective encoding is `base64` (and is written in `x-codec-meta`).

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
    // unsupported compression/encoding/checksum values
} catch (CodecException e) {
    // catch-all for other codec errors
}
```

## Development

Run full checks (tests, checkstyle, spotless, jacoco):
```bash
./gradlew check
```

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
- Raw length attribute (`x-codec-raw-length`): disabled
- If encoding is `NONE` and compression is not `NONE`, the effective encoding is `BASE64`.

Enable raw length metadata when you want extra observability (it consumes one SQS message-attribute slot):
```java
SqsCodecInterceptor interceptor = SqsCodecInterceptor.defaultInterceptor()
        .withRawLengthAttributeEnabled(true);
```

## Attributes

Codec configuration is stored in a single attribute:
- `x-codec-conf` (String), for example: `v=1;c=zstd;e=base64;h=md5`

Keys:
- `v`: codec version
- `c`: compression (`zstd`, `gzip`, `snappy`, `none`)
- `e`: encoding (`base64`, `base64-std`, `none`)
- `h`: checksum (`md5`, `sha256`, `none`)

Notes:
- Order does not matter; keys and values are case-insensitive.
- Missing keys default to `none` (including `h`), and `v` defaults to `1`.
- The interceptor defaults to `h=md5` when encoding.
- Unknown keys are ignored for forward compatibility.
- When `x-codec-conf` is already present on send, the interceptor validates that body and checksum match the declared configuration before skipping re-encoding.
- If compression is not `none` and encoding is `none`, the effective encoding is `base64` (and is written in `x-codec-conf`).

Other attributes:
- `x-codec-checksum` (String)
- `x-codec-raw-length` (Number, optional debug/observability metadata; disabled by default)

SQS attribute limit:
- SQS supports at most 10 message attributes per message.
- `sqs-codec` adds `x-codec-conf`, optionally `x-codec-checksum`, and optionally `x-codec-raw-length`.
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
} catch (UnsupportedCodecConfigurationException e) {
    // malformed/duplicate/unsupported codec configuration
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

# sqs-payload-codec

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
    implementation("io.github.leanish:sqs-payload-codec:<version>")
}
```

Java:
```java
SqsClient client = SqsClient.builder()
        .overrideConfiguration(config -> config.addExecutionInterceptor(
                new SqsPayloadCodecInterceptor()
                        .withCompressionAlgorithm(CompressionAlgorithm.ZSTD)
                        .withEncodingAlgorithm(EncodingAlgorithm.BASE64_STD)
                        .withChecksumAlgorithm(ChecksumAlgorithm.SHA256)))
        .build();
```

Defaults:
- Compression: `NONE`
- Encoding: `NONE`
- Checksum: `MD5`
- If encoding is `NONE` and compression is not `NONE`, the effective encoding is `BASE64`.

## Attributes

Metadata is stored in SQS message attributes:
- `x-codec-compression-alg`
- `x-codec-encoding-alg`
- `x-codec-checksum-alg`
- `x-codec-checksum`
- `x-codec-version` (Number, current value `1`)
- `x-codec-raw-length` (Number)

## Development

Run full checks (tests, checkstyle, spotless, jacoco):
```bash
./gradlew check
```

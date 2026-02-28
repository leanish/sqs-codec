# AGENTS

This repo is a Java library that encodes/decodes SQS payloads via an AWS SDK v2
execution interceptor.

## Commands
- `./gradlew check` (tests, checkstyle, spotless, jacoco)

Integration tests use LocalStack and require Docker.
Always run `./gradlew check` after each code change.

## Conventions
- JDK 21, Gradle.
- Tests use AssertJ assertions.
- Keep responsibilities well-separated; avoid mixing concerns in the same class.
- Keep attribute keys in `CodecAttributes`.
- Use `MessageAttributeUtils` for reading/writing `MessageAttributeValue`s.
- Codec configuration is written to `x-codec-conf` using `v/c/e/h` keys; keep the format stable and update docs/tests when changing it.
- `SqsCodecInterceptor` defaults: compression `NONE`, encoding `NONE`, checksum `MD5`.
- When compression is not `NONE` and encoding is `NONE`, the effective encoding is `BASE64`.
- When adding algorithms, update the enum, the codec tests, and the interceptor tests.
- JSpecify + NullAway handle nullability, so prefer explicit nullability annotations over defensive null-checks in internal code.
- Keep null-checks at boundaries (external inputs, SDK responses, IO).

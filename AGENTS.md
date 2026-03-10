# AGENTS

This repo is a Java library that encodes/decodes SQS payloads via an AWS SDK v2
execution interceptor.

## Commands
- `./gradlew check` (tests, checkstyle, spotless, jacoco)

Integration tests use LocalStack and require Docker.
Always run `./gradlew check` after each code change.

## Conventions
- Java target is JDK 17.
- Toolchain comes from `java-conventions` (default compile/runtime JDK 25).
- CI (`ci.yml`) runs full `build` on JDK 25.
- Legacy runtime checks (`legacy-jdk-check.yml`) run tests on JDK 17 and 21, and can be run manually or are required by publishing.
- Tests use AssertJ assertions.
- Keep responsibilities well-separated; avoid mixing concerns in the same class.
- Keep attribute keys in `CodecAttributes`.
- Use `MessageAttributeUtils` for reading/writing `MessageAttributeValue`s.
- Codec metadata is written to `x-codec-meta` using `v/c/h/s/l` keys; keep the format stable and update docs/tests when changing it.
- `SqsCodecInterceptor` defaults: compression `NONE`, checksum `MD5`.
- When compression is not `NONE`, the compressed binary bytes are encoded as unpadded URL-safe Base64.
- When adding algorithms, update the enum, the codec tests, and the interceptor tests.
- JSpecify + NullAway handle nullability, so prefer explicit nullability annotations over defensive null-checks in internal code.
- Keep null-checks at boundaries (external inputs, SDK responses, IO).

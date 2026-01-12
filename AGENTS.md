# AGENTS

This repo is a Java library that encodes/decodes SQS payloads via an AWS SDK v2
execution interceptor.

## Commands
- `./gradlew check` (tests, checkstyle, spotless, jacoco)
- `./gradlew check -DexcludeTags=integration` (skip LocalStack integration tests)

Integration tests use LocalStack and require Docker.

## Conventions
- JDK 21, Gradle.
- Keep attribute keys in `PayloadCodecAttributes`.
- Use `MessageAttributeUtils` for reading/writing `MessageAttributeValue`s.
- `SqsPayloadCodecInterceptor` defaults: compression `NONE`, encoding `NONE`, checksum `MD5`.
- When adding algorithms, update the enum, the codec tests, and the interceptor tests.

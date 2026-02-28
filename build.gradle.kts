plugins {
    `java-library`
    id("io.github.leanish.java-conventions")
}

group = "io.github.leanish"
version = "0.4.0-SNAPSHOT"
description = "AWS SQS payload interceptor for zstd+base64 encoding."

val jdkVersion = 21
java {
    sourceCompatibility = JavaVersion.toVersion(jdkVersion)
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(jdkVersion))
    }
}

dependencies {
    // BOMs
    compileOnly(platform("software.amazon.awssdk:bom:2.41.7"))
    testImplementation(platform("software.amazon.awssdk:bom:2.41.7"))
    testImplementation(platform("org.mockito:mockito-bom:5.21.0"))
    testImplementation(platform("org.testcontainers:testcontainers-bom:2.0.3"))

    // Consumers provide AWS SDK versions; keep it compileOnly to avoid forcing a version.
    compileOnly("software.amazon.awssdk:sqs")

    implementation("org.apache.commons:commons-lang3:3.20.0")
    implementation("com.github.luben:zstd-jni:1.5.7-7")
    implementation("org.xerial.snappy:snappy-java:1.1.10.8")

    testImplementation("software.amazon.awssdk:sqs")

    testImplementation("org.mockito:mockito-core")
    testImplementation("org.testcontainers:testcontainers-junit-jupiter")
    testImplementation("org.testcontainers:testcontainers-localstack")
}

tasks.withType<JavaExec>().configureEach {
    // Required for zstd-jni native access on JDK 21+ to avoid future hard failures.
    jvmArgs("--enable-native-access=ALL-UNNAMED")
}

tasks.withType<Test>().configureEach {
    // Required for zstd-jni native access on JDK 21+ to avoid future hard failures.
    jvmArgs("--enable-native-access=ALL-UNNAMED")
}

tasks.jacocoTestCoverageVerification {
    violationRules {
        rules.forEach { rule ->
            rule.limits.forEach { limit ->
                limit.minimum = "0.90".toBigDecimal()
            }
        }
    }
}

tasks.withType<JavaCompile>().configureEach {
    options.release.set(jdkVersion)
}

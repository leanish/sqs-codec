plugins {
    `java-library`
    id("io.github.leanish.java-conventions")
}

group = "io.github.leanish"
version = "0.4.0-SNAPSHOT"
description = "AWS SQS payload interceptor for automatic compression and encoding."

val targetJavaVersion = 17
val defaultTestRuntimeJavaVersion = 25

dependencies {
    // BOMs
    compileOnly(platform("software.amazon.awssdk:bom:2.42.36"))
    testImplementation(platform("software.amazon.awssdk:bom:2.42.36"))
    testImplementation(platform("org.mockito:mockito-bom:5.23.0"))
    testImplementation(platform("org.testcontainers:testcontainers-bom:2.0.4"))

    // Consumers provide AWS SDK versions; keep it compileOnly to avoid forcing a version.
    compileOnly("software.amazon.awssdk:sqs")

    implementation("com.github.luben:zstd-jni:1.5.7-7")
    implementation("org.xerial.snappy:snappy-java:1.1.10.8")

    testImplementation("software.amazon.awssdk:sqs")

    testImplementation("org.mockito:mockito-core")
    testImplementation("org.testcontainers:testcontainers-junit-jupiter")
    testImplementation("org.testcontainers:testcontainers-localstack")
}

java {
    // Keep IDE/tooling metadata aligned with the compile release target.
    sourceCompatibility = JavaVersion.toVersion(targetJavaVersion)
}

tasks.withType<JavaCompile>().configureEach {
    options.release.set(targetJavaVersion)
}

val testRuntimeJavaVersion = providers.gradleProperty("sqsCodec.testRuntimeJdkVersion")
    .map(String::toInt)
    .orElse(defaultTestRuntimeJavaVersion)
val testRuntimeLauncher = project.extensions.getByType<JavaToolchainService>().launcherFor {
    languageVersion.set(testRuntimeJavaVersion.map(JavaLanguageVersion::of))
}

tasks.withType<JavaExec>().configureEach {
    // Keep test runtime aligned with the workflow matrix.
    javaLauncher.set(testRuntimeLauncher)
    // Required for zstd-jni native access on JDK 21+ to avoid future hard failures.
    jvmArgs("--enable-native-access=ALL-UNNAMED")
}

tasks.withType<Test>().configureEach {
    // Keep test runtime aligned with the workflow matrix.
    javaLauncher.set(testRuntimeLauncher)
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

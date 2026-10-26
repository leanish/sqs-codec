import org.gradle.api.plugins.quality.CheckstyleExtension

plugins {
    `java-library`
    id("io.github.leanish.java-conventions")
}

group = "io.github.leanish"
version = "0.6.0-SNAPSHOT"
description = "AWS SQS payload interceptor for automatic compression and encoding."

val checkstyleExtension = extensions.getByType<CheckstyleExtension>()

// Mockito loads as a JVM agent instead of attaching itself at runtime, which JDK 21+ warns about and sandboxes forbid.
val mockitoAgent = configurations.create("mockitoAgent")

val targetJavaVersion = 17
val defaultTestRuntimeJavaVersion = 25

dependencies {
    // An explicit Checkstyle dependency replaces the conventions' default tool artifact.
    checkstyle(providers.provider { "com.puppycrawl.tools:checkstyle:${checkstyleExtension.toolVersion}" })

    // BOMs
    compileOnly(platform("software.amazon.awssdk:bom:2.56.0"))
    testImplementation(platform("software.amazon.awssdk:bom:2.56.0"))
    testImplementation(platform("org.mockito:mockito-bom:5.24.0"))
    mockitoAgent(platform("org.mockito:mockito-bom:5.24.0"))
    testImplementation(platform("org.testcontainers:testcontainers-bom:2.0.5"))

    // Consumers provide AWS SDK versions; keep it compileOnly to avoid forcing a version.
    compileOnly("software.amazon.awssdk:sqs")

    implementation("com.github.luben:zstd-jni:1.5.7-23")
    implementation("org.xerial.snappy:snappy-java:1.1.10.12")

    testImplementation("software.amazon.awssdk:sqs")

    testImplementation("org.mockito:mockito-core")
    mockitoAgent("org.mockito:mockito-core") { isTransitive = false }
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
    // A local copy, so the configuration cache doesn't capture the build script.
    val agent: FileCollection = mockitoAgent
    inputs.files(agent).withPropertyName("mockitoAgent").withNormalizer(ClasspathNormalizer::class.java)
    jvmArgumentProviders.add(CommandLineArgumentProvider { listOf("-javaagent:${agent.singleFile.absolutePath}") })
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

// The conventions configure PIT; integration tests need Docker, zstd-jni needs native access in PIT's test JVMs, and
// Mockito loads there as an agent too.
pitest {
    excludedGroups = listOf("integration")
    jvmArgs = mockitoAgent.elements.map { agent ->
        listOf("--enable-native-access=ALL-UNNAMED", "-javaagent:${agent.single().asFile.absolutePath}")
    }
}

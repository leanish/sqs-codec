import net.ltgt.gradle.errorprone.errorprone

plugins {
    `java-library`
    `maven-publish`
    checkstyle
    jacoco
    id("com.diffplug.spotless") version "8.1.0"
    id("net.ltgt.errorprone") version "4.4.0"
}

group = "io.github.leanish"
version = "0.1.0"

java {
    sourceCompatibility = JavaVersion.VERSION_21
}

jacoco {
    toolVersion = "0.8.14"
}

tasks.wrapper {
    gradleVersion = "9.2.1"
    distributionType = Wrapper.DistributionType.BIN
}

checkstyle {
    toolVersion = "12.1.2"
    configFile = file("config/checkstyle/checkstyle.xml")
    maxWarnings = 0
}

tasks.withType<Checkstyle>().configureEach {
    configDirectory.set(file("config/checkstyle"))
    reports {
        xml.required.set(true)
        html.required.set(true)
    }
}

repositories {
    mavenLocal()
    mavenCentral()
}

dependencies {
    // BOMs
    compileOnly(platform("software.amazon.awssdk:bom:2.41.5"))
    testImplementation(platform("software.amazon.awssdk:bom:2.41.5"))
    testImplementation(platform("org.junit:junit-bom:6.0.2"))
    testImplementation(platform("org.mockito:mockito-bom:5.21.0"))
    testImplementation(platform("org.testcontainers:testcontainers-bom:2.0.3"))

    // Consumers provide AWS SDK versions; keep it compileOnly to avoid forcing a version.
    compileOnly("software.amazon.awssdk:sqs")

    implementation("org.apache.commons:commons-lang3:3.20.0")
    implementation("com.github.luben:zstd-jni:1.5.7-6")
    implementation("org.xerial.snappy:snappy-java:1.1.10.8")

    compileOnly("org.jspecify:jspecify:1.0.0")
    compileOnly("org.jetbrains:annotations:26.0.2-1")
    compileOnly("org.projectlombok:lombok:1.18.42")
    annotationProcessor("org.projectlombok:lombok:1.18.42")

    testImplementation("software.amazon.awssdk:sqs")

    testImplementation("org.junit.jupiter:junit-jupiter")
    testImplementation("org.assertj:assertj-core:3.27.6")
    testImplementation("org.mockito:mockito-core")
    testImplementation("org.testcontainers:testcontainers-junit-jupiter")
    testImplementation("org.testcontainers:testcontainers-localstack")
    // IDE test runners use the launcher when not delegating to Gradle.
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testCompileOnly("org.projectlombok:lombok:1.18.42")
    testAnnotationProcessor("org.projectlombok:lombok:1.18.42")

    errorprone("com.google.errorprone:error_prone_core:2.46.0")
    errorprone("com.uber.nullaway:nullaway:0.12.15")
}

tasks.withType<JavaExec>().configureEach {
    // Required for zstd-jni native access on JDK 21+ to avoid future hard failures.
    jvmArgs("--enable-native-access=ALL-UNNAMED")
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform {
        val excludedTags = System.getProperty("excludeTags")
        if (!excludedTags.isNullOrBlank()) {
            excludeTags(*excludedTags.split(',')
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .toTypedArray())
        }
    }
    // Required for zstd-jni native access on JDK 21+ to avoid future hard failures.
    jvmArgs("--enable-native-access=ALL-UNNAMED")
}

tasks.jacocoTestReport {
    dependsOn(tasks.test)
    reports {
        xml.required.set(true)
        html.required.set(true)
    }
}

tasks.jacocoTestCoverageVerification {
    dependsOn(tasks.test)
    violationRules {
        rule {
            limit {
                counter = "LINE"
                value = "COVEREDRATIO"
                minimum = "0.90".toBigDecimal()
            }
        }
    }
}

tasks.withType<JavaCompile>().configureEach {
    // Required from errorprone 2.46.0+ on JDK 21
    options.compilerArgs.add("-XDaddTypeAnnotationsToSymbol=true")
    options.errorprone {
        disableWarningsInGeneratedCode.set(true)
        errorproneArgs.addAll(
            "-Xep:NullAway:ERROR",
            "-XepOpt:NullAway:AnnotatedPackages=io.github.leanish",
        )
    }
}

tasks.named<JavaCompile>("compileTestJava").configure {
    options.errorprone.isEnabled = false
}

spotless {
    java {
        removeUnusedImports()
        trimTrailingWhitespace()
        endWithNewline()
        licenseHeaderFile("LICENSE_HEADER")
    }
}

tasks.register<Copy>("installGitHooks") {
    description = "Copies git hooks from scripts/git-hooks to .git/hooks"

    from(layout.projectDirectory.file("scripts/git-hooks/pre-commit")) {
        filePermissions {
            unix("755")
        }
    }
    into(layout.projectDirectory.dir(".git/hooks"))
}

tasks.named("build") {
    dependsOn("installGitHooks")
}

tasks.named("check") {
    dependsOn("jacocoTestCoverageVerification")
}

// Task to set up the project initially
tasks.register("setupProject") {
    description = "Sets up the project with git hooks and initial configuration"
    dependsOn("installGitHooks")

    doLast {
        println("Project setup completed!")
        println("Git hooks installed in .git/hooks/")
    }
}

publishing {
    publications {
        create<MavenPublication>("mavenJava") {
            from(components["java"])
            pom {
                name.set("sqs-payload-codec")
                description.set("AWS SQS payload interceptor for zstd+base64 encoding.")
                url.set("https://github.com/lean-ish/sqs-payload-codec")
                licenses {
                    license {
                        name.set("The MIT License")
                        url.set("https://opensource.org/licenses/MIT")
                    }
                }
                developers {
                    developer {
                        id.set("lean-ish")
                        name.set("Leandro Aguiar")
                        url.set("https://github.com/lean-ish")
                    }
                }
                scm {
                    url.set("https://github.com/lean-ish/sqs-payload-codec")
                    connection.set("scm:git:https://github.com/lean-ish/sqs-payload-codec.git")
                    developerConnection.set("scm:git:ssh://git@github.com/lean-ish/sqs-payload-codec.git")
                }
            }
        }
    }

    repositories {
        mavenLocal()
        maven {
            name = "GitHubPackages"
            url = uri("https://maven.pkg.github.com/lean-ish/sqs-payload-codec")
            credentials {
                username = findProperty("gpr.user") as String? ?: System.getenv("GITHUB_ACTOR")
                password = findProperty("gpr.key") as String? ?: System.getenv("GITHUB_TOKEN")
            }
        }
    }
}

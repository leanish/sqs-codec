import net.ltgt.gradle.errorprone.errorprone

plugins {
    `java-library`
    `maven-publish`
    checkstyle
    id("com.diffplug.spotless") version "8.1.0"
    id("net.ltgt.errorprone") version "4.3.0"
}

group = "io.github.leanish"
version = "0.1.0-SNAPSHOT"

java {
    sourceCompatibility = JavaVersion.VERSION_21
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
    compileOnly(platform("software.amazon.awssdk:bom:2.41.4"))
    testImplementation(platform("software.amazon.awssdk:bom:2.41.4"))
    testImplementation(platform("org.junit:junit-bom:6.0.2"))
    testImplementation(platform("org.mockito:mockito-bom:5.15.2"))

    // Consumers provide AWS SDK versions; keep it compileOnly to avoid forcing a version.
    compileOnly("software.amazon.awssdk:sqs")

    implementation("com.github.luben:zstd-jni:1.5.7-6")
    implementation("org.apache.commons:commons-lang3:3.20.0")

    compileOnly("org.jspecify:jspecify:1.0.0")

    testImplementation("software.amazon.awssdk:sqs")

    testImplementation("org.junit.jupiter:junit-jupiter")
    testImplementation("org.assertj:assertj-core:3.26.3")
    testImplementation("org.mockito:mockito-core")
    // IDE test runners use the launcher when not delegating to Gradle.
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")

    errorprone("com.google.errorprone:error_prone_core:2.45.0")
    errorprone("com.uber.nullaway:nullaway:0.12.15")
}

tasks.withType<JavaExec>().configureEach {
    // Required for zstd-jni native access on JDK 21+ to avoid future hard failures.
    jvmArgs("--enable-native-access=ALL-UNNAMED")
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    // Required for zstd-jni native access on JDK 21+ to avoid future hard failures.
    jvmArgs("--enable-native-access=ALL-UNNAMED")
}

tasks.withType<JavaCompile>().configureEach {
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

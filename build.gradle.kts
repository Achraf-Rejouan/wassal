plugins {
    java
    alias(libs.plugins.spring.boot) apply false
    alias(libs.plugins.spring.dep.mgmt) apply false
    alias(libs.plugins.spotless)
}

allprojects {
    group = "dev.wassal"
    version = "0.1.0-SNAPSHOT"

    repositories { mavenCentral() }
}

// Spring Boot's BOM pins testcontainers, silently overriding the version catalogue. Without
// this override the build resolves an older client that cannot negotiate with modern Docker
// Engine (see docs/bug-log.md). The catalogue stays the single source of truth.
val testcontainersVersion = libs.versions.testcontainers.get()

subprojects {
    apply(plugin = "java")
    apply(plugin = "com.diffplug.spotless")

    extensions.configure<JavaPluginExtension> {
        toolchain { languageVersion = JavaLanguageVersion.of(21) }
    }

    // Formatting is settled by the formatter, not by discussion (coding-standards.md).
    extensions.configure<com.diffplug.gradle.spotless.SpotlessExtension> {
        java {
            googleJavaFormat("1.24.0").aosp()
            removeUnusedImports()
            trimTrailingWhitespace()
            endWithNewline()
            targetExclude("**/build/**")
        }
    }

    plugins.withId("io.spring.dependency-management") {
        extra["testcontainers.version"] = testcontainersVersion
    }

    tasks.withType<Test>().configureEach {
        useJUnitPlatform()
        testLogging { events("passed", "skipped", "failed") }

        // Docker Engine 29 refuses API < 1.40, and two things conspire to send 1.32:
        //   1. a stale `docker.client.strategy` pin in ~/.testcontainers.properties selects
        //      UnixSocketClientProviderStrategy, which hardcodes the old default; and
        //   2. that strategy never consults DOCKER_API_VERSION.
        // The failure surfaces as "Could not find a valid Docker environment", which points
        // nowhere near the real cause (see docs/bug-log.md).
        //
        // Overridden here rather than by editing a developer's home directory, so the fix is
        // committed, reproducible, and identical in CI.
        systemProperty(
            "docker.client.strategy",
            providers
                .systemProperty("docker.client.strategy")
                .getOrElse(
                    "org.testcontainers.dockerclient" +
                        ".EnvironmentAndSystemPropertyClientProviderStrategy"
                ),
        )
        // DOCKER_HOST must be set for EnvironmentAndSystemPropertyClientProviderStrategy to
        // be considered applicable at all — it is the only strategy that honours
        // DOCKER_API_VERSION. Defaulted, not hardcoded, so a rootless or remote daemon still
        // works by exporting DOCKER_HOST.
        environment(
            "DOCKER_HOST",
            providers.environmentVariable("DOCKER_HOST").getOrElse("unix:///var/run/docker.sock"),
        )
        // docker-java's knob is the `api.version` SYSTEM PROPERTY. It does not read a
        // DOCKER_API_VERSION environment variable, which is the trap that makes this look
        // unfixable from the shell.
        systemProperty("api.version", providers.systemProperty("api.version").getOrElse("1.43"))
    }

    tasks.withType<JavaCompile>().configureEach {
        options.compilerArgs.addAll(listOf("-Xlint:all", "-parameters"))
    }
}

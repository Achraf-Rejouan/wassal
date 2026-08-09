// The deliverable, separated so it is visible rather than buried in src/test.
// @Disabled here fails the build — see .github/workflows/ci.yml.
plugins { java }
dependencies {
    testImplementation(platform(libs.testcontainers.bom))
    testImplementation(libs.testcontainers.junit)
    testImplementation(libs.testcontainers.postgresql)
    testImplementation(libs.assertj.core)
    testImplementation(libs.awaitility)
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.4")
}

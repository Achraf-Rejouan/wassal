// The chaos proof. Runs in its own CI workflow: allowed to be slow, never allowed to be
// skipped (risk R-4). @Disabled here fails the build.
plugins { java }
dependencies {
    testImplementation(platform(libs.testcontainers.bom))
    testImplementation(libs.testcontainers.junit)
    testImplementation(libs.testcontainers.postgresql)
    testImplementation(libs.testcontainers.toxiproxy)
    testImplementation(libs.assertj.core)
    testImplementation(libs.awaitility)
    testImplementation(libs.postgresql)
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.4")
    testImplementation("redis.clients:jedis:5.2.0")
}

plugins {
    alias(libs.plugins.spring.boot)
    alias(libs.plugins.spring.dep.mgmt)
}
dependencies {
    implementation(project(":contracts"))
    implementation(libs.spring.boot.starter.web)
    implementation(libs.spring.boot.starter.actuator)
    implementation(libs.spring.boot.starter.validation)
    implementation(libs.micrometer.prometheus)
    implementation(libs.spring.kafka)
    implementation("org.springframework.boot:spring-boot-starter-jdbc")
    implementation(libs.spring.boot.starter.data.redis)
    implementation(libs.flyway.core)
    runtimeOnly(libs.flyway.postgresql)
    runtimeOnly(libs.postgresql)
    compileOnly(libs.postgresql)

    testImplementation(libs.spring.boot.starter.test)
    testImplementation(libs.assertj.core)
    testImplementation(libs.archunit.junit5)
    testImplementation(platform(libs.testcontainers.bom))
    testImplementation(libs.testcontainers.junit)
    testImplementation(libs.testcontainers.postgresql)
    testImplementation(libs.awaitility)
    testImplementation("org.junit.jupiter:junit-jupiter-params:5.11.4")
    // Test-source-set only: the Redis claim variant exists to be BENCHMARKED
    // against the production Postgres claim (S2-12), never to be wired in.
    testImplementation("redis.clients:jedis:5.2.0")
}

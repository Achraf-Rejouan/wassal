// MUST NOT import any service module. Its independence is what stops the proof being
// circular (architecture-review.md finding F-2, security threat E-02).
plugins {
    alias(libs.plugins.spring.boot)
    alias(libs.plugins.spring.dep.mgmt)
}
dependencies {
    implementation(project(":contracts"))
    implementation(libs.spring.boot.starter.web)
    implementation(libs.postgresql)

    testImplementation(libs.spring.boot.starter.test)
    testImplementation(libs.archunit.junit5)
}

// No application code yet — this module lands in a later sprint (docs/08-delivery-plan.md).
// Disabled explicitly so `./gradlew build` fails for real reasons only.
tasks.named<org.springframework.boot.gradle.tasks.bundling.BootJar>("bootJar") { enabled = false }
tasks.named<Jar>("jar") { enabled = true }

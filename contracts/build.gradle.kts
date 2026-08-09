// The ONLY shared compile-time module. Every class added here becomes a coupling point
// between services — additions need justification (docs/project-structure.md).
plugins { `java-library` }
dependencies {
    api("com.fasterxml.jackson.core:jackson-annotations:2.18.2")
}

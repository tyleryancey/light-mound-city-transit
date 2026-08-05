// Standalone probe. NOT part of the tool build — it exists only to answer
// "does kotlinx-serialization-protobuf handle Metro's feed?" (docs/03 §2, Phase 0.3).
plugins {
    kotlin("jvm") version "2.0.21"
    kotlin("plugin.serialization") version "2.0.21"
    application
}
repositories { mavenCentral() }
dependencies {
    // The only coordinate under test. Allow-listed in the tool build via the
    // org.jetbrains.kotlinx:kotlinx-serialization prefix match.
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-protobuf:1.7.3")
}
kotlin { jvmToolchain(17) }
application {
    mainClass.set("ProbeKt")
    // Point at the captured .pb files; defaults to ../fixtures
    applicationDefaultJvmArgs = listOf("-Dfixtures=${project.findProperty("fixtures") ?: "../fixtures"}")
}

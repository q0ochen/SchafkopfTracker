import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// Datenhaltung als reines Kotlin/JVM: JSON-Dateien, Sicherung (Export/Import) und der REST-Aufruf für die
// optionale KI-Zusammenfassung. Ohne Android-Abhängigkeit, damit alles auf der JVM testbar ist.
plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
        allWarningsAsErrors.set(true)
    }
}

dependencies {
    api(project(":domain"))
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.okhttp)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.okhttp.mockwebserver)
}

tasks.test {
    useJUnitPlatform()

    // Golden-Daten für die Web-App (web/src/data): WebFormatGoldenTest vergleicht die eingecheckte Datei mit dem, was
    // der Kotlin-Code heute schreibt und liest, und schlägt fehl, wenn sie veraltet ist. Mit -PgoldenWrite=true schreibt
    // er sie neu. Die Datei ist Eingabe des Tests, damit er nach jeder Änderung an ihr wieder läuft; sie darf fehlen
    // (deshalb als Dateisammlung angemeldet), dann schlägt nur der Vergleich fehl.
    val goldenFile = layout.projectDirectory.file("../web/src/data/golden/kotlin-format-golden.json").asFile.normalize()
    systemProperty("golden.file", goldenFile.absolutePath)
    systemProperty("golden.write", providers.gradleProperty("goldenWrite").getOrElse("false"))
    inputs.files(goldenFile).withPropertyName("webFormatGolden").withPathSensitivity(PathSensitivity.NONE)
}

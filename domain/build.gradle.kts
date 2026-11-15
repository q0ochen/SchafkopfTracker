import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// Reines Kotlin/JVM ohne jede Android-Abhängigkeit: Regelwerk, Abrechnung, Statistik, Ports.
// Dadurch ist die gesamte Fachlogik schnell und vollständig auf der JVM testbar.
plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kover)
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
    api(libs.kotlinx.coroutines.core)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
    testImplementation(libs.kotlinx.coroutines.test)
    // Nachgestellte Ablagen (MockK) und Prüfen von Datenströmen (Turbine), wie in der Vorgabe verlangt.
    testImplementation(libs.mockk)
    testImplementation(libs.turbine)
    // Nur in Tests: prüft, dass die für das Sprachmodell erzeugten Fakten gültiges JSON sind.
    testImplementation(libs.kotlinx.serialization.json)
}

// Golden-Daten für die Web-App (web/src/core, TypeScript): WebGoldenTest lässt diesen Kern viele feste Eingaben
// durchrechnen und vergleicht das Ergebnis mit der eingecheckten Datei web/src/core/golden/kotlin-golden.json, gegen
// die der TypeScript-Port geprüft wird. Rechnet der Kern anders, schlägt der Test fehl, bis die Datei neu erzeugt ist:
//   tools/jvm-build.sh :domain:test --tests '*WebGoldenTest' -PgoldenWrite=true
// Der Pfad wird vom Modulordner aus bestimmt, damit es auch mit dem Hilfsbau von tools/jvm-build.sh stimmt.
val webGoldenFile: File = file("../web/src/core/golden/kotlin-golden.json")

tasks.test {
    useJUnitPlatform()
    systemProperty("golden.file", webGoldenFile.absolutePath)
    systemProperty("golden.write", providers.gradleProperty("goldenWrite").getOrElse("false"))
    // Die Datei ist (optionale) Eingabe des Tests: Ändert sie sich, läuft der Test erneut. Fehlt sie, meldet der Test
    // das selbst.
    inputs.files(webGoldenFile).withPropertyName("webGoldenFile").withPathSensitivity(PathSensitivity.NONE).optional()
}

// Testabdeckung der Fachlogik: `:domain:koverVerify` schlägt fehl, wenn weniger als 90 % der Zeilen von Tests
// durchlaufen werden; `:domain:koverHtmlReport` zeigt, welche Zeilen fehlen.
kover {
    reports {
        verify {
            rule {
                minBound(90)
            }
        }
    }
}

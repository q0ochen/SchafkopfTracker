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

tasks.test {
    useJUnitPlatform()
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

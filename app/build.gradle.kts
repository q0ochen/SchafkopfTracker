import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// Die Android-App: Oberfläche (Jetpack Compose), ViewModels und die Kompositionswurzel (AppContainer).
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

// Testversionen aus der CI (-PciBuildNumber=<Laufnummer>, siehe .github/workflows/android.yml) bekommen eine
// steigende Versionsnummer, damit Android, F-Droid und Obtainium jede als Update erkennen: versionCode
// 1000 + Laufnummer, versionName „0.1.0-test.<Laufnummer>“. Sie werden als Release gebaut (flüssiger als ein
// Debug-Build) und mit dem öffentlichen Test-Schlüssel signiert. Lokale Builds bleiben bei den festen Werten unten.
val ciBuildNumber: Int? = providers.gradleProperty("ciBuildNumber").orNull?.trim()?.toIntOrNull()?.takeIf { it > 0 }

android {
    namespace = "io.github.q0ochen.schafkopf"
    compileSdk = 37

    defaultConfig {
        applicationId = "io.github.q0ochen.schafkopf"
        minSdk = 26
        targetSdk = 37
        // F-Droid liest diese beiden Werte per Textsuche: Sie müssen als feste Zahlen bzw. Texte hier stehen.
        versionCode = 1
        versionName = "0.1.0"
        // Rücksprung aus der Anmeldung bei Google (AppAuth): eigenes URI-Schema = Paketname, wie Google es für
        // Android-Clients verlangt (io.github.q0ochen.schafkopf:/oauth2redirect). Muss zu
        // AppAuthDriveAccount.REDIRECT_SCHEME passen; ein applicationIdSuffix ändert daran nichts.
        manifestPlaceholders["appAuthRedirectScheme"] = "io.github.q0ochen.schafkopf"
    }

    // Fester, öffentlicher Test-Schlüssel (kein Geheimnis, liegt bewusst im Repository): Alle Debug-Builds und alle
    // Testversionen haben dieselbe Signatur und lassen sich als Update über eine installierte Version installieren,
    // ohne dass Daten verloren gehen. Für echte Veröffentlichungen ist er tabu (siehe docs/F-DROID.md).
    signingConfigs {
        getByName("debug") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // Nur Testversionen aus der CI werden signiert; ein normaler Release-Build bleibt unsigniert (so baut
            // auch F-Droid und signiert dann selbst).
            if (ciBuildNumber != null) signingConfig = signingConfigs.getByName("debug")
        }
    }

    // Zwei Ausgaben derselben App:
    //  - fdroid: ausschließlich freie Software, so wie F-Droid sie baut.
    //  - full:   zusätzlich die Anbindung der Wear-OS-Uhr, die nur über die proprietären
    //            Google Play Services möglich ist.
    flavorDimensions += "distribution"
    productFlavors {
        create("fdroid") {
            dimension = "distribution"
        }
        create("full") {
            dimension = "distribution"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }

    // F-Droid lehnt den verschlüsselten „Dependency metadata“-Block von Google ab.
    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }

    testOptions {
        unitTests {
            // Die Tests der Oberfläche laufen mit Robolectric auf der JVM und brauchen die Ressourcen der App.
            isIncludeAndroidResources = true
            all { test ->
                // Bildschirmfotos werden bei jedem Lauf neu geschrieben (app/build/outputs/roborazzi).
                test.systemProperty("roborazzi.test.record", "true")
            }
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

// Versionsnummer der Testversionen (siehe oben). Die festen Werte in defaultConfig bleiben stehen, weil F-Droid sie
// später per Textsuche aus dieser Datei liest.
androidComponents {
    onVariants { variant ->
        val build = ciBuildNumber ?: return@onVariants
        val baseName = android.defaultConfig.versionName ?: "0.0.0"
        variant.outputs.forEach { output ->
            output.versionCode.set(1000 + build)
            output.versionName.set("$baseName-test.$build")
        }
    }
}

dependencies {
    implementation(project(":domain"))
    implementation(project(":data"))
    implementation(project(":database"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.koin.android)
    implementation(libs.koin.androidx.compose)
    implementation(libs.androidx.work.runtime)
    implementation(libs.okhttp)
    implementation(libs.appauth)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.core)
    implementation(libs.compose.ui.tooling.preview)
    debugImplementation(libs.compose.ui.tooling)

    testImplementation(libs.junit4)
    testImplementation(libs.robolectric)
    testImplementation(libs.roborazzi)
    testImplementation(libs.roborazzi.compose)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.compose.ui.test.junit4)
    testImplementation(libs.androidx.work.testing)
    debugImplementation(libs.compose.ui.test.manifest)
}

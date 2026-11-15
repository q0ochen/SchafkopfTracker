import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// Die Android-App: Oberfläche (Jetpack Compose), ViewModels und die Kompositionswurzel (AppContainer).
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

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
    }

    buildTypes {
        release {
            isMinifyEnabled = false
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

dependencies {
    implementation(project(":domain"))
    implementation(project(":data"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.koin.android)
    implementation(libs.koin.androidx.compose)

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
    debugImplementation(libs.compose.ui.test.manifest)
}

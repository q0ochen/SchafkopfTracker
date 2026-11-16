// Alle Plugins werden hier nur bekannt gemacht und in den Modulen angewendet.
// Es gibt bewusst kein Google-Services-Plugin und kein Firebase (F-Droid, siehe docs/ARCHITEKTUR.md, Abschnitt 0).
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.kover) apply false
}

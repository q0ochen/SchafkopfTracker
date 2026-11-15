#!/usr/bin/env bash
# Baut und testet nur die reinen Kotlin/JVM-Module (:domain und :data), ohne das Android-Plugin aufzulösen.
#
# Wozu: In manchen Umgebungen (zum Beispiel in der Cloud von Claude Code mit dem Standard-Netz) ist dl.google.com
# gesperrt. Dann findet Gradle das Android-Plugin nicht, und schon „./gradlew :domain:test“ scheitert, weil das
# Wurzelprojekt das Plugin bekannt macht. Dieses Skript legt außerhalb des Repositorys einen kleinen Hilfsbau an,
# der nur die beiden JVM-Module einbindet (dieselben Quellen, dieselben Versionen aus gradle/libs.versions.toml).
#
# Aufruf vom Projektverzeichnis aus, mit beliebigen Gradle-Aufgaben und -Schaltern:
#   tools/jvm-build.sh :domain:test :data:test :domain:koverVerify --console=plain
#
# Für :app und :database braucht es weiterhin das Android-SDK und Zugriff auf dl.google.com.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
KEY="$(printf '%s' "$ROOT" | cksum | cut -d' ' -f1)"
DIR="${TMPDIR:-/tmp}/schafkopf-jvm-build-$KEY"
mkdir -p "$DIR"

# Der Spiegel von Maven Central bei Google steht vorn: Maven Central selbst drosselt Rechenzentren zeitweise
# (HTTP 429), und Gradle merkt sich eine so verfehlte Bibliothek einen Tag lang als „nicht vorhanden“.
cat > "$DIR/settings.gradle.kts" <<SETTINGS
pluginManagement {
    repositories {
        maven("https://maven-central.storage-download.googleapis.com/maven2/") { name = "MavenCentralMirror" }
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        maven("https://maven-central.storage-download.googleapis.com/maven2/") { name = "MavenCentralMirror" }
        mavenCentral()
    }
    versionCatalogs {
        create("libs") {
            from(files("$ROOT/gradle/libs.versions.toml"))
        }
    }
}
rootProject.name = "SchafkopfTrackerJvm"
include(":domain", ":data")
project(":domain").projectDir = file("$ROOT/domain")
project(":data").projectDir = file("$ROOT/data")
SETTINGS

cat > "$DIR/build.gradle.kts" <<'BUILD'
plugins {
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.kover) apply false
}
BUILD

cp "$ROOT/gradle.properties" "$DIR/gradle.properties"
exec bash "$ROOT/gradlew" -p "$DIR" "$@"

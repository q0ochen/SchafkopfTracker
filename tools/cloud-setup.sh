#!/bin/bash
# Richtet eine frische Linux-Maschine (zum Beispiel eine Cloud-Sitzung von Claude Code) für den Bau der App ein.
#
# Dasselbe Skript ist als Einrichtungsskript in der Cloud-Umgebung „SchafkopfTracker“ hinterlegt und läuft dort
# beim Start jeder neuen Sitzung von selbst. Wer es ändert, ändert bitte beide Stellen.
#
# Von Hand: aus dem Ordner aufrufen, in dem der Projektordner „SchafkopfTracker“ liegt:
#   bash SchafkopfTracker/tools/cloud-setup.sh
#
# Voraussetzung: Zugriff auf dl.google.com (in der Cloud-Umgebung „SchafkopfTracker“ freigegeben). Den
# Lizenzbedingungen des Android-SDK hat der Projektinhaber am 15.11.2026 für dieses Projekt zugestimmt.
#
# Android-SDK und Bibliotheks-Spiegel für den Bau von SchafkopfTracker. Ein Fehler hier darf den Start nicht verhindern.
SDK=/opt/android-sdk
CLI="$SDK/cmdline-tools/latest/bin/android"
TOOLS=https://dl.google.com/android/repository/commandlinetools-linux-16111833_latest.zip
if [ ! -x "$CLI" ]; then
  rm -rf /tmp/skt-sdk && mkdir -p /tmp/skt-sdk "$SDK/cmdline-tools"
  curl -fsSL --retry 3 -o /tmp/skt-sdk/tools.zip "$TOOLS" \
    && echo "e025545c62a8e64c7559119566a569fb1dec5f60  /tmp/skt-sdk/tools.zip" | sha1sum -c - \
    && unzip -q /tmp/skt-sdk/tools.zip -d /tmp/skt-sdk \
    && mv /tmp/skt-sdk/cmdline-tools "$SDK/cmdline-tools/latest"
  rm -rf /tmp/skt-sdk
fi
# Plattform und Werkzeuge, mit denen die App gebaut wird (ohne Nutzungsstatistik an Google).
if [ -x "$CLI" ]; then
  "$CLI" --no-metrics --sdk="$SDK" sdk install platforms/android-37.0 build-tools/36.0.0 platform-tools </dev/null || true
fi
# Dem Projekt sagen, wo das SDK liegt (die Datei wird nicht eingecheckt).
if [ -d SchafkopfTracker ]; then
  printf 'sdk.dir=%s\n' "$SDK" > SchafkopfTracker/local.properties
fi
# Bibliotheken über den Spiegel von Maven Central bei Google laden: Maven Central selbst drosselt diese Rechner.
mkdir -p "${HOME:-/root}/.gradle/init.d"
cat > "${HOME:-/root}/.gradle/init.d/central-mirror.init.gradle.kts" <<'EOF'
val centralMirror = "https://maven-central.storage-download.googleapis.com/maven2/"

fun org.gradle.api.artifacts.dsl.RepositoryHandler.mirrorFirst() {
    if (findByName("MavenCentralMirror") != null) return
    val mirror = maven(centralMirror) { name = "MavenCentralMirror" }
    remove(mirror)
    addFirst(mirror)
}

settingsEvaluated {
    pluginManagement.repositories.mirrorFirst()
    dependencyResolutionManagement.repositories.mirrorFirst()
}

// Auch Robolectric (Bildschirmtests) lädt seine Android-Laufzeit dann über den Spiegel.
allprojects {
    tasks.withType<Test>().configureEach {
        systemProperty("robolectric.dependency.repo.url", centralMirror.removeSuffix("/"))
        systemProperty("robolectric.dependency.repo.id", "central-mirror")
    }
}
EOF
exit 0

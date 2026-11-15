package io.github.q0ochen.schafkopf.data.llm

import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

private const val CONNECT_TIMEOUT_SECONDS = 10L
private const val READ_TIMEOUT_SECONDS = 60L
private const val CALL_TIMEOUT_SECONDS = 180L

/**
 * Der HTTP-Client für Aufrufe ins Netz: höchstens 10 Sekunden für den Verbindungsaufbau, höchstens 60 Sekunden
 * Warten auf das nächste Stück der Antwort und höchstens 3 Minuten für einen Aufruf insgesamt. Das Gesamtlimit
 * braucht es, weil das Warten auf jedes einzelne Stück neu zählt: Ein Dienst, der seine Antwort nur tröpfchenweise
 * schickt, könnte einen Aufruf sonst beliebig lange offen halten.
 *
 * Der Client schreibt nichts mit (in einem Protokoll stünde sonst der Schlüssel) und hat keine eigenen Zusätze, die
 * Anfragen von sich aus wiederholen.
 *
 * Ein Client genügt für die ganze App: einmal in der Kompositionswurzel anlegen und überall übergeben. Die
 * Anbindungen der Sprachmodelle verschärfen die Regeln für ihre eigenen Aufrufe selbst (keine Weiterleitungen,
 * keine Wiederholung durch die Netzbibliothek, kein Proxy für unverschlüsselte Aufrufe), gleich welchen Client
 * sie bekommen.
 */
fun defaultHttpClient(): OkHttpClient =
    OkHttpClient.Builder()
        .connectTimeout(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .readTimeout(READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .callTimeout(CALL_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .build()

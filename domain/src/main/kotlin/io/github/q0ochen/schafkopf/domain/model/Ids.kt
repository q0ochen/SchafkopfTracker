package io.github.q0ochen.schafkopf.domain.model

/** Kennung eines Spielers. Bleibt gleich, auch wenn der Name geändert wird. */
@JvmInline
value class PlayerId(val value: String)

/** Kennung eines Abends. */
@JvmInline
value class EveningId(val value: String)

/** Kennung eines Eintrags (Spiel, Zusammengeworfen, Bockrunde, freie Buchung) innerhalb eines Abends. */
@JvmInline
value class EntryId(val value: String)

/** Erzeugt neue, eindeutige Kennungen. Wird injiziert, damit Tests feste Werte verwenden können. */
fun interface IdGenerator {
    fun newId(): String
}

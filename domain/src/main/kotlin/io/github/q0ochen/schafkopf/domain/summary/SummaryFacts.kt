package io.github.q0ochen.schafkopf.domain.summary

import io.github.q0ochen.schafkopf.domain.model.PlayerId

/**
 * Ein Spieler in der Zusammenfassung.
 *
 * @property seat Sitzplatz ab 1, in Sitzreihenfolge. Aus ihm entsteht der Platzhalter `[[S1]]` … `[[S4]]`, der
 *   statt des Namens an ein Sprachmodell geht.
 */
data class SummaryPlayer(val id: PlayerId, val seat: Int, val name: String)

/** Eine Zeile der Rangliste. [balance] ist der Endstand als fertiger Text mit Vorzeichen. */
data class SummaryStanding(val player: SummaryPlayer, val rank: Int, val balance: String)

/**
 * Wer auf dem ersten oder auf dem letzten Platz steht. Bei Gleichstand sind es mehrere Spieler in Sitzreihenfolge.
 * [balance] ist ihr gemeinsamer Endstand als fertiger Text mit Vorzeichen.
 */
data class SummaryPlace(val players: List<SummaryPlayer>, val balance: String)

/**
 * Der Lege-Pechvogel: wer in Spielen, in denen er selbst geklopft (gelegt) hat, zusammen am meisten verloren hat.
 *
 * @property lostGames Wie viele selbst geklopfte Spiele er verloren hat.
 * @property lostAmount Was er in diesen Spielen zusammen verloren hat, als fertiger Text ohne Vorzeichen.
 */
data class KlopferLoser(val player: SummaryPlayer, val lostGames: Int, val lostAmount: String)

/** Wie oft ein Spieler Schneider ([schneider]) und wie oft er schwarz ([schwarz]) verloren hat. */
data class SchneiderLoser(val player: SummaryPlayer, val schneider: Int, val schwarz: Int)

/**
 * Das Spiel des Abends: das Spiel mit dem höchsten Spielwert.
 *
 * @property type Die Spielart als Text, zum Beispiel „Rufspiel“, „Wenz“ oder „Herz-Solo“.
 * @property partner Der Mitspieler beim Rufspiel, sonst `null`.
 * @property won Ob der Ansager gewonnen hat.
 * @property value Der Spielwert je Zahlung als fertiger Text ohne Vorzeichen.
 * @property declarerAmount Der Betrag des Ansagers aus diesem Spiel als fertiger Text mit Vorzeichen.
 * @property specials Besonderheiten als fertige Texte, zum Beispiel „Tout“, „2 Klopfer“, „Kontra“, „Bockspiel“.
 */
data class SummaryGame(
    val number: Int,
    val type: String,
    val declarer: SummaryPlayer,
    val partner: SummaryPlayer?,
    val won: Boolean,
    val value: String,
    val declarerAmount: String,
    val specials: List<String>,
)

/** Wie oft ein Spieler mit 6 Nixern eine Bockrunde ausgelöst hat. */
data class NixerCount(val player: SummaryPlayer, val count: Int)

/**
 * Die Bockrunden des Abends.
 *
 * @property count Wie oft eine Bockrunde ausgelöst wurde.
 * @property nixer Wer die 6 Nixer hatte, in Sitzreihenfolge; leer, wenn das nicht eingetragen wurde.
 */
data class BockRounds(val count: Int, val nixer: List<NixerCount>)

/** Der Solo-König: wer die meisten Einzelspiele (Farbsolo, Wenz, Geier) als Ansager gewonnen hat. */
data class SoloKing(val player: SummaryPlayer, val soloWins: Int)

/** Die längste Siegesserie: [length] gewonnene Spiele am Stück. */
data class WinStreak(val player: SummaryPlayer, val length: Int)

/** Eine Ausgleichszahlung. [amount] ist der Betrag als fertiger Text ohne Vorzeichen. */
data class SummaryTransfer(val from: SummaryPlayer, val to: SummaryPlayer, val amount: String)

/**
 * Alle vorab berechneten Fakten eines Abends für die Zusammenfassung. Beträge sind bereits fertige Texte, damit
 * weder die Vorlage noch ein Sprachmodell rechnen muss.
 *
 * Wo es nichts zu berichten gibt, ist das Feld `null` oder die Liste leer. Bei Gleichstand entscheidet überall
 * die Sitzreihenfolge.
 *
 * @property date Datum des Abends im Format TT.MM.JJJJ.
 * @property location Ort des Abends; `null`, wenn keiner eingetragen ist. Er wird nie an ein Sprachmodell gesendet.
 * @property gamesPlayed Zahl der gespielten Spiele.
 * @property asMoney `true`: Es wird um Geld gespielt (Beträge in Euro). `false`: um Punkte.
 * @property players Die Spieler in Sitzreihenfolge.
 * @property standings Die Rangliste, bester Platz zuerst.
 * @property winner Der Abendsieger (Platz 1); `null`, wenn alle gleich stehen.
 * @property payer Der Zahlmeister (letzter Platz), der den Abend finanziert hat; `null`, wenn alle gleich stehen.
 * @property klopferLoser Der Lege-Pechvogel; `null`, wenn niemand nach eigenem Klopfen verloren hat.
 * @property schneiderLosers Die Geschneiderten: wer Schneider oder schwarz verloren hat, die meisten Fälle zuerst.
 * @property gameOfTheEvening Das Spiel des Abends; `null`, wenn kein Spiel gespielt wurde.
 * @property leadChanges Wie oft die Führung gewechselt hat.
 * @property bockRounds Die Bockrunden; `null`, wenn keine ausgelöst wurde.
 * @property soloKing Der Solo-König; `null`, wenn niemand mindestens zwei Einzelspiele gewonnen hat.
 * @property winStreak Die längste Siegesserie; `null`, wenn niemand mindestens drei Spiele am Stück gewonnen hat.
 * @property transfers „Wer zahlt wem“: die Zahlungen, mit denen am Ende alle ausgeglichen sind.
 */
data class SummaryFacts(
    val date: String,
    val location: String?,
    val gamesPlayed: Int,
    val asMoney: Boolean,
    val players: List<SummaryPlayer>,
    val standings: List<SummaryStanding>,
    val winner: SummaryPlace?,
    val payer: SummaryPlace?,
    val klopferLoser: KlopferLoser?,
    val schneiderLosers: List<SchneiderLoser>,
    val gameOfTheEvening: SummaryGame?,
    val leadChanges: Int,
    val bockRounds: BockRounds?,
    val soloKing: SoloKing?,
    val winStreak: WinStreak?,
    val transfers: List<SummaryTransfer>,
)

package io.github.q0ochen.schafkopf.domain.summary

/**
 * Die Zusammenfassung eines Abends ganz ohne KI: feste Sätze, leicht bairisch gefärbt, liebevoll frotzelnd.
 * Funktioniert immer, auch ohne Internet.
 *
 * Das Ergebnis ist reiner Text zum Teilen in einem Messenger: 6 bis 12 kurze Zeilen, eine je Rubrik, kein Markdown.
 * Genannt wird nur, was in den [SummaryFacts] steht; leere Rubriken entfallen. Auch ein Abend ohne ein einziges
 * Spiel bekommt eine kurze, sinnvolle Fassung.
 *
 * Je Rubrik gibt es mehrere Formulierungen. Welche drankommt, hängt nur von der Zahl der gespielten Spiele ab:
 * Dieselben Fakten ergeben immer denselben Text.
 */
object TemplateSummary {

    fun render(facts: SummaryFacts): String {
        val winner = facts.winner?.takeIf { it.players.isNotEmpty() }
        val payer = facts.payer?.takeIf { it.players.isNotEmpty() }
        val undecided = winner == null && payer == null

        val lines = ArrayList<String>()
        lines += header(facts)
        if (facts.gamesPlayed == 0) {
            lines += "Heid is koa oanzigs Spiel zammganga."
            if (facts.players.isNotEmpty()) lines += "Am Tisch: ${names(facts.players)}."
            standingsLine(facts, "Stand")?.let { lines += it }
            transfersLine(facts, undecided)?.let { lines += it }
            lines += "Nächstes Moi werd wieder gscheid kartelt!"
        } else {
            lines += gamesLine(facts)
            if (undecided) lines += "Koa Sieger, koa Zahlmeister: alle stehn gleich."
            winner?.let { lines += winnerLine(facts, it) }
            payer?.let { lines += payerLine(facts, it) }
            facts.klopferLoser?.let { lines += klopferLine(facts, it) }
            if (facts.schneiderLosers.isNotEmpty()) lines += schneiderLine(facts)
            facts.gameOfTheEvening?.let { lines += gameLine(facts, it) }
            facts.bockRounds?.let { lines += bockLine(facts, it) }
            facts.soloKing?.let { lines += soloKingLine(facts, it) }
            facts.winStreak?.let { lines += winStreakLine(facts, it) }
            standingsLine(facts, "Endstand")?.let { lines += it }
            transfersLine(facts, undecided)?.let { lines += it }
        }
        return lines.joinToString("\n")
    }

    private fun header(facts: SummaryFacts): String =
        "Schafkopf am ${facts.date}" + (facts.location?.let { ", $it" } ?: "")

    private fun gamesLine(facts: SummaryFacts): String {
        val games = if (facts.gamesPlayed == 1) "1 Spiel" else "${facts.gamesPlayed} Spiele"
        val unit = if (facts.asMoney) "Geld" else "Punkte"
        val line = pick(
            facts.gamesPlayed,
            "Gspuit: $games um $unit.",
            "Auf'm Zettel: $games, ganga is um $unit.",
            "Bilanz vom Abend: $games um $unit.",
        )
        return if (facts.leadChanges > 0) "$line Die Führung hat ${times(facts.leadChanges)} gwechselt." else line
    }

    private fun winnerLine(facts: SummaryFacts, place: SummaryPlace): String {
        val balance = facts.withUnit(place.balance)
        val afterMit = facts.withUnit(place.balance, dative = true)
        val name = names(place.players)
        if (place.players.size > 1) return "Den Sieg teilen sich $name mit je $afterMit."
        return pick(
            facts.gamesPlayed + 1,
            "Abendsieger: $name mit $afterMit. Respekt!",
            "Gwonna hat heid $name mit $afterMit.",
            "$name hat ois abgramt: $balance.",
        )
    }

    private fun payerLine(facts: SummaryFacts, place: SummaryPlace): String {
        val balance = facts.withUnit(place.balance)
        val afterMit = facts.withUnit(place.balance, dative = true)
        val name = names(place.players)
        if (place.players.size > 1) return "Finanziert ham den Abend $name mit je $afterMit. Vergelt's Gott!"
        return pick(
            facts.gamesPlayed + 2,
            "Zahlmeister: $name mit $afterMit. Vergelt's Gott fürs Finanzieren!",
            "Den Abend finanziert hat $name: $balance. Dankschön!",
            "$name hat heid die Runde gsponsert: $balance.",
        )
    }

    /**
     * Gezählt sind nur die verlorenen Spiele mit eigenem Klopfer. Deshalb heißt es immer „glegt und verlorn“:
     * Wie oft jemand insgesamt gelegt hat, steht nicht in den Fakten.
     */
    private fun klopferLine(facts: SummaryFacts, loser: KlopferLoser): String {
        val name = loser.player.name
        val amount = facts.withUnit(loser.lostAmount)
        return pick(
            facts.gamesPlayed,
            "Beim Legen verzockt: $name, ${times(loser.lostGames)} glegt und verlorn, macht $amount.",
            "Legen will glernt sei: $name hat ${times(loser.lostGames)} glegt und verlorn, des hat $amount kost.",
        )
    }

    private fun schneiderLine(facts: SummaryFacts): String {
        val losers = facts.schneiderLosers.joinToString(", ") { loser ->
            val total = loser.schneider + loser.schwarz
            when {
                loser.schwarz == 0 -> "${loser.player.name} ${times(total)}"
                loser.schneider == 0 -> "${loser.player.name} ${times(total)} schwarz"
                else -> "${loser.player.name} ${times(total)} (${times(loser.schwarz)} schwarz)"
            }
        }
        return pick(
            facts.gamesPlayed + 1,
            "Geschneidert: $losers.",
            "Im Schneider gsessn: $losers.",
        )
    }

    private fun gameLine(facts: SummaryFacts, game: SummaryGame): String {
        val declarer = game.declarer.name
        val partner = game.partner?.let { " mit ${it.name}" } ?: ""
        val specials = if (game.specials.isEmpty()) "" else " (${game.specials.joinToString(", ")})"
        val what = "${game.type} von $declarer$partner$specials"
        val outcome = if (game.won) "gwonna" else "verlorn"
        val value = facts.withUnit(game.value)
        val amount = facts.withUnit(game.declarerAmount)
        // Bei einem einzigen Spiel gibt es kein Spiel des Abends zu küren.
        if (facts.gamesPlayed == 1) return "Das oanzige Spiel: $what, $outcome – $value je Zahlung, $amount für $declarer."
        return pick(
            facts.gamesPlayed,
            "Spiel des Abends: Nr. ${game.number}, $what, $outcome: $value je Zahlung, für $declarer $amount.",
            "Der Hammer war Spiel Nr. ${game.number}: $what, $outcome – $value je Zahlung, $amount für $declarer.",
        )
    }

    private fun bockLine(facts: SummaryFacts, bock: BockRounds): String {
        val nixer = bock.nixer.joinToString(", ") { if (it.count > 1) "${it.player.name} ${times(it.count)}" else it.player.name }
        val reason = if (nixer.isEmpty()) "" else " (6 Nixer: $nixer)"
        return pick(
            facts.gamesPlayed + 1,
            "Bockrunden: ${bock.count}$reason.",
            "${times(bock.count)} Bockrunde$reason.",
        )
    }

    private fun soloKingLine(facts: SummaryFacts, king: SoloKing): String = pick(
        facts.gamesPlayed,
        "Solo-König: ${king.player.name} mit ${king.soloWins} gwonnenen Einzelspielen.",
        "Alloa am stärksten: ${king.player.name}, ${king.soloWins} Einzelspiele gwonna.",
    )

    private fun winStreakLine(facts: SummaryFacts, streak: WinStreak): String = pick(
        facts.gamesPlayed + 1,
        "Längste Siegesserie: ${streak.player.name} mit ${streak.length} Spielen am Stück.",
        "${streak.player.name} war ned zum Bremsen: ${streak.length} Siege hintereinander.",
    )

    /** Die Rangliste in einer Zeile. Stehen alle gleich, genügt der gemeinsame Stand. */
    private fun standingsLine(facts: SummaryFacts, title: String): String? {
        val first = facts.standings.firstOrNull() ?: return null
        if (facts.standings.all { it.balance == first.balance }) {
            return "$title: alle bei ${facts.withUnit(first.balance, dative = true)}."
        }
        val ranking = facts.standings.joinToString(", ") { "${it.rank}. ${it.player.name} ${facts.withUnit(it.balance)}" }
        return "$title: $ranking."
    }

    /**
     * „Wer zahlt wem“. Ohne Sieger und Zahlmeister sind alle quitt. Gibt es einen Sieger, aber keine Zahlungen
     * (die Salden gehen wegen einer fehlerhaften freien Buchung nicht auf), wird nichts behauptet.
     */
    private fun transfersLine(facts: SummaryFacts, undecided: Boolean): String? = when {
        facts.transfers.isNotEmpty() -> {
            val payments = facts.transfers.joinToString(", ") { "${it.from.name} ${facts.withUnit(it.amount)} an ${it.to.name}" }
            "Wer zahlt wem: $payments."
        }

        undecided -> "Zahln muaß koana, alle san quitt."
        else -> null
    }

    /** Wählt eine Formulierung aus. Dieselbe Zahl ergibt immer dieselbe Wahl. */
    private fun pick(seed: Int, vararg variants: String): String = variants[seed.mod(variants.size)]

    private fun times(count: Int): String = "$count-mal"

    /** „Anna“, „Anna und Bert“, „Anna, Bert und Chris“. */
    private fun names(players: List<SummaryPlayer>): String {
        val all = players.map { it.name }
        return if (all.size <= 1) all.joinToString() else all.dropLast(1).joinToString(", ") + " und " + all.last()
    }

    /**
     * Euro-Beträge tragen ihre Einheit schon im Text; Punkten wird sie angehängt.
     *
     * @param dative `true` hinter „mit“ und „bei“: Dort steht der Wemfall, also „mit +150 Punkten“.
     */
    private fun SummaryFacts.withUnit(amount: String, dative: Boolean = false): String = when {
        asMoney -> amount
        amount.trimStart('+', MINUS, '-') == "1" -> "$amount Punkt"
        dative -> "$amount Punkten"
        else -> "$amount Punkte"
    }

    /** Das echte Minuszeichen (U+2212), das die Oberfläche vor negative Beträge setzt. */
    private const val MINUS = '−'
}

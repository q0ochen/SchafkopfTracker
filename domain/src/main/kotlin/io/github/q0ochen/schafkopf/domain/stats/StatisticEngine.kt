package io.github.q0ochen.schafkopf.domain.stats

import io.github.q0ochen.schafkopf.domain.evening.Evening
import io.github.q0ochen.schafkopf.domain.evening.EveningCalculator
import io.github.q0ochen.schafkopf.domain.model.EveningId
import io.github.q0ochen.schafkopf.domain.model.PlayerId
import io.github.q0ochen.schafkopf.domain.rules.GameType
import kotlin.math.abs

/**
 * Eine Zeile der Tabelle: der Kontostand eines Spielers.
 *
 * @property balance Saldo in Cent, auch aus freien Buchungen.
 * @property rank Platz ab 1. Bei Gleichstand teilen sich Spieler einen Platz; der nächste Platz wird dann übersprungen.
 */
data class Standing(val player: PlayerId, val balance: Int, val rank: Int)

/**
 * Die Finanz-Übersicht: Kontostände und Vermögensverlauf.
 *
 * @property standings Kontostand und Platz je Spieler, in der Reihenfolge von [Statistics.players].
 * @property balanceSeries Der Vermögensverlauf je Spieler für das Liniendiagramm, in der Reihenfolge von
 *   [Statistics.players]. Jede Reihe hat `gameCount + 1` Punkte: Index 0 ist der Start bei 0, Index n der Stand in Cent
 *   nach dem n-ten abgerechneten Spiel, gezählt über alle Abende in zeitlicher Reihenfolge. Der letzte Punkt ist
 *   immer der Saldo aus [standings].
 *   Freie Buchungen zählen zum nächsten Spiel desselben Abends; stehen sie hinter dem letzten Spiel des Abends,
 *   zählen sie zu dessen Punkt. Buchungen eines Abends ohne abgerechnetes Spiel zählen zum nächsten Spiel eines
 *   späteren Abends und, wenn keines mehr folgt, zum letzten Punkt (ohne ein einziges Spiel ist das der einzige
 *   Punkt der Reihe). Wer an einem Abend nicht am Tisch saß, bleibt dort waagerecht; einzige Ausnahme ist eine
 *   solche Buchung von einem Abend ohne Spiel, die im Punkt eines anderen Abends erscheint.
 * @property eveningStarts Wo im Verlauf ein Abend beginnt: der Index des Punktes vor seinem ersten Spiel. Die Spiele
 *   des Abends sind die Punkte dahinter bis einschließlich des nächsten Eintrags dieser Liste (oder bis zum Ende).
 *   Abende ohne abgerechnetes Spiel fehlen.
 * @property eveningIds Die Abende zu [eveningStarts], in derselben (zeitlichen) Reihenfolge.
 * @property turnover Umsatz in Cent: die Summe aller positiven Beträge aller Einträge, auch der freien Buchungen.
 */
data class FinanceOverview(
    val standings: List<Standing>,
    val balanceSeries: Map<PlayerId, List<Int>>,
    val eveningStarts: List<Int>,
    val eveningIds: List<EveningId>,
    val turnover: Int,
) {
    /** Kontostand und Platz eines Spielers; `null`, wenn er an keinem der Abende am Tisch saß. */
    fun standingOf(player: PlayerId): Standing? = standings.firstOrNull { it.player == player }
}

/**
 * Bilanz eines Spielers in einer Spielart.
 *
 * @property games Abgerechnete Spiele.
 * @property won Davon auf der Gewinnerseite.
 * @property balance Summe der eigenen Beträge aus diesen Spielen in Cent.
 */
data class ModeRecord(val games: Int, val won: Int, val balance: Int) {
    /** Wie viele dieser Spiele verloren gingen. */
    val lost: Int get() = games - won

    /** Anteil der Siege zwischen 0 und 1; `null` ohne Spiel. */
    val winRate: Double? get() = if (games == 0) null else won.toDouble() / games

    /**
     * Durchschnittlicher Gewinn (positiv) oder Verlust (negativ) je Spiel in ganzen Cent, kaufmännisch gerundet
     * (ab einem halben Cent weg von der Null); `null` ohne Spiel.
     */
    val averageAmount: Int? get() = if (games == 0) null else roundedAverage(balance, games)
}

/**
 * Die Spielart-Analyse eines Spielers. Beide Aufstellungen enthalten jede Spielart, notfalls mit 0 Spielen.
 *
 * @property atTable Alle Spiele der Spielart, bei denen der Spieler am Tisch saß, egal in welcher Rolle.
 * @property asPlayer Nur selbst gespielte Spiele: als Ansager oder Mitspieler eines Rufspiels oder als Alleinspieler.
 *   Bei Über Kreuz sagt niemand an; dort ist es dasselbe wie [atTable].
 */
data class PlayerGameModes(
    val player: PlayerId,
    val atTable: Map<GameType, ModeRecord>,
    val asPlayer: Map<GameType, ModeRecord>,
)

/** Das Fazit zum Legen eines Spielers, augenzwinkernd gemeint. Die Texte dazu stehen in der Oberfläche. */
enum class GelegtBadge {
    /** Hat nie gelegt. */
    STREICHHOLZ_SPARER,

    /** Hat erst ein- oder zweimal gelegt: zu wenig für ein Urteil. */
    NOCH_OFFEN,

    /** Gewinnt mindestens 60 % der gelegten Spiele und steht damit nicht im Minus. */
    SICHERE_BANK,

    /** Gewinnt höchstens 40 % der gelegten Spiele und steht damit im Minus: Er sollte es lassen. */
    BRANDSTIFTER,

    /** Weder sichere Bank noch Brandstifter, aber unterm Strich im Plus. */
    GLUECKSRITTER,

    /** Weder sichere Bank noch Brandstifter und unterm Strich nicht im Plus. */
    WACKELKANDIDAT,
    ;

    companion object {
        /** Ab so vielen gelegten Spielen gibt es ein Urteil. */
        const val MIN_GAMES = 3

        /**
         * Das Abzeichen zu [count] gelegten Spielen, von denen [won] gewonnen wurden, mit der Netto-Bilanz
         * [netBalance] in Cent. Die Quoten werden mit ganzen Zahlen verglichen, damit die Schwellen (60 % und 40 %)
         * exakt gelten.
         */
        fun of(count: Int, won: Int, netBalance: Int): GelegtBadge = when {
            count <= 0 -> STREICHHOLZ_SPARER
            count < MIN_GAMES -> NOCH_OFFEN
            won * 10L >= count * 6L && netBalance >= 0 -> SICHERE_BANK
            won * 10L <= count * 4L && netBalance < 0 -> BRANDSTIFTER
            netBalance > 0 -> GLUECKSRITTER
            else -> WACKELKANDIDAT
        }
    }
}

/**
 * Der „Sollte-er-es-lassen?“-Index eines Spielers: was beim Legen (Klopfen) herauskommt.
 *
 * @property count In wie vielen abgerechneten Spielen der Spieler gelegt hat.
 * @property won Wie viele davon er gewonnen hat.
 * @property netBalance Netto-Bilanz in Cent: die Summe der eigenen Beträge aus den Spielen, in denen er gelegt hat.
 * @property effect Was das eigene Legen gekostet (negativ) oder gebracht hat (positiv), in Cent: je Spiel der gebuchte
 *   Betrag minus der Betrag desselben Spiels ohne den eigenen Leger, mit dem Regelwerk des Abends exakt nachgerechnet.
 */
data class GelegtRecord(
    val player: PlayerId,
    val count: Int,
    val won: Int,
    val netBalance: Int,
    val effect: Int,
) {
    /** Wie viele der gelegten Spiele verloren gingen. */
    val lost: Int get() = count - won

    /** Gewinnquote der gelegten Spiele zwischen 0 und 1; `null`, wenn der Spieler nie gelegt hat. */
    val winRate: Double? get() = if (count == 0) null else won.toDouble() / count

    /** Das Abzeichen (siehe [GelegtBadge.of]). */
    val badge: GelegtBadge get() = GelegtBadge.of(count, won, netBalance)

    /** Die Antwort auf „Sollte er es lassen?“: nur beim Brandstifter ja. */
    val shouldLeaveIt: Boolean get() = badge == GelegtBadge.BRANDSTIFTER
}

/**
 * Bilanz eines Spielers aus den Bockspielen.
 *
 * @property games Abgerechnete Bockspiele am Tisch.
 * @property won Davon auf der Gewinnerseite.
 * @property balance Summe der eigenen Beträge aus diesen Spielen in Cent.
 */
data class BockRecord(val player: PlayerId, val games: Int, val won: Int, val balance: Int) {
    /** Anteil der Siege zwischen 0 und 1; `null` ohne Bockspiel. */
    val winRate: Double? get() = if (games == 0) null else won.toDouble() / games
}

/**
 * Wer in Bockspielen am meisten gewinnt.
 *
 * @property records Bilanz je Spieler, in der Reihenfolge von [Statistics.players].
 * @property king Der Bock-König: der Spieler mit dem höchsten Saldo aus Bockspielen, aber nur, wenn dieser über 0
 *   liegt. Bei Gleichstand der, der in [Statistics.players] weiter vorn steht.
 */
data class BockOverview(val records: List<BockRecord>, val king: BockRecord?) {
    /** Die Bock-Bilanz eines Spielers; `null`, wenn er an keinem der Abende am Tisch saß. */
    fun recordOf(player: PlayerId): BockRecord? = records.firstOrNull { it.player == player }
}

/**
 * Was ein Spieler ansagt.
 *
 * @property solos Einzelspiele (Farbsolo, Wenz, Geier) als Alleinspieler.
 * @property calls Rufspiele als Ansager. Über Kreuz zählt nicht, weil dort niemand ansagt.
 */
data class SoloRecord(val player: PlayerId, val solos: Int, val calls: Int) {
    /** Alle angesagten Spiele: Einzelspiele und Rufspiele zusammen. */
    val announced: Int get() = solos + calls

    /** Anteil der Einzelspiele an den angesagten Spielen zwischen 0 und 1; `null`, wenn der Spieler nie angesagt hat. */
    val soloShare: Double? get() = if (announced == 0) null else solos.toDouble() / announced
}

/**
 * Solo-Spezialist und Mauerblümchen.
 *
 * @property records Die Ansagen je Spieler, in der Reihenfolge von [Statistics.players].
 * @property specialist Der Solo-Spezialist: wer die meisten Einzelspiele gespielt hat (mindestens eines). Bei
 *   Gleichstand der, der in [Statistics.players] weiter vorn steht.
 * @property wallflower Das Mauerblümchen, das fast nur ruft: unter den Spielern mit mindestens
 *   [StatisticEngine.MIN_ANNOUNCED_GAMES] angesagten Spielen (der Spezialist ausgenommen) der mit dem kleinsten
 *   Solo-Anteil, aber nur, wenn dieser höchstens 20 % beträgt. Bei gleichem Anteil der mit mehr angesagten Spielen,
 *   danach der, der in [Statistics.players] weiter vorn steht.
 */
data class SoloOverview(val records: List<SoloRecord>, val specialist: SoloRecord?, val wallflower: SoloRecord?) {
    /** Die Ansagen eines Spielers; `null`, wenn er an keinem der Abende am Tisch saß. */
    fun recordOf(player: PlayerId): SoloRecord? = records.firstOrNull { it.player == player }
}

/**
 * Bilanz eines Zweiergespanns aus den Spielen zu zweit gegen zwei (Rufspiel und Über Kreuz), in denen beide auf
 * derselben Seite standen, egal ob als Spieler- oder als Gegenspielerpaar. Das Gespann ist ungeordnet; [first] ist
 * der Spieler mit der kleineren Kennung.
 *
 * @property games Gemeinsame abgerechnete Spiele.
 * @property won Davon gewonnen.
 * @property balance Gemeinsamer Saldo dieser Spiele in Cent: die Summe der Beträge beider Spieler.
 */
data class PartnerPair(val first: PlayerId, val second: PlayerId, val games: Int, val won: Int, val balance: Int) {
    /** Anteil der Siege zwischen 0 und 1; `null` ohne gemeinsames Spiel. */
    val winRate: Double? get() = if (games == 0) null else won.toDouble() / games

    /** Ob dieser Eintrag zu den beiden Spielern gehört, egal in welcher Reihenfolge. */
    fun isPair(a: PlayerId, b: PlayerId): Boolean = (first == a && second == b) || (first == b && second == a)
}

/**
 * Die Zweiergespanne.
 *
 * @property pairs Alle Gespanne mit mindestens einem gemeinsamen Spiel: höchster gemeinsamer Saldo zuerst, bei
 *   Gleichstand die höhere Gewinnquote, danach nach den Kennungen.
 * @property favorite Die Lieblingspartner, das erfolgreichste Gespann: das erste Gespann in [pairs] mit mindestens
 *   [StatisticEngine.MIN_PAIR_GAMES] gemeinsamen Spielen, aber nur, wenn sein gemeinsamer Saldo über 0 liegt;
 *   sonst `null`. (Mit wechselnder Besetzung kann das einzige Gespann mit genug Spielen eines im Minus sein.)
 */
data class PartnerOverview(val pairs: List<PartnerPair>, val favorite: PartnerPair?) {
    /** Die Bilanz zweier Spieler als Gespann; `null`, wenn sie nie zusammen auf einer Seite standen. */
    fun pairOf(a: PlayerId, b: PlayerId): PartnerPair? = pairs.firstOrNull { it.isPair(a, b) }
}

/**
 * Alle Auswertungen für einen Abend oder für viele Abende („ewige Tabelle“). Beträge sind ganze Cent; Quoten sind
 * `null`, wenn es keinen einzigen Fall gab. Gezählt werden nur Spiele, die abgerechnet werden konnten.
 *
 * Ein Abend ohne abgerechnetes Spiel gilt nicht als gespielter Abend. Was dort trotzdem gebucht wurde (freie
 * Buchungen), steht aber im Kontostand, damit kein Betrag verloren geht.
 *
 * @property eveningCount Abende mit mindestens einem abgerechneten Spiel.
 * @property gameCount Abgerechnete Spiele über alle Abende.
 * @property players Alle Spieler, die an mindestens einem der Abende am Tisch saßen. Für einen einzelnen Abend
 *   ([StatisticEngine.forEvening]) in Sitzreihenfolge, sonst höchster Saldo zuerst und bei Gleichstand nach Kennung.
 *   Alle Listen „je Spieler“ haben diese Reihenfolge; nur `history.playerStats` steht immer nach Saldo.
 * @property finance Finanz-Übersicht: Kontostände und Vermögensverlauf.
 * @property gameModes Spielart-Analyse je Spieler.
 * @property gelegt „Sollte-er-es-lassen?“-Index je Spieler.
 * @property bock Bilanz der Bockspiele und der Bock-König.
 * @property solo Solo-Spezialist und Mauerblümchen.
 * @property partners Die Zweiergespanne und die Lieblingspartner.
 * @property history Die übrigen Kennzahlen (Rollen, Serien, Schneider, teuerstes Spiel und so weiter). Ihre
 *   Spielerliste steht immer nach Saldo absteigend, auch für einen einzelnen Abend.
 */
data class Statistics(
    val eveningCount: Int,
    val gameCount: Int,
    val players: List<PlayerId>,
    val finance: FinanceOverview,
    val gameModes: List<PlayerGameModes>,
    val gelegt: List<GelegtRecord>,
    val bock: BockOverview,
    val solo: SoloOverview,
    val partners: PartnerOverview,
    val history: HistoryStats,
) {
    /** Die Spielart-Analyse eines Spielers; `null`, wenn er an keinem der Abende am Tisch saß. */
    fun gameModesOf(player: PlayerId): PlayerGameModes? = gameModes.firstOrNull { it.player == player }

    /** Der „Sollte-er-es-lassen?“-Index eines Spielers; `null`, wenn er an keinem der Abende am Tisch saß. */
    fun gelegtOf(player: PlayerId): GelegtRecord? = gelegt.firstOrNull { it.player == player }
}

/**
 * Einstieg für alle Auswertungen: ein einzelner Abend oder alle Abende zusammen („ewige Tabelle“).
 *
 * Reine Funktionen: Nichts wird gespeichert, dieselbe Eingabe ergibt immer dasselbe Ergebnis. Jeder Abend wird mit
 * dem [EveningCalculator] neu durchgerechnet, mit seinem eigenen Regelwerk.
 */
object StatisticEngine {

    /** Ab so vielen angesagten Spielen kann jemand das Mauerblümchen sein. */
    const val MIN_ANNOUNCED_GAMES = 3

    /** Ab so vielen gemeinsamen Spielen kann ein Gespann zu den Lieblingspartnern werden. */
    const val MIN_PAIR_GAMES = 3

    /** Das Mauerblümchen spielt höchstens jedes fünfte seiner angesagten Spiele allein (20 %). */
    private const val WALLFLOWER_SHARE_DIVISOR = 5

    /** Die Auswertung eines einzelnen Abends. Die Spieler stehen in Sitzreihenfolge. */
    fun forEvening(evening: Evening): Statistics {
        val digest = EveningDigest(EveningCalculator.compute(evening))
        return build(listOf(digest), digest.seating)
    }

    /**
     * Die Auswertung über viele Abende. Die Reihenfolge der Liste spielt keine Rolle (ausgewertet wird zeitlich
     * geordnet, bei gleichem Beginn nach der Kennung), derselbe Abend doppelt zählt nur einmal. Jede Kennung darf
     * nur einen Abend bezeichnen; von zwei verschiedenen Fassungen unter einer Kennung zählt die zuerst übergebene.
     * Die Spieler stehen nach Saldo absteigend, bei Gleichstand nach Kennung; das gilt auch, wenn die Liste nur
     * einen Abend enthält.
     */
    fun forEvenings(evenings: List<Evening>): Statistics {
        val digests = HistoryStatistics.digests(evenings)
        val balances = LinkedHashMap<PlayerId, Int>()
        for (digest in digests) {
            for (player in digest.seating) balances[player] = (balances[player] ?: 0) + digest.balance(player)
        }
        val players = balances.keys.sortedWith(compareByDescending<PlayerId> { balances.getValue(it) }.thenBy { it.value })
        return build(digests, players)
    }

    /**
     * @param digests Die durchgerechneten Abende in zeitlicher Reihenfolge, jeder genau einmal.
     * @param players Alle Spieler dieser Abende in der Reihenfolge, in der sie erscheinen sollen.
     */
    private fun build(digests: List<EveningDigest>, players: List<PlayerId>): Statistics {
        // Je Spieler die Spiele der Abende, an denen er am Tisch saß.
        val gamesOf = players.associateWith { player -> digests.filter { player in it.seating }.flatMap { it.games } }
        return Statistics(
            eveningCount = digests.count { it.games.isNotEmpty() },
            gameCount = digests.sumOf { it.games.size },
            players = players,
            finance = finance(digests, players),
            gameModes = players.map { gameModes(it, gamesOf.getValue(it)) },
            gelegt = players.map { gelegt(it, gamesOf.getValue(it)) },
            bock = bock(players.map { bockRecord(it, gamesOf.getValue(it)) }),
            solo = solo(players.map { soloRecord(it, gamesOf.getValue(it)) }),
            partners = partners(digests),
            history = HistoryStatistics.fromDigests(digests),
        )
    }

    private fun finance(digests: List<EveningDigest>, players: List<PlayerId>): FinanceOverview {
        val current = LinkedHashMap<PlayerId, Int>()
        for (player in players) current[player] = 0
        val points = ArrayList<Map<PlayerId, Int>>()
        points += current.toMap()
        val eveningStarts = ArrayList<Int>()
        val eveningIds = ArrayList<EveningId>()

        for (digest in digests) {
            val hasGames = digest.games.isNotEmpty()
            if (hasGames) {
                eveningStarts += points.lastIndex
                eveningIds += digest.eveningId
            }
            for (result in digest.state.results) {
                for ((player, amount) in result.amounts) current[player] = (current[player] ?: 0) + amount
                if (result.isPlayedGame()) points += current.toMap()
            }
            // Freie Buchungen hinter dem letzten Spiel des Abends zählen zu dessen Punkt.
            if (hasGames) points[points.lastIndex] = current.toMap()
        }
        // Buchungen von Abenden ohne Spiel, auf die kein Spiel mehr folgt: Der letzte Punkt ist immer der Saldo.
        points[points.lastIndex] = current.toMap()

        return FinanceOverview(
            standings = players.map { player ->
                val balance = current.getValue(player)
                Standing(player, balance, rank = 1 + current.values.count { it > balance })
            },
            balanceSeries = players.associateWith { player -> points.map { it.getValue(player) } },
            eveningStarts = eveningStarts,
            eveningIds = eveningIds,
            turnover = digests.sumOf { it.turnover },
        )
    }

    private fun gameModes(player: PlayerId, games: List<PlayedGame>): PlayerGameModes {
        val atTable = GameType.entries.associateWith { ModeTally() }
        val asPlayer = GameType.entries.associateWith { ModeTally() }
        for (game in games) {
            atTable.getValue(game.entry.type).add(game, player)
            if (game.playedBy(player)) asPlayer.getValue(game.entry.type).add(game, player)
        }
        return PlayerGameModes(
            player = player,
            atTable = atTable.mapValues { it.value.toRecord() },
            asPlayer = asPlayer.mapValues { it.value.toRecord() },
        )
    }

    private class ModeTally {
        private var games = 0
        private var won = 0
        private var balance = 0

        fun add(game: PlayedGame, player: PlayerId) {
            games += 1
            if (game.won(player)) won += 1
            balance += game.amount(player)
        }

        fun toRecord(): ModeRecord = ModeRecord(games, won, balance)
    }

    private fun gelegt(player: PlayerId, games: List<PlayedGame>): GelegtRecord {
        val own = games.filter { player in it.entry.klopfer }
        return GelegtRecord(
            player = player,
            count = own.size,
            won = own.count { it.won(player) },
            netBalance = own.sumOf { it.amount(player) },
            effect = own.sumOf { it.klopferEffect(player) },
        )
    }

    private fun bockRecord(player: PlayerId, games: List<PlayedGame>): BockRecord {
        val bockGames = games.filter { it.isBockGame }
        return BockRecord(
            player = player,
            games = bockGames.size,
            won = bockGames.count { it.won(player) },
            balance = bockGames.sumOf { it.amount(player) },
        )
    }

    // `maxByOrNull` liefert bei Gleichstand den ersten Treffer, also den, der in der Spielerliste weiter vorn steht.
    private fun bock(records: List<BockRecord>): BockOverview =
        BockOverview(records, king = records.filter { it.balance > 0 }.maxByOrNull { it.balance })

    private fun soloRecord(player: PlayerId, games: List<PlayedGame>): SoloRecord {
        val announced = games.filter { it.entry.type.hasDeclarer && it.entry.declarer == player }
        return SoloRecord(
            player = player,
            solos = announced.count { it.entry.type.isSolo },
            calls = announced.count { !it.entry.type.isSolo },
        )
    }

    private fun solo(records: List<SoloRecord>): SoloOverview {
        val specialist = records.filter { it.solos > 0 }.maxByOrNull { it.solos }
        val wallflower = records
            .filter { it.announced >= MIN_ANNOUNCED_GAMES && it.player != specialist?.player }
            .minWithOrNull(BY_SOLO_SHARE)
            ?.takeIf { it.solos * WALLFLOWER_SHARE_DIVISOR <= it.announced }
        return SoloOverview(records, specialist, wallflower)
    }

    /**
     * Kleinster Solo-Anteil zuerst, bei gleichem Anteil der mit mehr angesagten Spielen. Die Anteile werden als
     * Brüche über Kreuz verglichen, also ohne Rundung. `minWithOrNull` liefert bei Gleichstand den ersten Treffer.
     */
    private val BY_SOLO_SHARE: Comparator<SoloRecord> =
        Comparator<SoloRecord> { a, b -> (a.solos.toLong() * b.announced).compareTo(b.solos.toLong() * a.announced) }
            .thenByDescending { it.announced }

    private fun partners(digests: List<EveningDigest>): PartnerOverview {
        val tallies = LinkedHashMap<Pair<PlayerId, PlayerId>, PairTally>()
        for (digest in digests) {
            for (game in digest.games) {
                if (game.entry.type.isSolo) continue
                val (declarers, opponents) = digest.seating.partition { it in game.declarerSide }
                if (declarers.size != 2 || opponents.size != 2) continue
                for (side in listOf(declarers, opponents)) {
                    val (first, second) = side.sortedBy { it.value }
                    tallies.getOrPut(first to second) { PairTally() }.add(game, first, second)
                }
            }
        }
        val pairs = tallies
            .map { (pair, tally) -> PartnerPair(pair.first, pair.second, tally.games, tally.won, tally.balance) }
            .sortedWith(PAIR_ORDER)
        // Die Liste steht nach Saldo: Ist das erste Gespann mit genug Spielen nicht im Plus, ist es keines.
        return PartnerOverview(pairs, favorite = pairs.firstOrNull { it.games >= MIN_PAIR_GAMES }?.takeIf { it.balance > 0 })
    }

    private class PairTally {
        var games = 0
            private set
        var won = 0
            private set
        var balance = 0
            private set

        fun add(game: PlayedGame, first: PlayerId, second: PlayerId) {
            games += 1
            if (game.won(first)) won += 1
            balance += game.amount(first) + game.amount(second)
        }
    }

    /**
     * Höchster gemeinsamer Saldo zuerst, dann die höhere Gewinnquote (als Brüche über Kreuz verglichen, also ohne
     * Rundung), dann nach den Kennungen. Jedes Gespann hat mindestens ein Spiel.
     */
    private val PAIR_ORDER: Comparator<PartnerPair> =
        compareByDescending<PartnerPair> { it.balance }
            .thenComparator { a, b -> (b.won.toLong() * a.games).compareTo(a.won.toLong() * b.games) }
            .thenBy { it.first.value }
            .thenBy { it.second.value }
}

/** Teilt [sum] durch [count] und rundet kaufmännisch: ab der Hälfte weg von der Null. [count] ist größer als 0. */
private fun roundedAverage(sum: Int, count: Int): Int {
    val rounded = (2L * abs(sum.toLong()) + count) / (2L * count)
    return if (sum < 0) (-rounded).toInt() else rounded.toInt()
}

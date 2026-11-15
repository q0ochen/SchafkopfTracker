package io.github.q0ochen.schafkopf.domain.stats

import io.github.q0ochen.schafkopf.domain.evening.BockReason
import io.github.q0ochen.schafkopf.domain.evening.BockTriggerEntry
import io.github.q0ochen.schafkopf.domain.evening.EveningState
import io.github.q0ochen.schafkopf.domain.evening.GameEntry
import io.github.q0ochen.schafkopf.domain.evening.ThrownInEntry
import io.github.q0ochen.schafkopf.domain.model.EveningId
import io.github.q0ochen.schafkopf.domain.model.PlayerId
import io.github.q0ochen.schafkopf.domain.rules.Announcement
import io.github.q0ochen.schafkopf.domain.rules.GameType
import io.github.q0ochen.schafkopf.domain.rules.KontraLevel
import io.github.q0ochen.schafkopf.domain.rules.ResultLevel
import io.github.q0ochen.schafkopf.domain.rules.ValueBreakdown

/*
 * Gemeinsames Rechenwerk für die Statistik eines Abends, die Statistik über viele Abende und die Zusammenfassung.
 * Alles hier ist reine Rechnung ohne Zustand nach außen; gespeichert wird nichts.
 */

/**
 * Ein gespieltes Spiel, das abgerechnet werden konnte, so aufbereitet, wie die Statistik es braucht.
 *
 * @property value Der Spielwert: so viel zahlt ein Verlierer an einen Gewinner.
 */
internal class PlayedGame(
    val eveningId: EveningId,
    val entry: GameEntry,
    val gameNumber: Int,
    val bockExponent: Int,
    val breakdown: ValueBreakdown,
    private val amounts: Map<PlayerId, Int>,
) {
    val value: Int get() = breakdown.value

    /** Der Ansager und, beim Rufspiel, sein Mitspieler. */
    val declarerSide: Set<PlayerId> = setOfNotNull(entry.declarer, entry.partner.takeIf { !entry.type.isSolo })

    /** Ob der Spieler auf der Gewinnerseite stand. */
    fun won(player: PlayerId): Boolean = (player in declarerSide) == entry.declarerWon

    /** Betrag des Spielers aus diesem Spiel in Cent. */
    fun amount(player: PlayerId): Int = amounts[player] ?: 0
}

/**
 * Ein durchgerechneter Abend, einmal für die Statistik vorsortiert.
 *
 * Als gespielte Spiele zählen nur Spiele, die abgerechnet werden konnten. Ein Spiel mit unbekanntem Ansager oder
 * fehlendem Mitspieler bekommt zwar eine Spielnummer, bucht aber nichts und bleibt in der Statistik außen vor.
 */
internal class EveningDigest(val state: EveningState) {
    val eveningId: EveningId = state.evening.id

    /** Die Spieler in Sitzreihenfolge. */
    val seating: List<PlayerId> = state.evening.seating.distinct()

    val games: List<PlayedGame> = state.results.mapNotNull { result ->
        val entry = result.entry as? GameEntry ?: return@mapNotNull null
        val gameNumber = result.gameNumber ?: return@mapNotNull null
        val breakdown = result.breakdown ?: return@mapNotNull null
        PlayedGame(eveningId, entry, gameNumber, result.bockExponent, breakdown, result.amounts)
    }

    val bockTriggers: List<BockTriggerEntry> = state.evening.entries.filterIsInstance<BockTriggerEntry>()

    val thrownInCount: Int = state.evening.entries.count { it is ThrownInEntry }

    /** Summe aller positiven Beträge aller Einträge, auch der freien Buchungen. */
    val turnover: Int = state.results.sumOf { result -> result.amounts.values.filter { it > 0 }.sum() }

    val leadChanges: Int = countLeadChanges(Progression.balancePoints(state))

    val highestBalance: Int = state.balances.values.maxOrNull() ?: 0

    val lowestBalance: Int = state.balances.values.minOrNull() ?: 0

    /** Stehen am Ende alle gleich, gibt es weder einen Sieger noch einen Letzten. */
    val allEqual: Boolean = highestBalance == lowestBalance

    fun balance(player: PlayerId): Int = state.balances[player] ?: 0
}

/**
 * Zählt, wie oft die Führung gewechselt hat: wie oft ein anderer Spieler alleiniger Erster ist als der letzte
 * alleinige Erste davor. Der erste alleinige Erste des Abends ist noch kein Wechsel.
 */
private fun countLeadChanges(points: List<Map<PlayerId, Int>>): Int {
    var changes = 0
    var lastLeader: PlayerId? = null
    for (point in points) {
        val best = point.values.maxOrNull() ?: continue
        val leader = point.filterValues { it == best }.keys.singleOrNull() ?: continue
        if (lastLeader != null && leader != lastLeader) changes += 1
        lastLeader = leader
    }
    return changes
}

/** Zählt Fälle und wie viele davon gut ausgingen. */
internal class Tally {
    private var played = 0
    private var won = 0

    fun add(success: Boolean) {
        played += 1
        if (success) won += 1
    }

    fun toRecord(): RoleRecord = RoleRecord(played, won)
}

/** Sammelt die Kennzahlen eines Spielers über beliebig viele Abende, an denen er am Tisch saß. */
internal class PlayerAccumulator(private val player: PlayerId) {
    private var evenings = 0
    private var games = 0
    private var balance = 0
    private var gamesWon = 0
    private val asDeclarer = Tally()
    private val asPartner = Tally()
    private val asOpponent = Tally()
    private val declarerByType: Map<GameType, Tally> = GameType.entries.associateWith { Tally() }
    private val klopferGiven = Tally()
    private var klopferEffect = 0
    private val kontraGiven = Tally()
    private val retourGiven = Tally()
    private var schneiderWon = 0
    private var schneiderLost = 0
    private var schwarzWon = 0
    private var schwarzLost = 0
    private val toutPlayed = Tally()
    private var biggestWin: GameAmount? = null
    private var biggestLoss: GameAmount? = null
    private var longestWinStreak = 0
    private var longestLoseStreak = 0
    private var nixerTriggers = 0
    private var eveningWins = 0
    private var eveningLastPlaces = 0
    private var bestEvening: EveningBalance? = null
    private var worstEvening: EveningBalance? = null

    /** Nimmt einen Abend auf. Die Abende müssen in zeitlicher Reihenfolge kommen. */
    fun add(digest: EveningDigest) {
        val eveningBalance = digest.balance(player)
        balance += eveningBalance
        // Nur Bockrunden wegen 6 Nixern zählen; bei einer von Hand ausgelösten gibt es keinen Nixer-Spieler.
        nixerTriggers += digest.bockTriggers.count { it.reason == BockReason.SECHS_NIXER && it.player == player }
        if (digest.games.isEmpty()) return

        evenings += 1
        if (!digest.allEqual) {
            if (eveningBalance == digest.highestBalance) eveningWins += 1
            if (eveningBalance == digest.lowestBalance) eveningLastPlaces += 1
        }
        val best = bestEvening
        if (best == null || eveningBalance > best.balance) bestEvening = EveningBalance(digest.eveningId, eveningBalance)
        val worst = worstEvening
        if (worst == null || eveningBalance < worst.balance) worstEvening = EveningBalance(digest.eveningId, eveningBalance)

        var winRun = 0
        var loseRun = 0
        for (game in digest.games) {
            val won = game.won(player)
            addGame(game, won)
            if (won) {
                winRun += 1
                loseRun = 0
            } else {
                loseRun += 1
                winRun = 0
            }
            longestWinStreak = maxOf(longestWinStreak, winRun)
            longestLoseStreak = maxOf(longestLoseStreak, loseRun)
        }
    }

    private fun addGame(game: PlayedGame, won: Boolean) {
        val entry = game.entry
        val amount = game.amount(player)
        games += 1
        if (won) gamesWon += 1

        when {
            entry.declarer == player -> {
                asDeclarer.add(won)
                declarerByType.getValue(entry.type).add(won)
                if (entry.announcement != Announcement.NONE) toutPlayed.add(won)
            }

            !entry.type.isSolo && entry.partner == player -> asPartner.add(won)
            else -> asOpponent.add(won)
        }

        if (player in entry.klopfer) {
            klopferGiven.add(won)
            // Jeder Klopfer verdoppelt: Ohne den eigenen Klopfer wäre der Betrag halb so groß gewesen.
            klopferEffect += amount / 2
        }
        if (entry.kontra != KontraLevel.NONE && entry.kontraBy == player) kontraGiven.add(won)
        if (entry.kontra == KontraLevel.RETOUR && entry.retourBy == player) retourGiven.add(won)

        // Bei Tout und Sie gibt es keinen Zuschlag für Schneider oder Schwarz, also zählt die Stufe dort nicht.
        if (entry.announcement == Announcement.NONE) {
            when (entry.level) {
                ResultLevel.NORMAL -> Unit
                ResultLevel.SCHNEIDER -> if (won) schneiderWon += 1 else schneiderLost += 1
                ResultLevel.SCHWARZ -> if (won) schwarzWon += 1 else schwarzLost += 1
            }
        }

        val highest = biggestWin
        if (amount > 0 && (highest == null || amount > highest.amount)) {
            biggestWin = GameAmount(game.eveningId, entry.id, game.gameNumber, amount)
        }
        val lowest = biggestLoss
        if (amount < 0 && (lowest == null || amount < lowest.amount)) {
            biggestLoss = GameAmount(game.eveningId, entry.id, game.gameNumber, amount)
        }
    }

    fun build(): PlayerStats = PlayerStats(
        player = player,
        evenings = evenings,
        games = games,
        balance = balance,
        gamesWon = gamesWon,
        asDeclarer = asDeclarer.toRecord(),
        asPartner = asPartner.toRecord(),
        asOpponent = asOpponent.toRecord(),
        declarerByType = declarerByType.mapValues { it.value.toRecord() },
        klopferGiven = klopferGiven.toRecord(),
        klopferEffect = klopferEffect,
        kontraGiven = kontraGiven.toRecord(),
        retourGiven = retourGiven.toRecord(),
        schneiderWon = schneiderWon,
        schneiderLost = schneiderLost,
        schwarzWon = schwarzWon,
        schwarzLost = schwarzLost,
        toutPlayed = toutPlayed.toRecord(),
        biggestWin = biggestWin,
        biggestLoss = biggestLoss,
        longestWinStreak = longestWinStreak,
        longestLoseStreak = longestLoseStreak,
        nixerTriggers = nixerTriggers,
        eveningWins = eveningWins,
        eveningLastPlaces = eveningLastPlaces,
        bestEvening = bestEvening,
        worstEvening = worstEvening,
    )
}

/** Sammelt die Kennzahlen, die den ganzen Tisch betreffen, über beliebig viele Abende. */
internal class TableAccumulator {
    private val typeCounts = LinkedHashMap<GameType, Int>().apply { GameType.entries.forEach { put(it, 0) } }

    var gameCount = 0
        private set
    var bockGamesCount = 0
        private set
    var bockTriggerCount = 0
        private set
    var thrownInCount = 0
        private set
    var leadChanges = 0
        private set
    var turnover = 0
        private set
    var mostExpensiveGame: ExpensiveGame? = null
        private set

    val gameTypeCounts: Map<GameType, Int> get() = typeCounts.toMap()

    /** Nimmt einen Abend auf. Die Abende müssen in zeitlicher Reihenfolge kommen. */
    fun add(digest: EveningDigest) {
        for (game in digest.games) {
            gameCount += 1
            typeCounts[game.entry.type] = typeCounts.getValue(game.entry.type) + 1
            if (game.bockExponent > 0) bockGamesCount += 1
            // Nur ein echt höherer Wert verdrängt: Bei Gleichstand bleibt das frühere Spiel.
            val top = mostExpensiveGame
            if (top == null || game.value > top.value) {
                mostExpensiveGame = ExpensiveGame(game.eveningId, game.entry.id, game.gameNumber, game.value)
            }
        }
        bockTriggerCount += digest.bockTriggers.size
        thrownInCount += digest.thrownInCount
        leadChanges += digest.leadChanges
        turnover += digest.turnover
    }
}

package io.github.q0ochen.schafkopf.domain.golden

import io.github.q0ochen.schafkopf.domain.evening.BockTriggerEntry
import io.github.q0ochen.schafkopf.domain.evening.EntryResult
import io.github.q0ochen.schafkopf.domain.evening.Evening
import io.github.q0ochen.schafkopf.domain.evening.EveningEntry
import io.github.q0ochen.schafkopf.domain.evening.EveningState
import io.github.q0ochen.schafkopf.domain.evening.GameEntry
import io.github.q0ochen.schafkopf.domain.evening.ManualEntry
import io.github.q0ochen.schafkopf.domain.evening.ThrownInEntry
import io.github.q0ochen.schafkopf.domain.evening.Transfer
import io.github.q0ochen.schafkopf.domain.model.PlayerId
import io.github.q0ochen.schafkopf.domain.rules.GameFacts
import io.github.q0ochen.schafkopf.domain.rules.GameType
import io.github.q0ochen.schafkopf.domain.rules.RuleSet
import io.github.q0ochen.schafkopf.domain.rules.ValueBreakdown
import io.github.q0ochen.schafkopf.domain.stats.BockOverview
import io.github.q0ochen.schafkopf.domain.stats.BockRecord
import io.github.q0ochen.schafkopf.domain.stats.EveningBalance
import io.github.q0ochen.schafkopf.domain.stats.EveningStats
import io.github.q0ochen.schafkopf.domain.stats.ExpensiveGame
import io.github.q0ochen.schafkopf.domain.stats.FinanceOverview
import io.github.q0ochen.schafkopf.domain.stats.GameAmount
import io.github.q0ochen.schafkopf.domain.stats.GelegtRecord
import io.github.q0ochen.schafkopf.domain.stats.HistoryStats
import io.github.q0ochen.schafkopf.domain.stats.ModeRecord
import io.github.q0ochen.schafkopf.domain.stats.PairRecord
import io.github.q0ochen.schafkopf.domain.stats.PartnerOverview
import io.github.q0ochen.schafkopf.domain.stats.PartnerPair
import io.github.q0ochen.schafkopf.domain.stats.PartnerRecord
import io.github.q0ochen.schafkopf.domain.stats.PlayerGameModes
import io.github.q0ochen.schafkopf.domain.stats.PlayerStats
import io.github.q0ochen.schafkopf.domain.stats.RoleRecord
import io.github.q0ochen.schafkopf.domain.stats.SoloOverview
import io.github.q0ochen.schafkopf.domain.stats.SoloRecord
import io.github.q0ochen.schafkopf.domain.stats.Standing
import io.github.q0ochen.schafkopf.domain.stats.Statistics
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Schreibt Ein- und Ausgaben des Kerns als JSON für die Golden-Daten der Web-App.
 *
 * - Eingaben passen 1:1 auf `web/src/core/model.ts`: dieselben Feldnamen, Aufzählungen mit dem Namen des Kotlin-Enums,
 *   Zeitpunkte als `Instant.toString()`, jede Map (auch die Tabellen je Spielart im Regelwerk und die Beträge einer
 *   freien Buchung) als Liste von `[Schlüssel, Wert]`-Paaren in Kotlin-Reihenfolge, Mengen als Listen.
 * - Ausgaben tragen die Namen der Kotlin-Eigenschaften, auch die abgeleiteten (`lost`, `winRate`, `badge` …). Maps
 *   stehen als Paarlisten in Kotlin-Reihenfolge da, damit die Reihenfolge mitgeprüft wird; `null` bleibt `null`, Quoten
 *   stehen mit voller Genauigkeit da (`Double.toString`, beim Einlesen bitgleich).
 *
 * `web/src/core/golden/goldenCodec.ts` bildet dieselbe Form aus den Ergebnissen des TypeScript-Ports.
 */
internal object GoldenJson {

    // ---------------------------------------------------------------- Bausteine

    private fun text(value: String): JsonElement = JsonPrimitive(value)

    private fun number(value: Int): JsonElement = JsonPrimitive(value)

    private fun numberOrNull(value: Int?): JsonElement = if (value == null) JsonNull else JsonPrimitive(value)

    private fun rate(value: Double?): JsonElement = if (value == null) JsonNull else JsonPrimitive(value)

    fun player(id: PlayerId?): JsonElement = if (id == null) JsonNull else JsonPrimitive(id.value)

    fun players(ids: Iterable<PlayerId>): JsonArray = JsonArray(ids.map { JsonPrimitive(it.value) })

    fun numbers(values: Iterable<Int>): JsonArray = JsonArray(values.map { JsonPrimitive(it) })

    fun names(values: Iterable<Enum<*>>): JsonArray = JsonArray(values.map { JsonPrimitive(it.name) })

    private fun pair(key: String, value: JsonElement): JsonArray = JsonArray(listOf(JsonPrimitive(key), value))

    /** Eine Map mit Spielern als Schlüssel als Paarliste in Kotlin-Reihenfolge. */
    fun <V> byPlayer(map: Map<PlayerId, V>, value: (V) -> JsonElement): JsonArray =
        JsonArray(map.map { (player, entry) -> pair(player.value, value(entry)) })

    fun amounts(map: Map<PlayerId, Int>): JsonArray = byPlayer(map, ::number)

    /** Eine Map mit Spielarten als Schlüssel als Paarliste in Kotlin-Reihenfolge. */
    private fun <V> byType(map: Map<GameType, V>, value: (V) -> JsonElement): JsonArray =
        JsonArray(map.map { (type, entry) -> pair(type.name, value(entry)) })

    private fun JsonObjectBuilder.put(key: String, value: Enum<*>) = put(key, value.name)

    // ---------------------------------------------------------------- Eingaben (wie web/src/core/model.ts)

    fun ruleSet(rules: RuleSet): JsonObject = buildJsonObject {
        put("name", rules.name)
        put("unit", rules.unit)
        put("prices", byType(rules.prices, ::number))
        put("enabledTypes", names(rules.enabledTypes))
        put("laufendeMin", byType(rules.laufendeMin, ::number))
        put("laufendeMax", byType(rules.laufendeMax, ::number))
        put("klopfenEnabled", rules.klopfenEnabled)
        put("kontraEnabled", rules.kontraEnabled)
        put("retourEnabled", rules.retourEnabled)
        put("toutFactor", rules.toutFactor)
        put("sieFactor", rules.sieFactor)
        put("sieLaufende", rules.sieLaufende)
        put(
            "bock",
            buildJsonObject {
                put("enabled", rules.bock.enabled)
                put("gamesPerTrigger", rules.bock.gamesPerTrigger)
                put("factor", rules.bock.factor)
                put("stacking", rules.bock.stacking)
                put("thrownInConsumesBockGame", rules.bock.thrownInConsumesBockGame)
            },
        )
        put("dealerAdvancesAfterThrowIn", rules.dealerAdvancesAfterThrowIn)
        put("displayAsMoney", rules.displayAsMoney)
        put("payoutRule", rules.payoutRule)
    }

    fun entry(entry: EveningEntry): JsonObject = when (entry) {
        is GameEntry -> buildJsonObject {
            put("kind", "game")
            put("id", entry.id.value)
            put("recordedAt", entry.recordedAt.toString())
            put("type", entry.type)
            put("suit", entry.suit?.name?.let(::text) ?: JsonNull)
            put("declarer", entry.declarer.value)
            put("partner", player(entry.partner))
            put("declarerWon", entry.declarerWon)
            put("level", entry.level)
            put("laufende", entry.laufende)
            put("announcement", entry.announcement)
            put("klopfer", players(entry.klopfer))
            put("kontra", entry.kontra)
            put("kontraBy", player(entry.kontraBy))
            put("retourBy", player(entry.retourBy))
            put("bockOverride", entry.bockOverride)
            put("declarerEyes", numberOrNull(entry.declarerEyes))
            put("note", entry.note)
        }

        is ThrownInEntry -> buildJsonObject {
            put("kind", "thrown_in")
            put("id", entry.id.value)
            put("recordedAt", entry.recordedAt.toString())
        }

        is BockTriggerEntry -> buildJsonObject {
            put("kind", "bock")
            put("id", entry.id.value)
            put("recordedAt", entry.recordedAt.toString())
            put("reason", entry.reason)
            put("player", player(entry.player))
            put("handThrownIn", entry.handThrownIn)
        }

        is ManualEntry -> buildJsonObject {
            put("kind", "manual")
            put("id", entry.id.value)
            put("recordedAt", entry.recordedAt.toString())
            put("amounts", amounts(entry.amounts))
            put("note", entry.note)
        }
    }

    fun evening(evening: Evening): JsonObject = buildJsonObject {
        put("id", evening.id.value)
        put("startedAt", evening.startedAt.toString())
        put("finishedAt", evening.finishedAt?.toString()?.let(::text) ?: JsonNull)
        put("location", evening.location)
        put("seating", players(evening.seating))
        put("firstDealer", evening.firstDealer.value)
        put("rules", ruleSet(evening.rules))
        put("entries", JsonArray(evening.entries.map(::entry)))
    }

    fun facts(facts: GameFacts): JsonObject = buildJsonObject {
        put("type", facts.type)
        put("players", players(facts.players))
        put("declarer", facts.declarer.value)
        put("partner", player(facts.partner))
        put("declarerWon", facts.declarerWon)
        put("level", facts.level)
        put("laufende", facts.laufende)
        put("announcement", facts.announcement)
        put("klopferCount", facts.klopferCount)
        put("kontra", facts.kontra)
        put("bockExponent", facts.bockExponent)
    }

    // ---------------------------------------------------------------- Ausgaben: Abrechnung und Abend

    fun breakdown(breakdown: ValueBreakdown?): JsonElement = if (breakdown == null) {
        JsonNull
    } else {
        buildJsonObject {
            put("basePrice", breakdown.basePrice)
            put("laufendeCounted", breakdown.laufendeCounted)
            put("laufendeAmount", breakdown.laufendeAmount)
            put("levelAmount", breakdown.levelAmount)
            put("sum", breakdown.sum)
            put("announcementFactor", breakdown.announcementFactor)
            put("klopferFactor", breakdown.klopferFactor)
            put("kontraFactor", breakdown.kontraFactor)
            put("bockFactor", breakdown.bockFactor)
            put("value", breakdown.value)
            put("totalFactor", breakdown.totalFactor)
        }
    }

    /**
     * Ein Ergebnis von `EveningCalculator.compute`. Statt des Eintrags selbst steht nur seine Kennung da (es ist
     * derselbe Eintrag wie in der Eingabe). [violations] ist `ScoreCalculator.validate` für das Spiel, sonst `null`.
     */
    fun entryResult(result: EntryResult, violations: List<Enum<*>>?): JsonObject = buildJsonObject {
        put("entryId", result.entry.id.value)
        put("gameNumber", numberOrNull(result.gameNumber))
        put("dealer", result.dealer.value)
        put("bockExponent", result.bockExponent)
        put("breakdown", breakdown(result.breakdown))
        put("amounts", amounts(result.amounts))
        put("balances", amounts(result.balances))
        put("ranks", amounts(result.ranks))
        put("issues", names(result.issues))
        put("violations", if (violations == null) JsonNull else names(violations))
    }

    /** Ein `EveningState` ohne den Abend selbst (das ist die Eingabe). */
    fun state(state: EveningState, results: List<JsonObject>): JsonObject = buildJsonObject {
        put("results", JsonArray(results))
        put("balances", amounts(state.balances))
        put("ranks", amounts(state.ranks))
        put("nextDealer", state.nextDealer.value)
        put("nextBockExponent", state.nextBockExponent)
        put("bockGamesRemaining", state.bockGamesRemaining)
        put("gamesPlayed", state.gamesPlayed)
    }

    fun transfers(transfers: List<Transfer>): JsonArray = JsonArray(
        transfers.map { transfer ->
            buildJsonObject {
                put("from", transfer.from.value)
                put("to", transfer.to.value)
                put("amount", transfer.amount)
            }
        },
    )

    // ---------------------------------------------------------------- Ausgaben: Statistik

    private fun roleRecord(record: RoleRecord): JsonObject = buildJsonObject {
        put("played", record.played)
        put("won", record.won)
        put("lost", record.lost)
        put("rate", rate(record.rate))
    }

    private fun gameAmount(amount: GameAmount?): JsonElement = if (amount == null) {
        JsonNull
    } else {
        buildJsonObject {
            put("eveningId", amount.eveningId.value)
            put("entryId", amount.entryId.value)
            put("gameNumber", amount.gameNumber)
            put("amount", amount.amount)
        }
    }

    private fun eveningBalance(balance: EveningBalance?): JsonElement = if (balance == null) {
        JsonNull
    } else {
        buildJsonObject {
            put("eveningId", balance.eveningId.value)
            put("balance", balance.balance)
        }
    }

    private fun playerStats(stats: PlayerStats): JsonObject = buildJsonObject {
        put("player", stats.player.value)
        put("evenings", stats.evenings)
        put("games", stats.games)
        put("balance", stats.balance)
        put("gamesWon", stats.gamesWon)
        put("asDeclarer", roleRecord(stats.asDeclarer))
        put("asPartner", roleRecord(stats.asPartner))
        put("asOpponent", roleRecord(stats.asOpponent))
        put("declarerByType", byType(stats.declarerByType, ::roleRecord))
        put("klopferGiven", roleRecord(stats.klopferGiven))
        put("klopferEffect", stats.klopferEffect)
        put("kontraGiven", roleRecord(stats.kontraGiven))
        put("retourGiven", roleRecord(stats.retourGiven))
        put("schneiderWon", stats.schneiderWon)
        put("schneiderLost", stats.schneiderLost)
        put("schwarzWon", stats.schwarzWon)
        put("schwarzLost", stats.schwarzLost)
        put("toutPlayed", roleRecord(stats.toutPlayed))
        put("biggestWin", gameAmount(stats.biggestWin))
        put("biggestLoss", gameAmount(stats.biggestLoss))
        put("longestWinStreak", stats.longestWinStreak)
        put("longestLoseStreak", stats.longestLoseStreak)
        put("nixerTriggers", stats.nixerTriggers)
        put("eveningWins", stats.eveningWins)
        put("eveningLastPlaces", stats.eveningLastPlaces)
        put("bestEvening", eveningBalance(stats.bestEvening))
        put("worstEvening", eveningBalance(stats.worstEvening))
        put("gamesLost", stats.gamesLost)
        put("overall", roleRecord(stats.overall))
        put("klopferCount", stats.klopferCount)
        put("klopferLost", stats.klopferLost)
    }

    private fun expensiveGame(game: ExpensiveGame?): JsonElement = if (game == null) {
        JsonNull
    } else {
        buildJsonObject {
            put("eveningId", game.eveningId.value)
            put("entryId", game.entryId.value)
            put("gameNumber", game.gameNumber)
            put("value", game.value)
        }
    }

    fun eveningStats(stats: EveningStats): JsonObject = buildJsonObject {
        put("eveningId", stats.eveningId.value)
        put("playerStats", JsonArray(stats.playerStats.map(::playerStats)))
        put("gamesPlayed", stats.gamesPlayed)
        put("gameTypeCounts", byType(stats.gameTypeCounts, ::number))
        put("bockGamesCount", stats.bockGamesCount)
        put("bockTriggerCount", stats.bockTriggerCount)
        put("thrownInCount", stats.thrownInCount)
        put("leadChanges", stats.leadChanges)
        put("mostExpensiveGame", expensiveGame(stats.mostExpensiveGame))
        put("turnover", stats.turnover)
    }

    private fun pairRecord(record: PairRecord): JsonObject = buildJsonObject {
        put("played", record.played)
        put("won", record.won)
        put("balance", record.balance)
        put("lost", record.lost)
        put("rate", rate(record.rate))
    }

    private fun partnerRecord(record: PartnerRecord): JsonObject = buildJsonObject {
        put("first", record.first.value)
        put("second", record.second.value)
        put("asDeclarerPair", pairRecord(record.asDeclarerPair))
        put("asOpponentPair", pairRecord(record.asOpponentPair))
    }

    /**
     * `HistoryStats` vollständig. Mit [playerStatsAsOrder] stehen statt der Kennzahlen je Spieler nur die Spieler in
     * ihrer Reihenfolge da (`playerStatsOrder`): für einen einzelnen Abend, dessen Kennzahlen je Spieler schon in der
     * Abend-Statistik stehen (dieselben Werte, dort in Sitzreihenfolge).
     */
    fun historyStats(stats: HistoryStats, playerStatsAsOrder: Boolean = false): JsonObject = buildJsonObject {
        if (playerStatsAsOrder) {
            put("playerStatsOrder", players(stats.playerStats.map { it.player }))
        } else {
            put("playerStats", JsonArray(stats.playerStats.map(::playerStats)))
        }
        put("eveningCount", stats.eveningCount)
        put("gameCount", stats.gameCount)
        put("partnerRecords", JsonArray(stats.partnerRecords.map(::partnerRecord)))
        put("gameTypeCounts", byType(stats.gameTypeCounts, ::number))
        put("bockGamesCount", stats.bockGamesCount)
        put("bockTriggerCount", stats.bockTriggerCount)
        put("thrownInCount", stats.thrownInCount)
        put("leadChanges", stats.leadChanges)
        put("mostExpensiveGame", expensiveGame(stats.mostExpensiveGame))
        put("turnover", stats.turnover)
    }

    private fun standing(standing: Standing): JsonObject = buildJsonObject {
        put("player", standing.player.value)
        put("balance", standing.balance)
        put("rank", standing.rank)
    }

    private fun finance(finance: FinanceOverview): JsonObject = buildJsonObject {
        put("standings", JsonArray(finance.standings.map(::standing)))
        put("balanceSeries", byPlayer(finance.balanceSeries, ::numbers))
        put("eveningStarts", numbers(finance.eveningStarts))
        put("eveningIds", JsonArray(finance.eveningIds.map { text(it.value) }))
        put("turnover", finance.turnover)
    }

    private fun modeRecord(record: ModeRecord): JsonObject = buildJsonObject {
        put("games", record.games)
        put("won", record.won)
        put("balance", record.balance)
        put("lost", record.lost)
        put("winRate", rate(record.winRate))
        put("averageAmount", numberOrNull(record.averageAmount))
    }

    private fun gameModes(modes: PlayerGameModes): JsonObject = buildJsonObject {
        put("player", modes.player.value)
        put("atTable", byType(modes.atTable, ::modeRecord))
        put("asPlayer", byType(modes.asPlayer, ::modeRecord))
    }

    private fun gelegtRecord(record: GelegtRecord): JsonObject = buildJsonObject {
        put("player", record.player.value)
        put("count", record.count)
        put("won", record.won)
        put("netBalance", record.netBalance)
        put("effect", record.effect)
        put("lost", record.lost)
        put("winRate", rate(record.winRate))
        put("badge", record.badge)
        put("shouldLeaveIt", record.shouldLeaveIt)
    }

    private fun bockRecord(record: BockRecord?): JsonElement = if (record == null) {
        JsonNull
    } else {
        buildJsonObject {
            put("player", record.player.value)
            put("games", record.games)
            put("won", record.won)
            put("balance", record.balance)
            put("winRate", rate(record.winRate))
        }
    }

    private fun bockOverview(overview: BockOverview): JsonObject = buildJsonObject {
        put("records", JsonArray(overview.records.map(::bockRecord)))
        put("king", bockRecord(overview.king))
    }

    private fun soloRecord(record: SoloRecord?): JsonElement = if (record == null) {
        JsonNull
    } else {
        buildJsonObject {
            put("player", record.player.value)
            put("solos", record.solos)
            put("calls", record.calls)
            put("announced", record.announced)
            put("soloShare", rate(record.soloShare))
        }
    }

    private fun soloOverview(overview: SoloOverview): JsonObject = buildJsonObject {
        put("records", JsonArray(overview.records.map(::soloRecord)))
        put("specialist", soloRecord(overview.specialist))
        put("wallflower", soloRecord(overview.wallflower))
    }

    private fun partnerPair(pair: PartnerPair?): JsonElement = if (pair == null) {
        JsonNull
    } else {
        buildJsonObject {
            put("first", pair.first.value)
            put("second", pair.second.value)
            put("games", pair.games)
            put("won", pair.won)
            put("balance", pair.balance)
            put("winRate", rate(pair.winRate))
        }
    }

    private fun partnerOverview(overview: PartnerOverview): JsonObject = buildJsonObject {
        put("pairs", JsonArray(overview.pairs.map(::partnerPair)))
        put("favorite", partnerPair(overview.favorite))
    }

    /**
     * `Statistics` vollständig; mit [withHistory] = `false` ohne `history` (die steht dann eigens daneben), mit
     * [historyPlayerStatsAsOrder] die `history` ohne die Kennzahlen je Spieler (siehe [historyStats]).
     */
    fun statistics(
        statistics: Statistics,
        withHistory: Boolean = true,
        historyPlayerStatsAsOrder: Boolean = false,
    ): JsonObject = buildJsonObject {
        put("eveningCount", statistics.eveningCount)
        put("gameCount", statistics.gameCount)
        put("players", players(statistics.players))
        put("finance", finance(statistics.finance))
        put("gameModes", JsonArray(statistics.gameModes.map(::gameModes)))
        put("gelegt", JsonArray(statistics.gelegt.map(::gelegtRecord)))
        put("bock", bockOverview(statistics.bock))
        put("solo", soloOverview(statistics.solo))
        put("partners", partnerOverview(statistics.partners))
        if (withHistory) put("history", historyStats(statistics.history, historyPlayerStatsAsOrder))
    }
}

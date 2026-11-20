package io.github.q0ochen.schafkopf.domain.golden

import io.github.q0ochen.schafkopf.domain.evening.EveningCalculator
import io.github.q0ochen.schafkopf.domain.evening.GameEntry
import io.github.q0ochen.schafkopf.domain.evening.Ranking
import io.github.q0ochen.schafkopf.domain.evening.SettleUp
import io.github.q0ochen.schafkopf.domain.round.DuplicateCheck
import io.github.q0ochen.schafkopf.domain.round.EveningMerge
import io.github.q0ochen.schafkopf.domain.round.InviteLinks
import io.github.q0ochen.schafkopf.domain.rules.PayoutCalculator
import io.github.q0ochen.schafkopf.domain.rules.PayoutRule
import io.github.q0ochen.schafkopf.domain.rules.ScoreCalculator
import io.github.q0ochen.schafkopf.domain.stats.EveningStatistics
import io.github.q0ochen.schafkopf.domain.stats.HistoryStatistics
import io.github.q0ochen.schafkopf.domain.stats.Progression
import io.github.q0ochen.schafkopf.domain.stats.StatisticEngine
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.fail
import org.junit.jupiter.api.Test
import java.io.File

/**
 * Golden-Daten für die Web-App: Der Rechenkern der Web-App (`web/src/core`, TypeScript) muss Cent für Cent und
 * Kennzahl für Kennzahl dasselbe liefern wie dieser Kotlin-Kern.
 *
 * Dieser Test lässt den echten Kotlin-Kern viele feste Eingaben durchrechnen (gezielte Grenzfälle und Zufallsfälle
 * mit festen Startwerten und Zeitpunkten) und beschreibt Ein- und Ausgaben als JSON (siehe [GoldenJson],
 * [GoldenCases]). Der TypeScript-Test `web/src/core/golden/kotlinGolden.test.ts` rechnet dieselben Eingaben mit dem
 * Port und verlangt exakt dieselben Ausgaben.
 *
 * - Mit `-PgoldenWrite=true` (Systemeigenschaft `golden.write`) schreibt er die Datei neu.
 * - Sonst vergleicht er den frisch erzeugten Inhalt mit der eingecheckten Datei und schlägt fehl, wenn sie veraltet
 *   ist. So zwingt jede Änderung am Kotlin-Kern dazu, die Web-App mitzuziehen.
 *
 * Den Pfad der Datei setzt `domain/build.gradle.kts` (Systemeigenschaft `golden.file`).
 */
class WebGoldenTest {

    @Test
    fun `die Golden-Daten der Web-App passen zum Kotlin-Kern`() {
        val path = System.getProperty("golden.file")
            ?: fail<String>("Systemeigenschaft golden.file fehlt (siehe domain/build.gradle.kts).")
        val file = File(path)
        val content = WebGolden.document()

        if (System.getProperty("golden.write") == "true") {
            file.parentFile.mkdirs()
            file.writeText(content, Charsets.UTF_8)
            return
        }

        val existing = if (file.isFile) file.readText(Charsets.UTF_8) else ""
        if (existing != content) {
            fail<Unit>(
                "Golden-Daten veraltet: Der Kotlin-Kern rechnet anders als beim letzten Erzeugen. Neu erzeugen mit " +
                    "`tools/jvm-build.sh :domain:test --tests '*WebGoldenTest' -PgoldenWrite=true` und danach den " +
                    "TypeScript-Port in web/src/core anpassen, bis `cd web && npm test` grün ist.\n" +
                    firstDifference(existing, content, file),
            )
        }
    }

    @Test
    fun `die Golden-Daten sind bei jedem Lauf gleich`() {
        // Ohne das könnte der Vergleich oben nie gelingen: kein Zufall ohne festen Startwert, keine „jetzt“-Zeit.
        assertEquals(WebGolden.document(), WebGolden.document())
    }

    /** Die erste Abweichung mit Zeile, Fall und Umgebung, damit man sieht, welcher Fall sich wie geändert hat. */
    private fun firstDifference(existing: String, content: String, file: File): String {
        if (existing.isEmpty()) return "Die Datei ${file.path} fehlt oder ist leer."
        val old = existing.lines()
        val new = content.lines()
        val line = old.indices.firstOrNull { it >= new.size || old[it] != new[it] } ?: minOf(old.size, new.size)
        val before = old.getOrNull(line).orEmpty()
        val after = new.getOrNull(line).orEmpty()
        val column = before.commonPrefixWith(after).length
        val case = CASE_ID.find(after.ifEmpty { before })?.groupValues?.get(1)?.let { " (Fall $it)" }.orEmpty()
        fun excerpt(text: String) =
            if (text.isEmpty()) "(Dateiende)" else "…" + text.substring(maxOf(0, column - 80), minOf(text.length, column + 120)) + "…"
        return "Erste Abweichung in Zeile ${line + 1}, Zeichen ${column + 1}$case von ${file.path}:\n" +
            "  eingecheckt: ${excerpt(before)}\n" +
            "  neu:         ${excerpt(after)}"
    }

    private companion object {
        /** Jeder Fall steht auf einer eigenen Zeile und beginnt mit seiner Kennung. */
        val CASE_ID = Regex("""^\{"id":"([^"]*)"""")
    }
}

/** Baut die Datei `web/src/core/golden/kotlin-golden.json`: jeder Fall auf einer eigenen Zeile. */
internal object WebGolden {

    fun document(): String {
        val evenings = GoldenCases.eveningCases()
        val sections = listOf(
            "rules" to GoldenCases.rules.map { (name, rules) -> name to GoldenJson.ruleSet(rules) },
            "score" to GoldenCases.scoreCases().map(::scoreCase),
            "payout" to GoldenCases.payoutCases().map(::payoutCase),
            "balances" to GoldenCases.balancesCases().map(::balancesCase),
            "evenings" to evenings.map(::eveningCase),
            "histories" to GoldenCases.historyCases(evenings).map { historyCase(it, evenings) },
            "merges" to RoundGoldenCases.mergeCases().map(::mergeCase),
            "duplicates" to RoundGoldenCases.duplicateCases().map(::duplicateCase),
            "invites" to RoundGoldenCases.inviteCases().map(::inviteCase),
        )
        return buildString {
            append("{\n")
            append("\"about\": ").append(JsonPrimitive(ABOUT)).append(",\n")
            sections.forEachIndexed { index, (name, entries) ->
                if (name == "rules") {
                    // Die Regelwerke als Objekt (Name → Regelwerk), je eines auf einer Zeile.
                    append("\"rules\": {\n")
                    append(entries.joinToString(",\n") { (key, value) -> "${JsonPrimitive(key)}: $value" })
                    append("\n}")
                } else {
                    append(JsonPrimitive(name)).append(": [\n")
                    append(entries.joinToString(",\n") { (_, value) -> value.toString() })
                    append("\n]")
                }
                append(if (index == sections.lastIndex) "\n" else ",\n")
            }
            append("}\n")
        }
    }

    private const val ABOUT =
        "Erzeugt vom Kotlin-Kern (domain/src/test/kotlin/io/github/q0ochen/schafkopf/domain/golden/WebGoldenTest.kt); " +
            "nicht von Hand ändern. Neu erzeugen: tools/jvm-build.sh :domain:test --tests '*WebGoldenTest' -PgoldenWrite=true"

    private fun scoreCase(case: ScoreCase): Pair<String, JsonObject> {
        val rules = GoldenCases.rules.getValue(case.rules)
        val facts = case.facts
        return case.id to buildJsonObject {
            put("id", case.id)
            put("rules", case.rules)
            put("facts", GoldenJson.facts(facts))
            put("declarerSide", GoldenJson.players(facts.declarerSide))
            put("winners", GoldenJson.players(facts.winners))
            put("losers", GoldenJson.players(facts.losers))
            put("breakdown", GoldenJson.breakdown(ScoreCalculator.breakdown(facts, rules)))
            put("amounts", GoldenJson.amounts(ScoreCalculator.amounts(facts, rules)))
            put("validate", GoldenJson.names(ScoreCalculator.validate(facts, rules)))
        }
    }

    private fun payoutCase(case: PayoutCase): Pair<String, JsonObject> = case.id to buildJsonObject {
        put("id", case.id)
        put("value", case.value)
        put("winners", GoldenJson.players(case.winners))
        put("losers", GoldenJson.players(case.losers))
        for (rule in PayoutRule.entries) {
            put(rule.name, GoldenJson.amounts(PayoutCalculator.of(rule).payout(case.value, case.winners, case.losers)))
        }
    }

    private fun balancesCase(case: BalancesCase): Pair<String, JsonObject> = case.id to buildJsonObject {
        put("id", case.id)
        put("balances", GoldenJson.amounts(case.balances))
        put("isBalanced", SettleUp.isBalanced(case.balances))
        put("transfers", GoldenJson.transfers(SettleUp.transfers(case.balances)))
        put("ranks", GoldenJson.amounts(Ranking.ranks(case.balances)))
    }

    private fun eveningCase(case: EveningCase): Pair<String, JsonObject> {
        val evening = case.evening
        val state = EveningCalculator.compute(evening)
        val eveningStats = EveningStatistics.compute(state)
        val statistics = StatisticEngine.forEvening(evening)
        // Die Kennzahlen je Spieler in `statistics.history` sind genau die der Abend-Statistik, nur nach Saldo geordnet.
        // Deshalb steht dort in der Datei nur die Reihenfolge (`playerStatsOrder`); das spart fast ein Fünftel der Datei.
        assertEquals(eveningStats.playerStats.size, statistics.history.playerStats.size, "Spieler in ${case.id}")
        assertEquals(eveningStats.playerStats.toSet(), statistics.history.playerStats.toSet(), "Kennzahlen je Spieler in ${case.id}")
        val results = state.results.map { result ->
            val entry = result.entry
            // Für jedes Spiel auch die Prüfung des ScoreCalculator, mit der Bock-Stufe, die der Abend ihm gab.
            val violations = if (entry is GameEntry) {
                val facts = with(EveningCalculator) { entry.toFacts(evening.seating, result.bockExponent) }
                ScoreCalculator.validate(facts, evening.rules)
            } else {
                null
            }
            GoldenJson.entryResult(result, violations)
        }
        return case.id to buildJsonObject {
            put("id", case.id)
            put("evening", GoldenJson.evening(evening))
            put("state", GoldenJson.state(state, results))
            put(
                "settleUp",
                buildJsonObject {
                    put("isBalanced", SettleUp.isBalanced(state.balances))
                    put("transfers", GoldenJson.transfers(SettleUp.transfers(state.balances)))
                },
            )
            put(
                "progression",
                buildJsonObject {
                    put("balanceSeries", GoldenJson.byPlayer(Progression.balanceSeries(state), GoldenJson::numbers))
                    put("rankSeries", GoldenJson.byPlayer(Progression.rankSeries(state), GoldenJson::numbers))
                },
            )
            put("eveningStatistics", GoldenJson.eveningStats(eveningStats))
            put("statistics", GoldenJson.statistics(statistics, historyPlayerStatsAsOrder = true))
        }
    }

    /** Gemeinsame Runde: drei Fassungen eines Abends und das Ergebnis von `EveningMerge.merge3`. */
    private fun mergeCase(case: MergeCase): Pair<String, JsonObject> = case.id to buildJsonObject {
        put("id", case.id)
        put("base", case.base?.let(GoldenJson::evening) ?: JsonNull)
        put("local", GoldenJson.evening(case.local))
        put("remote", GoldenJson.evening(case.remote))
        put("merged", GoldenJson.evening(EveningMerge.merge3(case.base, case.local, case.remote)))
    }

    /** Gemeinsame Runde: ein Abend und die Kennungen der vermutlich doppelt eingetragenen Spiele. */
    private fun duplicateCase(case: DuplicateCase): Pair<String, JsonObject> = case.id to buildJsonObject {
        put("id", case.id)
        put("evening", GoldenJson.evening(case.evening))
        put("suspects", JsonArray(DuplicateCheck.suspects(case.evening).map { JsonPrimitive(it.value) }))
    }

    /** Gemeinsame Runde: ein eingefügter Text und der Einladungscode darin (`null`, wenn keiner darin steht). */
    private fun inviteCase(case: InviteCase): Pair<String, JsonObject> = case.id to buildJsonObject {
        put("id", case.id)
        put("text", case.text)
        put("token", InviteLinks.tokenFrom(case.text)?.let(::JsonPrimitive) ?: JsonNull)
    }

    /**
     * Eine Gruppe von Abenden. `statistics` ist `StatisticEngine.forEvenings` ohne `history`: Die steht als `history`
     * daneben, weil sie hier genau `HistoryStatistics.compute` ist (das prüft dieser Test); so steht sie nur einmal in
     * der Datei. Der TypeScript-Test vergleicht beide Ergebnisse des Ports damit.
     */
    private fun historyCase(case: HistoryCase, evenings: List<EveningCase>): Pair<String, JsonObject> {
        val byId = evenings.associateBy { it.id }
        val list = case.evenings.map { byId.getValue(it).evening }
        val statistics = StatisticEngine.forEvenings(list)
        val history = HistoryStatistics.compute(list)
        assertEquals(history, statistics.history, "forEvenings(…).history und HistoryStatistics.compute in ${case.id}")
        return case.id to buildJsonObject {
            put("id", case.id)
            put("evenings", JsonArray(case.evenings.map(::JsonPrimitive)))
            put("statistics", GoldenJson.statistics(statistics, withHistory = false))
            put("history", GoldenJson.historyStats(history))
        }
    }
}

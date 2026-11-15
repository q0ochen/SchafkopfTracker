package io.github.q0ochen.schafkopf.data.golden

import io.github.q0ochen.schafkopf.domain.evening.BockTriggerEntry
import io.github.q0ochen.schafkopf.domain.evening.Evening
import io.github.q0ochen.schafkopf.domain.evening.EveningEntry
import io.github.q0ochen.schafkopf.domain.evening.GameEntry
import io.github.q0ochen.schafkopf.domain.evening.ManualEntry
import io.github.q0ochen.schafkopf.domain.evening.ThrownInEntry
import io.github.q0ochen.schafkopf.domain.model.AppSettings
import io.github.q0ochen.schafkopf.domain.model.Player
import io.github.q0ochen.schafkopf.domain.rules.GameType
import io.github.q0ochen.schafkopf.domain.rules.RuleSet
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonArray
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/** Die Form der Daten wie in `web/src/core/model.ts`: Aufzählungen mit ihren Kotlin-Namen, Tabellen als Paarlisten. */
internal object GoldenModel {

    fun player(player: Player): JsonObject = buildJsonObject {
        put("id", player.id.value)
        put("name", player.name)
        put("colorIndex", player.colorIndex)
        put("archived", player.archived)
    }

    fun players(players: List<Player>): JsonArray = buildJsonArray { players.forEach { add(player(it)) } }

    private fun table(values: Map<GameType, Int>): JsonArray = buildJsonArray {
        for ((type, value) in values) {
            addJsonArray {
                add(type.name)
                add(value)
            }
        }
    }

    fun rules(rules: RuleSet): JsonObject = buildJsonObject {
        put("name", rules.name)
        put("unit", rules.unit)
        put("prices", table(rules.prices))
        putJsonArray("enabledTypes") { rules.enabledTypes.forEach { add(it.name) } }
        put("laufendeMin", table(rules.laufendeMin))
        put("laufendeMax", table(rules.laufendeMax))
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
                put("stacking", rules.bock.stacking.name)
                put("thrownInConsumesBockGame", rules.bock.thrownInConsumesBockGame)
            },
        )
        put("dealerAdvancesAfterThrowIn", rules.dealerAdvancesAfterThrowIn)
        put("displayAsMoney", rules.displayAsMoney)
        put("payoutRule", rules.payoutRule.name)
    }

    fun entry(entry: EveningEntry): JsonObject = when (entry) {
        is GameEntry -> buildJsonObject {
            put("kind", "game")
            put("id", entry.id.value)
            put("recordedAt", entry.recordedAt.toString())
            put("type", entry.type.name)
            put("suit", entry.suit?.name)
            put("declarer", entry.declarer.value)
            put("partner", entry.partner?.value)
            put("declarerWon", entry.declarerWon)
            put("level", entry.level.name)
            put("laufende", entry.laufende)
            put("announcement", entry.announcement.name)
            putJsonArray("klopfer") { entry.klopfer.forEach { add(it.value) } }
            put("kontra", entry.kontra.name)
            put("kontraBy", entry.kontraBy?.value)
            put("retourBy", entry.retourBy?.value)
            put("bockOverride", entry.bockOverride.name)
            put("declarerEyes", entry.declarerEyes)
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
            put("reason", entry.reason.name)
            put("player", entry.player?.value)
            put("handThrownIn", entry.handThrownIn)
        }

        is ManualEntry -> buildJsonObject {
            put("kind", "manual")
            put("id", entry.id.value)
            put("recordedAt", entry.recordedAt.toString())
            putJsonArray("amounts") {
                for ((player, amount) in entry.amounts) {
                    addJsonArray {
                        add(player.value)
                        add(amount)
                    }
                }
            }
            put("note", entry.note)
        }
    }

    fun evening(evening: Evening): JsonObject = buildJsonObject {
        put("id", evening.id.value)
        put("startedAt", evening.startedAt.toString())
        put("finishedAt", evening.finishedAt?.toString())
        put("location", evening.location)
        putJsonArray("seating") { evening.seating.forEach { add(it.value) } }
        put("firstDealer", evening.firstDealer.value)
        put("rules", rules(evening.rules))
        putJsonArray("entries") { evening.entries.forEach { add(entry(it)) } }
    }

    /** Die Einstellungen, soweit die Web-App sie hat (ohne Vibration und ohne KI-Zusammenfassung). */
    fun settings(settings: AppSettings): JsonObject = buildJsonObject {
        put("defaultRules", rules(settings.defaultRules))
        put("keepScreenOn", settings.keepScreenOn)
        put("themeMode", settings.themeMode.name)
    }
}

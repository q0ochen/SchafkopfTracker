package io.github.q0ochen.schafkopf.data.storage

import io.github.q0ochen.schafkopf.domain.evening.BockOverride
import io.github.q0ochen.schafkopf.domain.evening.BockReason
import io.github.q0ochen.schafkopf.domain.evening.BockTriggerEntry
import io.github.q0ochen.schafkopf.domain.evening.Evening
import io.github.q0ochen.schafkopf.domain.evening.GameEntry
import io.github.q0ochen.schafkopf.domain.evening.ManualEntry
import io.github.q0ochen.schafkopf.domain.evening.ThrownInEntry
import io.github.q0ochen.schafkopf.domain.model.AiProvider
import io.github.q0ochen.schafkopf.domain.model.AiSettings
import io.github.q0ochen.schafkopf.domain.model.AppSettings
import io.github.q0ochen.schafkopf.domain.model.EntryId
import io.github.q0ochen.schafkopf.domain.model.EveningId
import io.github.q0ochen.schafkopf.domain.model.Player
import io.github.q0ochen.schafkopf.domain.model.PlayerId
import io.github.q0ochen.schafkopf.domain.model.ThemeMode
import io.github.q0ochen.schafkopf.domain.rules.Announcement
import io.github.q0ochen.schafkopf.domain.rules.BockRules
import io.github.q0ochen.schafkopf.domain.rules.BockStacking
import io.github.q0ochen.schafkopf.domain.rules.GameType
import io.github.q0ochen.schafkopf.domain.rules.KontraLevel
import io.github.q0ochen.schafkopf.domain.rules.ResultLevel
import io.github.q0ochen.schafkopf.domain.rules.RuleSet
import io.github.q0ochen.schafkopf.domain.rules.Suit
import kotlinx.coroutines.CoroutineDispatcher
import java.time.Instant
import kotlin.coroutines.CoroutineContext

/**
 * Ein Dispatcher für Tests: zählt, wie oft ihm Arbeit übergeben wurde, und führt sie sofort aus.
 * So lässt sich prüfen, was auf dem IO-Dispatcher läuft und was aus dem Speicher kommt.
 */
internal class CountingDispatcher : CoroutineDispatcher() {
    var dispatched = 0
        private set

    override fun dispatch(context: CoroutineContext, block: Runnable) {
        dispatched++
        block.run()
    }
}

/** Gemeinsame Beispieldaten für die Tests der Datenhaltung und der Sicherung. */
internal object TestData {

    val a = PlayerId("spieler-anna")
    val b = PlayerId("spieler-bertl")
    val c = PlayerId("spieler-centa")
    val d = PlayerId("spieler-done")
    val seating = listOf(a, b, c, d)

    val anna = Player(a, "Anna", colorIndex = 0)
    val bertl = Player(b, "Bertl", colorIndex = 1)
    val centa = Player(c, "Centa", colorIndex = 2)
    val done = Player(d, "Done", colorIndex = 3)
    val players = listOf(anna, bertl, centa, done)

    val start: Instant = Instant.parse("2026-11-14T19:00:00Z")

    /** Ein Regelwerk, in dem jeder einzelne Wert von der Vorlage Sauspiel abweicht. */
    val fullRules = RuleSet(
        name = "Hausregeln 5-10-25",
        unit = 5,
        prices = mapOf(GameType.RUFSPIEL to 10, GameType.FARBSOLO to 25, GameType.WENZ to 30, GameType.GEIER to 35),
        enabledTypes = setOf(GameType.RUFSPIEL, GameType.WENZ),
        laufendeMin = mapOf(GameType.RUFSPIEL to 2, GameType.FARBSOLO to 4, GameType.WENZ to 3, GameType.GEIER to 1),
        laufendeMax = mapOf(GameType.RUFSPIEL to 8, GameType.FARBSOLO to 9, GameType.WENZ to 3, GameType.GEIER to 2),
        klopfenEnabled = false,
        kontraEnabled = false,
        retourEnabled = false,
        toutFactor = 3,
        sieFactor = 4,
        sieLaufende = 6,
        bock = BockRules(
            enabled = false,
            gamesPerTrigger = 8,
            factor = 3,
            stacking = BockStacking.STACK,
            thrownInConsumesBockGame = true,
        ),
        dealerAdvancesAfterThrowIn = false,
        displayAsMoney = false,
    )

    /** Einstellungen, in denen jeder einzelne Wert vom Standard abweicht. */
    val customSettings = AppSettings(
        defaultRules = fullRules,
        keepScreenOn = false,
        themeMode = ThemeMode.DARK,
        haptics = false,
        ai = AiSettings(
            enabled = true,
            provider = AiProvider.OPENAI_COMPATIBLE,
            model = "testmodell-7",
            baseUrl = "https://ki.example.invalid/v1",
            sendRealNames = true,
            consentHost = "ki.example.invalid",
        ),
    )

    /**
     * Ein beendeter Abend mit jeder Eintragsart, einem vollen Regelwerk und Spielen, in denen jede freiwillige
     * Angabe gesetzt ist. Er ist in sich stimmig: Alle genannten Spieler sitzen am Tisch, die freie Buchung
     * geht auf (150 − 50 − 100 = 0).
     */
    fun fullEvening(id: String = "abend-voll", startedAt: Instant = start): Evening = Evening(
        id = EveningId(id),
        startedAt = startedAt,
        finishedAt = startedAt.plusSeconds(4 * 3600),
        location = "Wirtshaus zur Post",
        seating = seating,
        firstDealer = b,
        rules = fullRules,
        entries = listOf(
            GameEntry(
                id = EntryId("e1"),
                recordedAt = Instant.parse("2026-11-14T19:05:30.123456789Z"),
                type = GameType.FARBSOLO,
                suit = Suit.HERZ,
                declarer = c,
                partner = null,
                declarerWon = false,
                level = ResultLevel.SCHWARZ,
                laufende = 4,
                announcement = Announcement.TOUT,
                klopfer = setOf(d, a),
                kontra = KontraLevel.RETOUR,
                kontraBy = a,
                retourBy = c,
                bockOverride = BockOverride.FORCE_ON,
                declarerEyes = 0,
                note = "Herz-Solo Tout; \"schwarz\" verloren, ärgerlich",
            ),
            GameEntry(
                id = EntryId("e2"),
                recordedAt = startedAt.plusSeconds(600),
                type = GameType.RUFSPIEL,
                suit = Suit.EICHEL,
                declarer = a,
                partner = b,
                declarerWon = true,
            ),
            ThrownInEntry(id = EntryId("e3"), recordedAt = startedAt.plusSeconds(900)),
            BockTriggerEntry(
                id = EntryId("e4"),
                recordedAt = startedAt.plusSeconds(1200),
                reason = BockReason.SECHS_NIXER,
                player = c,
                handThrownIn = true,
            ),
            BockTriggerEntry(
                id = EntryId("e5"),
                recordedAt = startedAt.plusSeconds(1500),
                reason = BockReason.MANUAL,
                player = null,
                handThrownIn = false,
            ),
            ManualEntry(
                id = EntryId("e6"),
                recordedAt = startedAt.plusSeconds(1800),
                amounts = mapOf(a to 150, b to -50, c to -100),
                note = "Wette um die Brotzeit",
            ),
            GameEntry(
                id = EntryId("e7"),
                recordedAt = startedAt.plusSeconds(2100),
                type = GameType.GEIER,
                suit = Suit.SCHELLEN,
                declarer = d,
                declarerWon = true,
                level = ResultLevel.SCHNEIDER,
                laufende = 2,
                announcement = Announcement.SIE,
                kontra = KontraLevel.KONTRA,
                kontraBy = b,
                bockOverride = BockOverride.FORCE_OFF,
                declarerEyes = 120,
            ),
        ),
    )

    /** Ein laufender Abend nach den Regeln von Sauspiel mit einem einzigen Rufspiel (Anna mit Bertl, gewonnen). */
    fun simpleEvening(id: String, startedAt: Instant = start): Evening = Evening(
        id = EveningId(id),
        startedAt = startedAt,
        seating = seating,
        firstDealer = a,
        rules = RuleSet.sauspiel(),
        entries = listOf(
            GameEntry(
                id = EntryId("spiel-1"),
                recordedAt = startedAt.plusSeconds(300),
                type = GameType.RUFSPIEL,
                declarer = a,
                partner = b,
                declarerWon = true,
            ),
        ),
    )
}

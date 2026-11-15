package io.github.q0ochen.schafkopf.domain.golden

import io.github.q0ochen.schafkopf.domain.evening.BockOverride
import io.github.q0ochen.schafkopf.domain.evening.BockReason
import io.github.q0ochen.schafkopf.domain.evening.BockTriggerEntry
import io.github.q0ochen.schafkopf.domain.evening.Evening
import io.github.q0ochen.schafkopf.domain.evening.EveningEntry
import io.github.q0ochen.schafkopf.domain.evening.GameEntry
import io.github.q0ochen.schafkopf.domain.evening.ManualEntry
import io.github.q0ochen.schafkopf.domain.evening.ThrownInEntry
import io.github.q0ochen.schafkopf.domain.model.EntryId
import io.github.q0ochen.schafkopf.domain.model.EveningId
import io.github.q0ochen.schafkopf.domain.model.PlayerId
import io.github.q0ochen.schafkopf.domain.rules.Announcement
import io.github.q0ochen.schafkopf.domain.rules.BockRules
import io.github.q0ochen.schafkopf.domain.rules.BockStacking
import io.github.q0ochen.schafkopf.domain.rules.GameFacts
import io.github.q0ochen.schafkopf.domain.rules.GameType
import io.github.q0ochen.schafkopf.domain.rules.KontraLevel
import io.github.q0ochen.schafkopf.domain.rules.PayoutRule
import io.github.q0ochen.schafkopf.domain.rules.ResultLevel
import io.github.q0ochen.schafkopf.domain.rules.RuleSet
import io.github.q0ochen.schafkopf.domain.rules.Suit
import io.github.q0ochen.schafkopf.domain.stats.RandomEvenings
import io.github.q0ochen.schafkopf.domain.stats.RoundEvenings
import io.github.q0ochen.schafkopf.domain.stats.SampleEvening
import java.time.Instant
import kotlin.random.Random

/** Ein Einzelspiel für den `ScoreCalculator`: Tatsachen und der Name des Regelwerks (siehe [GoldenCases.rules]). */
internal data class ScoreCase(val id: String, val rules: String, val facts: GameFacts)

/** Ein Aufruf des `PayoutCalculator` (beide Regeln werden gerechnet). */
internal data class PayoutCase(val id: String, val value: Int, val winners: List<PlayerId>, val losers: List<PlayerId>)

/** Salden für `SettleUp` und `Ranking`. */
internal data class BalancesCase(val id: String, val balances: Map<PlayerId, Int>)

/** Ein ganzer Abend. Die Kennung des Falls ist nicht die des Abends: Zwei Fälle dürfen dieselbe Abend-Kennung tragen. */
internal data class EveningCase(val id: String, val evening: Evening)

/** Eine Gruppe von Abenden für die „ewige Tabelle“, als Kennungen von [EveningCase]s in der übergebenen Reihenfolge. */
internal data class HistoryCase(val id: String, val evenings: List<String>)

/**
 * Die Eingaben der Golden-Daten: fest gewählte Grenzfälle und viele Zufallsfälle mit festen Startwerten und festen
 * Zeitpunkten. Dieselbe Fassung des Kerns ergibt deshalb immer genau dieselbe Datei.
 */
internal object GoldenCases {

    private val a = PlayerId("A")
    private val b = PlayerId("B")
    private val c = PlayerId("C")
    private val d = PlayerId("D")
    private val e = PlayerId("E")
    private val x = PlayerId("X")
    private val table = listOf(a, b, c, d)

    /** Meist einer vom Tisch, manchmal ein Unbekannter oder die leere Kennung. */
    private val pool = listOf(a, b, c, d, e, x, PlayerId(""))

    /**
     * Sechs Spieler für die „ewige Tabelle“, deren Kennungen sich nach UTF-16-Codeeinheiten (so vergleicht Kotlin)
     * anders ordnen als nach Zahlenwert, nach Sprachregeln oder nach Unicode-Codepunkt: „10“ vor „9“, „A“ vor „a“ und
     * das Emoji (ein Ersatzzeichenpaar, U+D83D …) vor „～“ (U+FF5E), obwohl sein Codepunkt größer ist.
     */
    private val historyPool = listOf("A", "a", "10", "9", "😀", "～").map(::PlayerId)

    /** Eine Zahl mit führenden Nullen, unabhängig von der Spracheinstellung (anders als `String.format`). */
    private fun pad(number: Int, width: Int = 2): String = number.toString().padStart(width, '0')

    private val now: Instant = Instant.parse("2026-11-15T19:00:00Z")

    private val extremes = listOf(
        Int.MIN_VALUE, Int.MIN_VALUE + 1, -2_000_000_000, -1_000_000, -100, -10, -2, -1, 0, 1, 2, 3, 4, 5, 8, 9, 10, 50,
        1_000, 1_000_000, 715_827_882, 715_827_883, 1_073_741_824, 2_000_000_000, Int.MAX_VALUE - 1, Int.MAX_VALUE,
    )

    private fun Random.anyInt(): Int = when (nextInt(4)) {
        0 -> extremes.random(this)
        1 -> nextInt()
        else -> nextInt(-20, 200)
    }

    private fun Random.sometimes(usual: Int): Int = if (nextInt(3) == 0) anyInt() else usual

    // ---------------------------------------------------------------- Regelwerke

    private val standard = RuleSet.standard()
    private val sauspiel = RuleSet.sauspiel()

    private fun bock(
        enabled: Boolean = true,
        gamesPerTrigger: Int = 4,
        factor: Int = 2,
        stacking: BockStacking = BockStacking.APPEND,
        thrownInConsumesBockGame: Boolean = false,
    ) = BockRules(enabled, gamesPerTrigger, factor, stacking, thrownInConsumesBockGame)

    /** Ein beschädigtes Regelwerk: einzelne Werte unsinnig, Tabellen lückenhaft, Schalter zufällig. */
    private fun Random.brokenRules(name: String): RuleSet = standard.copy(
        name = name,
        unit = sometimes(standard.unit),
        prices = GameType.entries.filter { nextInt(5) != 0 }.associateWith { sometimes(standard.price(it)) },
        laufendeMin = GameType.entries.filter { nextInt(5) != 0 }.associateWith { sometimes(standard.laufendeMin(it)) },
        laufendeMax = GameType.entries.filter { nextInt(5) != 0 }.associateWith { sometimes(standard.laufendeMax(it)) },
        enabledTypes = GameType.entries.filter { nextInt(4) != 0 }.toSet(),
        klopfenEnabled = nextInt(4) != 0,
        kontraEnabled = nextInt(4) != 0,
        retourEnabled = nextInt(4) != 0,
        toutFactor = sometimes(2),
        sieFactor = sometimes(2),
        sieLaufende = sometimes(8),
        bock = bock(
            enabled = nextInt(5) != 0,
            gamesPerTrigger = sometimes(4),
            factor = sometimes(2),
            stacking = BockStacking.entries.random(this),
            thrownInConsumesBockGame = nextBoolean(),
        ),
        dealerAdvancesAfterThrowIn = nextBoolean(),
        displayAsMoney = nextBoolean(),
        payoutRule = PayoutRule.entries.random(this),
    )

    /** Alle Regelwerke mit Namen; die Einzelspiele verweisen darauf, die Abende tragen ihre eigene Kopie. */
    val rules: Map<String, RuleSet> = buildMap {
        put("standard", standard)
        put("sauspiel", sauspiel)
        put("topf", standard.copy(name = "Topf-Teilung", payoutRule = PayoutRule.POT_SPLIT))
        put("sauspiel-topf", sauspiel.copy(payoutRule = PayoutRule.POT_SPLIT))
        put("ohne-zusaetze", standard.copy(klopfenEnabled = false, kontraEnabled = false, retourEnabled = false))
        put("ohne-retour", standard.copy(retourEnabled = false))
        put("nur-rufspiel", standard.copy(enabledTypes = setOf(GameType.RUFSPIEL)))
        put("nur-soli", standard.copy(enabledTypes = setOf(GameType.FARBSOLO, GameType.WENZ, GameType.GEIER)))
        // Ein älterer Abend: Über Kreuz fehlt in den Tabellen (gilt wie das Rufspiel), manches fehlt ganz (Standard).
        put(
            "alt",
            standard.copy(
                name = "Alt",
                prices = mapOf(GameType.RUFSPIEL to 20, GameType.FARBSOLO to 60),
                laufendeMin = mapOf(GameType.RUFSPIEL to 2, GameType.WENZ to 3),
                laufendeMax = mapOf(GameType.FARBSOLO to 14),
            ),
        )
        put("leere-tabellen", standard.copy(prices = emptyMap(), laufendeMin = emptyMap(), laufendeMax = emptyMap()))
        put("tout-vierfach", standard.copy(toutFactor = 4, sieFactor = 4, sieLaufende = 14))
        put(
            "punkte",
            standard.copy(
                name = "Punkte", unit = 1, displayAsMoney = false,
                prices = GameType.entries.associateWith { if (it.isSolo) 5 else 1 },
            ),
        )
        put("bock-stapeln", standard.copy(bock = bock(stacking = BockStacking.STACK)))
        put("bock-ignorieren", standard.copy(bock = bock(stacking = BockStacking.IGNORE)))
        put("bock-aus", standard.copy(bock = bock(enabled = false)))
        put("bock-null-spiele", standard.copy(bock = bock(gamesPerTrigger = 0)))
        put("bock-zwei-spiele", standard.copy(bock = bock(gamesPerTrigger = 2, factor = 3)))
        put("bock-riesig", standard.copy(bock = bock(gamesPerTrigger = Int.MAX_VALUE)))
        put("bock-riesig-stapeln", standard.copy(bock = bock(gamesPerTrigger = Int.MAX_VALUE, stacking = BockStacking.STACK)))
        put("bock-negativ", standard.copy(bock = bock(gamesPerTrigger = -4)))
        put(
            "zusammengeworfen-zaehlt",
            standard.copy(bock = bock(thrownInConsumesBockGame = true), dealerAdvancesAfterThrowIn = false),
        )
        put("geber-bleibt", standard.copy(dealerAdvancesAfterThrowIn = false))
        put("bock-faktor-0", standard.copy(bock = bock(factor = 0)))
        put("bock-faktor-minus-1", standard.copy(bock = bock(factor = -1)))
        put("bock-faktor-minus-2", standard.copy(bock = bock(factor = -2)))
        put("bock-faktor-riesig", standard.copy(bock = bock(factor = Int.MAX_VALUE)))
        put("bock-faktor-kleinst", standard.copy(bock = bock(factor = Int.MIN_VALUE)))
        put("grenze-solo", standard.copy(prices = mapOf(GameType.FARBSOLO to 715_827_882)))
        put("ueber-grenze-solo", standard.copy(prices = mapOf(GameType.FARBSOLO to 715_827_883)))
        put("riesig", standard.copy(unit = Int.MAX_VALUE, prices = GameType.entries.associateWith { Int.MAX_VALUE }))
        put("kleinst", standard.copy(unit = Int.MIN_VALUE, prices = GameType.entries.associateWith { Int.MIN_VALUE }))
        put("negativ", standard.copy(unit = -10, prices = mapOf(GameType.WENZ to -50), payoutRule = PayoutRule.POT_SPLIT))
        put("aufheben", standard.copy(unit = 1_000_000, prices = mapOf(GameType.RUFSPIEL to -2_000_000_000)))
        put(
            "faktoren-riesig",
            standard.copy(toutFactor = Int.MAX_VALUE, sieFactor = Int.MIN_VALUE, bock = bock(factor = 65_536)),
        )
        put("faktoren-negativ", standard.copy(toutFactor = -2, sieFactor = -3, sieLaufende = -8))
        put("solo-teuer", standard.copy(prices = mapOf(GameType.FARBSOLO to 700_000_000, GameType.RUFSPIEL to 700_000_000)))
        val random = Random(20261116)
        for (number in 1..24) {
            val name = "kaputt-${pad(number)}"
            put(name, random.brokenRules(name))
        }
    }

    // ---------------------------------------------------------------- Einzelspiele

    private fun facts(
        type: GameType,
        declarer: PlayerId,
        partner: PlayerId? = null,
        won: Boolean = true,
        level: ResultLevel = ResultLevel.NORMAL,
        laufende: Int = 0,
        announcement: Announcement = Announcement.NONE,
        gelegt: Int = 0,
        kontra: KontraLevel = KontraLevel.NONE,
        bockExponent: Int = 0,
        players: List<PlayerId> = table,
    ) = GameFacts(type, players, declarer, partner, won, level, laufende, announcement, gelegt, kontra, bockExponent)

    /** Gezielt gewählte Fälle: die Referenzfälle aus docs/ARCHITEKTUR.md und die Ränder des Zahlenbereichs. */
    private fun chosenScoreCases(): List<Pair<String, GameFacts>> = buildList {
        add("standard" to facts(GameType.RUFSPIEL, a, b))
        // Farbsolo mit 3 Laufenden, schwarz, zweimal gelegt: (50 + 30 + 20) × 4 = 400 je Gegner, 1200 für den Solisten.
        add("standard" to facts(GameType.FARBSOLO, a, laufende = 3, level = ResultLevel.SCHWARZ, gelegt = 2))
        add("topf" to facts(GameType.WENZ, b, won = false, laufende = 2, gelegt = 1))
        add("standard" to facts(GameType.FARBSOLO, c, announcement = Announcement.TOUT, level = ResultLevel.SCHWARZ, laufende = 4))
        add("standard" to facts(GameType.FARBSOLO, d, announcement = Announcement.SIE))
        add("standard" to facts(GameType.FARBSOLO, d, announcement = Announcement.SIE, won = false))
        add("standard" to facts(GameType.RUFSPIEL, a, b, announcement = Announcement.TOUT))
        add("standard" to facts(GameType.WENZ, a, announcement = Announcement.SIE))
        add("standard" to facts(GameType.UEBER_KREUZ, a, c, won = false, level = ResultLevel.SCHNEIDER, laufende = 4, gelegt = 1))
        add("alt" to facts(GameType.UEBER_KREUZ, b, d, laufende = 2))
        add("leere-tabellen" to facts(GameType.GEIER, a, laufende = 2, kontra = KontraLevel.RETOUR))
        add("tout-vierfach" to facts(GameType.FARBSOLO, b, announcement = Announcement.SIE))
        add("grenze-solo" to facts(GameType.FARBSOLO, a))
        add("grenze-solo" to facts(GameType.FARBSOLO, a, won = false))
        add("ueber-grenze-solo" to facts(GameType.FARBSOLO, a))
        add("aufheben" to facts(GameType.RUFSPIEL, a, b, laufende = 3000))
        add("negativ" to facts(GameType.WENZ, b))
        add("negativ" to facts(GameType.RUFSPIEL, a, b, level = ResultLevel.SCHWARZ))
        add("faktoren-negativ" to facts(GameType.WENZ, a, announcement = Announcement.TOUT))
        add("faktoren-negativ" to facts(GameType.FARBSOLO, a, announcement = Announcement.SIE))
        // Das Produkt aller Faktoren (totalFactor) läuft in Kotlin als Int über; der Spielwert bleibt am Rand stehen.
        add(
            "faktoren-riesig" to
                facts(GameType.WENZ, a, announcement = Announcement.TOUT, gelegt = 4, kontra = KontraLevel.RETOUR, bockExponent = 1),
        )
        add(
            "faktoren-riesig" to
                facts(GameType.FARBSOLO, a, announcement = Announcement.SIE, gelegt = 3, kontra = KontraLevel.KONTRA, bockExponent = 2),
        )
        for (rules in listOf("riesig", "kleinst")) {
            for (level in ResultLevel.entries) add(rules to facts(GameType.RUFSPIEL, a, b, level = level, laufende = 8))
            add(rules to facts(GameType.FARBSOLO, a, announcement = Announcement.SIE))
        }
        for (laufende in listOf(Int.MIN_VALUE, -1, 2, 3, 8, 9, 14, 15, Int.MAX_VALUE)) {
            add("standard" to facts(GameType.RUFSPIEL, a, b, laufende = laufende))
            add("sauspiel" to facts(GameType.WENZ, c, laufende = laufende))
        }
        for (gelegt in listOf(Int.MIN_VALUE, -1, 0, 1, 4, 5, Int.MAX_VALUE)) {
            add("standard" to facts(GameType.GEIER, d, gelegt = gelegt))
            add("ohne-zusaetze" to facts(GameType.RUFSPIEL, a, c, gelegt = gelegt, kontra = KontraLevel.KONTRA))
        }
        val exponents = listOf(Int.MIN_VALUE, -5, -1, 0, 1, 2, 3, 26, 27, 28, 31, 32, 33, 40, 64, 101, 1_000, Int.MAX_VALUE)
        for (exponent in exponents) {
            val bockRules = listOf(
                "standard", "bock-faktor-0", "bock-faktor-minus-1", "bock-faktor-minus-2", "bock-faktor-riesig", "bock-faktor-kleinst",
            )
            for (rules in bockRules) {
                add(rules to facts(GameType.RUFSPIEL, a, b, bockExponent = exponent))
            }
        }
        // Falsche Sitzordnungen: leer, drei, fünf, doppelt besetzt; Ansager oder Mitspieler nicht am Tisch.
        val seatings = listOf(emptyList(), listOf(a, b, c), listOf(a, b, c, d, e), listOf(a, a, b, c), listOf(a, b, c, c), listOf(b, a, d, c))
        for (players in seatings) {
            add("standard" to facts(GameType.RUFSPIEL, a, b, players = players))
            add("standard" to facts(GameType.RUFSPIEL, a, b, won = false, players = players))
            add("topf" to facts(GameType.WENZ, c, players = players))
            add("topf" to facts(GameType.WENZ, c, won = false, players = players))
            add("punkte" to facts(GameType.UEBER_KREUZ, b, d, players = players))
        }
        add("standard" to facts(GameType.RUFSPIEL, x, b))
        add("standard" to facts(GameType.RUFSPIEL, a, x))
        add("standard" to facts(GameType.RUFSPIEL, a, a))
        add("standard" to facts(GameType.RUFSPIEL, a, null))
        add("standard" to facts(GameType.FARBSOLO, a, b))
        add("standard" to facts(GameType.FARBSOLO, PlayerId(""), players = listOf(a, b, PlayerId(""), d)))
        add("nur-rufspiel" to facts(GameType.WENZ, a))
        add("nur-soli" to facts(GameType.UEBER_KREUZ, a, c))
        add("ohne-retour" to facts(GameType.RUFSPIEL, a, b, kontra = KontraLevel.RETOUR))
    }

    /** Gültige Spiele aller Art, wie sie am Tisch vorkommen; dazu ab und zu ein Regelverstoß. */
    private fun Random.usualFacts(): GameFacts {
        val type = GameType.entries.random(this)
        val declarer = table.random(this)
        val announcement = when {
            type == GameType.FARBSOLO && nextInt(12) == 0 -> Announcement.SIE
            type.isSolo && nextInt(6) == 0 -> Announcement.TOUT
            nextInt(40) == 0 -> Announcement.entries.random(this)
            else -> Announcement.NONE
        }
        return GameFacts(
            type = type,
            players = table,
            declarer = declarer,
            partner = if (type.isSolo) null else (table - declarer).random(this),
            declarerWon = announcement == Announcement.SIE || nextBoolean(),
            level = ResultLevel.entries.random(this),
            laufende = nextInt(0, 10),
            announcement = announcement,
            klopferCount = nextInt(0, 5),
            kontra = KontraLevel.entries.random(this),
            bockExponent = nextInt(0, 4),
        )
    }

    /** Beliebiger Unsinn, wie ihn eine beschädigte Datei liefern kann. */
    private fun Random.brokenFacts(): GameFacts {
        val type = GameType.entries.random(this)
        return GameFacts(
            type = type,
            players = if (nextInt(3) == 0) List(nextInt(0, 7)) { pool.random(this) } else table,
            declarer = if (nextInt(5) == 0) pool.random(this) else table.random(this),
            partner = if (nextInt(4) == 0) null else pool.random(this),
            declarerWon = nextBoolean(),
            level = ResultLevel.entries.random(this),
            laufende = sometimes(nextInt(0, 9)),
            announcement = if (nextInt(4) == 0) Announcement.entries.random(this) else Announcement.NONE,
            klopferCount = sometimes(nextInt(0, 5)),
            kontra = KontraLevel.entries.random(this),
            bockExponent = listOf(-5, -1, 0, 0, 0, 1, 1, 2, 3, 5, 30, 31, 32, 33, 64, 200).random(this),
        )
    }

    fun scoreCases(): List<ScoreCase> {
        val random = Random(20261117)
        val names = rules.keys.toList()
        val usualRules = listOf("standard", "sauspiel", "topf", "sauspiel-topf", "alt", "tout-vierfach", "punkte", "ohne-zusaetze")
        val all = chosenScoreCases() +
            List(380) { usualRules.random(random) to random.usualFacts() } +
            List(260) { names.random(random) to random.brokenFacts() }
        return all.mapIndexed { index, (rulesName, facts) -> ScoreCase("spiel-${pad(index + 1, 4)}", rulesName, facts) }
    }

    // ---------------------------------------------------------------- Verteilung

    fun payoutCases(): List<PayoutCase> {
        val random = Random(20261118)
        val anyone = listOf(a, b, c, d, e, x)
        val values = listOf(Int.MIN_VALUE, -1_000_000, -51, -1, 0, 1, 2, 5, 7, 10, 50, 715_827_882, 715_827_883, 1_073_741_824, Int.MAX_VALUE)
        val chosen = listOf(
            Triple(10, listOf(a, b), listOf(c, d)),
            Triple(50, listOf(a), listOf(b, c, d)),
            Triple(50, listOf(b, c, d), listOf(a)),
            Triple(100, listOf(c), listOf(a, b, d)),
            Triple(5, listOf(a, b), listOf(c, d, e)),
            Triple(5, listOf(c, d, e), listOf(a, b)),
            Triple(10, listOf(a, b), listOf(c)),
            Triple(10, listOf(a, a, b), listOf(c)),
            Triple(10, listOf(a, b), listOf(b, c)),
            Triple(10, emptyList(), listOf(a, b)),
            Triple(10, listOf(a, b), emptyList()),
            Triple(0, emptyList(), emptyList()),
            Triple(Int.MAX_VALUE, listOf(a), listOf(b, c, d)),
            Triple(715_827_882, listOf(a), listOf(b, c, d)),
        )
        val randomCases = List(400) {
            // Mal sauber getrennt, mal mit doppelten oder auf beiden Seiten genannten Spielern.
            val value = if (random.nextBoolean()) values.random(random) else random.nextInt(0, 10_000)
            if (random.nextBoolean()) {
                val shuffled = anyone.shuffled(random)
                val winners = shuffled.take(random.nextInt(0, 4))
                Triple(value, winners, shuffled.drop(winners.size).take(random.nextInt(0, 4)))
            } else {
                Triple(value, List(random.nextInt(0, 5)) { anyone.random(random) }, List(random.nextInt(0, 5)) { anyone.random(random) })
            }
        }
        return (chosen + randomCases).mapIndexed { index, (value, winners, losers) ->
            PayoutCase("verteilung-${pad(index + 1, 4)}", value, winners, losers)
        }
    }

    // ---------------------------------------------------------------- Salden

    fun balancesCases(): List<BalancesCase> {
        val random = Random(20261119)
        val chosen = listOf(
            emptyMap(),
            mapOf(a to 0),
            mapOf(a to -100, b to 60),
            mapOf(a to -10, b to -20),
            mapOf(a to 10, b to 20),
            mapOf(a to Int.MIN_VALUE, b to 5),
            mapOf(a to Int.MIN_VALUE, b to Int.MAX_VALUE, c to 1),
            mapOf(a to Int.MIN_VALUE, b to Int.MIN_VALUE),
            mapOf(a to Int.MAX_VALUE, b to Int.MAX_VALUE, c to 2),
            mapOf(a to Int.MAX_VALUE, b to -Int.MAX_VALUE),
            mapOf(a to 30, b to 30, c to -30, d to -30),
            mapOf(a to -30, b to -30, c to 30, d to 30),
            mapOf(a to 920, b to -1340, c to 1140, d to -720),
        )
        val players = listOf(a, b, c, d, e, x, PlayerId(""), PlayerId("10"), PlayerId("9"))
        val randomCases = List(150) {
            val seats = players.shuffled(random).take(random.nextInt(0, 8))
            seats.associateWith { if (random.nextInt(3) == 0) extremes.random(random) else random.nextInt(-5_000, 5_000) }
        }
        return (chosen + randomCases).mapIndexed { index, balances -> BalancesCase("salden-${pad(index + 1, 4)}", balances) }
    }

    // ---------------------------------------------------------------- Abende

    private var nextEntry = 0

    private fun entryId() = EntryId("g${nextEntry++}")

    private fun game(
        type: GameType,
        declarer: PlayerId,
        partner: PlayerId? = null,
        won: Boolean = true,
        level: ResultLevel = ResultLevel.NORMAL,
        laufende: Int = 0,
        announcement: Announcement = Announcement.NONE,
        klopfer: Set<PlayerId> = emptySet(),
        kontra: KontraLevel = KontraLevel.NONE,
        bockOverride: BockOverride = BockOverride.AUTO,
    ) = GameEntry(
        entryId(), now, type, declarer = declarer, partner = partner, declarerWon = won, level = level,
        laufende = laufende, announcement = announcement, klopfer = klopfer, kontra = kontra, bockOverride = bockOverride,
    )

    private fun rufspiel(declarer: PlayerId = a, partner: PlayerId? = b, won: Boolean = true, klopfer: Set<PlayerId> = emptySet()) =
        game(GameType.RUFSPIEL, declarer, partner, won, klopfer = klopfer)

    private fun solo(declarer: PlayerId, type: GameType = GameType.FARBSOLO, won: Boolean = true) = game(type, declarer, won = won)

    private fun thrownIn() = ThrownInEntry(entryId(), now)

    private fun sixNixer(player: PlayerId? = null, thrownIn: Boolean = true) =
        BockTriggerEntry(entryId(), now, BockReason.SECHS_NIXER, player = player, handThrownIn = thrownIn)

    private fun manual(vararg amounts: Pair<PlayerId, Int>) = ManualEntry(entryId(), now, mapOf(*amounts), "frei")

    private fun evening(
        id: String,
        entries: List<EveningEntry>,
        seating: List<PlayerId> = table,
        firstDealer: PlayerId = a,
        rules: RuleSet = standard,
        startedAt: Instant = now,
    ) = Evening(EveningId(id), startedAt = startedAt, seating = seating, firstDealer = firstDealer, rules = rules, entries = entries)

    /** Abende aus beschädigten Daten und an den Rändern des Zahlenbereichs, jeder mit einem Grund im Namen. */
    private fun brokenEvenings(): List<Evening> = listOf(
        evening(
            "leere-sitzordnung",
            listOf(rufspiel(), thrownIn(), sixNixer(), manual(a to 10, b to -10), solo(c)),
            seating = emptyList(),
            firstDealer = c,
        ),
        evening("leere-sitzordnung-ohne-eintraege", emptyList(), seating = emptyList(), firstDealer = b),
        evening("doppelter-platz", listOf(rufspiel(), solo(c), manual(a to 5, b to -5), thrownIn()), seating = listOf(a, a, b, c)),
        evening("drei-am-tisch", listOf(rufspiel(), solo(c), solo(a, won = false), thrownIn(), rufspiel(c, a)), seating = listOf(a, b, c)),
        evening(
            "fuenf-am-tisch",
            listOf(rufspiel(), solo(e, GameType.WENZ), rufspiel(c, e, won = false), sixNixer(), rufspiel(d, a)),
            seating = listOf(a, b, c, d, e),
            rules = standard.copy(prices = mapOf(GameType.RUFSPIEL to 5)),
        ),
        evening("erster-geber-fehlt", listOf(rufspiel(), thrownIn(), rufspiel(c, d)), firstDealer = x),
        evening(
            "unbekannte-spieler",
            listOf(
                solo(x), rufspiel(a, null), rufspiel(a, x), rufspiel(a, a), rufspiel(a, b, klopfer = setOf(x)),
                rufspiel(c, d, klopfer = setOf(c, PlayerId(""))), manual(a to 100, x to -100), manual(a to 30, b to -20),
                sixNixer(player = x), rufspiel(),
            ),
        ),
        evening(
            "freie-buchungen-am-rand",
            listOf(
                manual(a to Int.MIN_VALUE, b to Int.MIN_VALUE), manual(a to Int.MAX_VALUE, b to Int.MAX_VALUE, c to 2),
                manual(c to Int.MAX_VALUE, d to Int.MIN_VALUE), rufspiel(c, d), manual(), manual(a to 0),
            ),
        ),
        evening(
            "salden-am-rand",
            listOf(
                manual(a to 2_000_000_000, b to -2_000_000_000), manual(a to 2_000_000_000, b to -2_000_000_000), rufspiel(c, d),
                manual(a to 147_483_637, b to -147_483_637), rufspiel(a, c), rufspiel(b, d), rufspiel(b, d, won = false),
                rufspiel(a, c),
            ),
        ),
        evening(
            "saldo-sprengt",
            List(26) { sixNixer(thrownIn = false) } + List(4) { rufspiel() },
            rules = standard.copy(bock = bock(stacking = BockStacking.STACK)),
        ),
        evening(
            "vierzig-bockrunden",
            List(40) { sixNixer(thrownIn = false) } + rufspiel() + game(GameType.WENZ, b, bockOverride = BockOverride.FORCE_OFF),
            rules = standard.copy(bock = bock(stacking = BockStacking.STACK)),
        ),
        evening(
            "lange-bockrunde",
            listOf(sixNixer(thrownIn = false), sixNixer(thrownIn = false), rufspiel(), rufspiel(), thrownIn()),
            rules = standard.copy(bock = bock(gamesPerTrigger = Int.MAX_VALUE, thrownInConsumesBockGame = true)),
        ),
        // Statistik mit Überlauf: Kein Saldo verlässt den Zahlenbereich, aber Int-Summen der Statistik laufen über wie
        // in Kotlin – A gewinnt zweimal je 2,1 Mrd. mit dem Farbsolo, das Gespann C/D viermal je 0,7 Mrd. (A/B verliert
        // als Gegenspielerpaar viermal so viel und steht danach scheinbar im Plus), der Umsatz liegt bei 8,4 Mrd.
        // Die freie Buchung am Ende würde den Saldo von A sprengen.
        evening(
            "statistik-ueberlauf",
            listOf(
                game(GameType.FARBSOLO, a, laufende = 3),
                rufspiel(c, d), rufspiel(c, d), rufspiel(c, d), rufspiel(c, d),
                rufspiel(b, c), rufspiel(b, d),
                game(GameType.FARBSOLO, a, laufende = 3),
                game(GameType.WENZ, b, klopfer = setOf(b, c)),
                manual(a to 1_000_000_000, b to -1_000_000_000),
            ),
            rules = standard.copy(
                prices = mapOf(GameType.FARBSOLO to 700_000_000, GameType.RUFSPIEL to 350_000_000, GameType.WENZ to 1),
            ),
        ),
        evening(
            "topf-rest-cent",
            listOf(
                game(GameType.WENZ, a, laufende = 2, klopfer = setOf(a, c)), game(GameType.GEIER, b, won = false, klopfer = setOf(b)),
                game(GameType.FARBSOLO, c, announcement = Announcement.TOUT, klopfer = setOf(d)),
                game(GameType.FARBSOLO, d, announcement = Announcement.SIE, klopfer = setOf(a, b, c, d)),
            ),
            rules = standard.copy(
                unit = 7,
                prices = GameType.entries.associateWith { if (it.isSolo) 11 else 5 },
                payoutRule = PayoutRule.POT_SPLIT,
            ),
        ),
    )

    /** Gültige Abende, die Schwellen der Statistik genau treffen (Mauerblümchen, Abzeichen). */
    private fun boundaryEvenings(): List<Evening> = listOf(
        // C ist Solo-Spezialist (zwei Soli). A sagt fünfmal an, davon ein Solo: genau 20 %, also das Mauerblümchen
        // (B mit 25 % liegt darüber). A legt viermal, gewinnt zweimal, netto genau 0: Wackelkandidat.
        evening(
            "grenze-mauerbluemchen-20",
            listOf(
                solo(c, GameType.WENZ), solo(c, GameType.WENZ, won = false), solo(a),
                rufspiel(a, b, klopfer = setOf(a)), rufspiel(a, c, won = false, klopfer = setOf(a)),
                rufspiel(a, d, klopfer = setOf(a)), rufspiel(a, b, won = false, klopfer = setOf(a)),
                solo(b, GameType.GEIER), rufspiel(b, c), rufspiel(b, d), rufspiel(b, a, won = false),
            ),
        ),
        // Gleicher Solo-Anteil (0 %) bei A (drei Ansagen) und B (fünf): Mauerblümchen ist der mit mehr Ansagen.
        // C legt zweimal (noch offen), D dreimal mit einem Sieg (33 %, im Minus: Brandstifter).
        evening(
            "grenze-mauerbluemchen-gleichstand",
            listOf(
                solo(c, GameType.GEIER), solo(c, GameType.GEIER, won = false),
                rufspiel(a, b), rufspiel(a, d, won = false), rufspiel(a, b),
                rufspiel(b, c, klopfer = setOf(c)), rufspiel(b, d, klopfer = setOf(d)), rufspiel(b, a, won = false),
                rufspiel(b, c, klopfer = setOf(c, d)), rufspiel(b, d),
                solo(d, GameType.WENZ, won = false), rufspiel(d, a, won = false, klopfer = setOf(d)), rufspiel(d, b),
                rufspiel(d, c), rufspiel(d, a), rufspiel(d, b, won = false),
            ),
            firstDealer = c,
        ),
        // Völliger Gleichstand beim Mauerblümchen (A und B je drei Rufspiele): Es ist der, der in der Sitzordnung
        // (D, B, A, C) weiter vorn steht, also B.
        evening(
            "grenze-mauerbluemchen-reihenfolge",
            listOf(solo(c), rufspiel(a, c), rufspiel(b, d), rufspiel(a, d, won = false), rufspiel(b, c), rufspiel(a, c), rufspiel(b, a)),
            seating = listOf(d, b, a, c),
            firstDealer = d,
        ),
    )

    /** Ein zufälliger Abend mit beschädigten Daten: Sitzordnung, Einträge und Regelwerk. */
    private fun Random.brokenEvening(id: String): Evening {
        fun player(): PlayerId = if (nextInt(6) == 0) pool.random(this) else table.random(this)
        fun brokenEntry(): EveningEntry = when (nextInt(12)) {
            0 -> thrownIn()
            1, 2 -> BockTriggerEntry(
                entryId(), now, BockReason.entries.random(this),
                player = player().takeIf { nextBoolean() }, handThrownIn = nextBoolean(),
            )
            3 -> {
                val amount = if (nextInt(3) == 0) extremes.random(this) else nextInt(-500, 500)
                val (from, to) = pool.shuffled(this).take(2)
                if (amount == Int.MIN_VALUE) {
                    manual(from to amount, to to Int.MAX_VALUE, player() to 1)
                } else {
                    manual(from to amount, to to -amount)
                }
            }
            4 -> ManualEntry(entryId(), now, List(nextInt(0, 5)) { player() }.associateWith { anyInt() }, "Unsinn")
            else -> {
                val type = GameType.entries.random(this)
                val declarer = player()
                GameEntry(
                    id = entryId(),
                    recordedAt = now,
                    type = type,
                    suit = Suit.entries.random(this).takeIf { nextInt(3) == 0 },
                    declarer = declarer,
                    partner = when {
                        nextInt(8) == 0 -> player()
                        type.isSolo -> null
                        nextInt(12) == 0 -> null
                        else -> (table - declarer).random(this)
                    },
                    declarerWon = nextBoolean(),
                    level = ResultLevel.entries.random(this),
                    laufende = if (nextInt(8) == 0) anyInt() else nextInt(0, 9),
                    announcement = if (nextInt(8) == 0) Announcement.entries.random(this) else Announcement.NONE,
                    klopfer = pool.filter { nextInt(5) == 0 }.toSet(),
                    kontra = KontraLevel.entries.random(this),
                    kontraBy = player().takeIf { nextInt(4) == 0 },
                    retourBy = player().takeIf { nextInt(4) == 0 },
                    bockOverride = BockOverride.entries.random(this),
                    declarerEyes = nextInt(-1, 121).takeIf { nextInt(4) == 0 },
                    note = if (nextInt(6) == 0) "Notiz „$id“" else "",
                )
            }
        }
        return evening(
            id = id,
            entries = List(nextInt(0, 30)) { brokenEntry() },
            seating = when (nextInt(5)) {
                0 -> List(nextInt(0, 7)) { pool.random(this) }
                1 -> pool.shuffled(this).take(nextInt(0, 7))
                else -> table.shuffled(this)
            },
            firstDealer = player(),
            rules = if (nextInt(3) == 0) standard.copy(payoutRule = PayoutRule.entries.random(this)) else brokenRules("Kaputt $id"),
        )
    }

    /** Rufspiel, Über Kreuz und Soli mit allen Zusätzen auf einem gültigen Tisch: der vorhandene Testgenerator. */
    private fun randomEvening(
        random: Random,
        id: String,
        seating: List<PlayerId>,
        rules: RuleSet,
        startedAt: Instant,
        maxEntries: Int,
    ): Evening {
        val generated = RandomEvenings.evening(random, id, seating, startedAt)
        return generated.copy(
            rules = rules,
            firstDealer = seating.random(random),
            finishedAt = startedAt.plusSeconds(4 * 3600).takeIf { random.nextBoolean() },
            location = if (random.nextBoolean()) "Beim Wirt" else "",
            entries = generated.entries.take(maxEntries),
        )
    }

    /** Die Regelwerke, mit denen die Zufallsabende gerechnet werden. */
    private val eveningRules = listOf(
        "standard", "sauspiel", "topf", "sauspiel-topf", "ohne-zusaetze", "ohne-retour", "alt", "tout-vierfach", "punkte",
        "bock-stapeln", "bock-ignorieren", "bock-aus", "bock-null-spiele", "bock-zwei-spiele", "bock-riesig",
        "bock-riesig-stapeln", "bock-negativ", "zusammengeworfen-zaehlt", "geber-bleibt", "bock-faktor-0",
        "bock-faktor-minus-1", "grenze-solo",
    )

    /** Zeitpunkte für die Abende der „ewigen Tabelle“: manche gleich, manche mit Bruchteilen, einer vor dem Jahr 0. */
    private val historyStarts = listOf(
        "2026-11-14T19:00:00Z", "2026-11-14T19:00:00Z", "2026-11-14T19:00:00.500Z", "2026-11-14T19:00:00.000000001Z",
        "2026-11-07T18:30:00Z", "2025-12-31T23:59:59.999999999Z", "+10000-01-01T00:00:00Z", "-0001-06-01T12:00:00Z",
        "2026-11-21T18:30:00Z", "2026-11-14T18:59:59.999Z",
    ).map(Instant::parse)

    /** Kennungen der Abende der „ewigen Tabelle“ in einer Form, die nach Text sortiert anders steht als nach Zahl. */
    private fun historyEveningId(number: Int): String = when (number % 3) {
        0 -> "abend-$number"
        1 -> "Abend-${pad(number)}"
        else -> "abend-${pad(number, 3)}"
    }

    /**
     * Die Abende der „ewigen Tabelle“: wechselnde Besetzung aus sechs Spielern, verschiedene Regelwerke,
     * gleiche Startzeiten, Abende ohne Spiel mit freien Buchungen, zwei Fassungen unter einer Kennung und Abende, deren
     * Salden zusammengezählt den Int-Bereich verlassen.
     */
    private fun historyEvenings(): List<Pair<String, Evening>> {
        val random = Random(20261120)
        val generated = List(22) { index ->
            val number = index + 1
            val seating = historyPool.shuffled(random).take(4)
            val rules = rules.getValue(eveningRules.random(random))
            "tabelle-${pad(number)}" to
                randomEvening(random, historyEveningId(number), seating, rules, historyStarts.random(random), maxEntries = 18)
        }
        val (p1, p2, p3, p4, p5) = historyPool
        val p6 = historyPool[5]
        val special = listOf(
            // Ein alter Kontostand als Abend mit einer einzigen freien Buchung.
            "tabelle-uebertrag" to Evening(
                EveningId("uebertrag"), startedAt = Instant.parse("2020-01-01T00:00:00Z"), seating = listOf(p1, p2, p3, p4),
                firstDealer = p1, rules = standard,
                entries = listOf(
                    ManualEntry(EntryId("u1"), Instant.parse("2020-01-01T00:00:00Z"), mapOf(p1 to 500, p2 to -200, p3 to -300), "Übertrag"),
                ),
            ),
            // Ein Abend ohne Spiel, nur mit Zusammengeworfen, Bockrunde und einer Wette am Ende der Liste.
            "tabelle-ohne-spiel" to Evening(
                EveningId("ohne-spiel"), startedAt = Instant.parse("2030-01-01T00:00:00Z"), seating = listOf(p5, p6, p1, p2),
                firstDealer = p6, rules = sauspiel,
                entries = listOf(
                    ThrownInEntry(EntryId("o1"), now),
                    BockTriggerEntry(EntryId("o2"), now, BockReason.SECHS_NIXER, player = p5),
                    ManualEntry(EntryId("o3"), now, mapOf(p6 to 70, p5 to -70), "Wette"),
                ),
            ),
            // Zwei Fassungen unter derselben Kennung: Es zählt die zuerst übergebene.
            "tabelle-fassung-1" to evening(
                "zweimal", listOf(rufspiel(p1, p2), game(GameType.WENZ, p3, won = false)), seating = listOf(p1, p2, p3, p4),
                firstDealer = p1, startedAt = Instant.parse("2026-11-14T19:00:00Z"),
            ),
            "tabelle-fassung-2" to evening(
                "zweimal", listOf(rufspiel(p3, p4, klopfer = setOf(p1)), solo(p2)), seating = listOf(p1, p2, p3, p4),
                firstDealer = p2, startedAt = Instant.parse("2026-11-14T19:00:00Z"),
            ),
            // Zwei Abende, an denen derselbe Spieler je 2 Mrd. gewinnt: Zusammen läuft sein Saldo (als Int) über.
            "tabelle-reich-1" to evening(
                "reich-1", listOf(manual(p5 to 2_000_000_000, p6 to -2_000_000_000), rufspiel(p5, p6)), seating = listOf(p5, p6, p3, p4),
                firstDealer = p5, startedAt = Instant.parse("2026-12-01T19:00:00Z"),
            ),
            "tabelle-reich-2" to evening(
                "reich-2", listOf(rufspiel(p5, p3), manual(p5 to 2_000_000_000, p4 to -2_000_000_000)), seating = listOf(p3, p5, p4, p6),
                firstDealer = p3, startedAt = Instant.parse("2026-12-08T19:00:00Z"),
            ),
            // Beschädigt: ein doppelt besetzter Platz.
            "tabelle-doppelt-besetzt" to evening(
                "doppelt-besetzt", listOf(rufspiel(p1, p2), manual(p1 to 15, p2 to -15)), seating = listOf(p1, p1, p2, p3),
                firstDealer = p1, startedAt = Instant.parse("2026-11-14T19:00:00Z"),
            ),
        )
        return generated + special
    }

    /** Alle Abende: die von Hand gerechneten, Zufallsabende, beschädigte Abende und die der „ewigen Tabelle“. */
    fun eveningCases(): List<EveningCase> {
        nextEntry = 0
        val handCalculated = listOf(
            "sample-1" to SampleEvening.evening,
            "sample-2" to SampleEvening.secondEvening,
            "runde-1" to RoundEvenings.evening,
            "runde-2" to RoundEvenings.secondEvening,
        )
        val random = Random(20261121)
        val randomValid = eveningRules.mapIndexed { index, rulesName ->
            val number = index + 1
            "zufall-${pad(number)}" to
                randomEvening(random, "zufall-$number", table.shuffled(random), rules.getValue(rulesName), now, maxEntries = 40)
        }
        val broken = boundaryEvenings().map { it.id.value to it } +
            brokenEvenings().map { "kaputt-${it.id.value}" to it } +
            List(10) { index -> "unsinn-${pad(index + 1)}" to random.brokenEvening("unsinn-${index + 1}") }
        return (handCalculated + randomValid + broken + historyEvenings()).map { (id, evening) -> EveningCase(id, evening) }
    }

    // ---------------------------------------------------------------- Ewige Tabelle

    fun historyCases(evenings: List<EveningCase>): List<HistoryCase> {
        val random = Random(20261122)
        val pool = evenings.map { it.id }.filter { it.startsWith("tabelle-") }
        val chosen = listOf(
            emptyList(),
            listOf("tabelle-01"),
            listOf("tabelle-uebertrag"),
            listOf("tabelle-ohne-spiel", "tabelle-02", "tabelle-03"),
            listOf("tabelle-fassung-1", "tabelle-fassung-2", "tabelle-04"),
            listOf("tabelle-fassung-2", "tabelle-05", "tabelle-fassung-1"),
            listOf("tabelle-reich-1", "tabelle-reich-2"),
            listOf("tabelle-reich-2", "tabelle-reich-1", "tabelle-reich-2", "tabelle-06"),
            listOf("tabelle-07", "tabelle-07", "tabelle-doppelt-besetzt", "tabelle-08"),
            listOf("runde-1", "runde-2"),
            listOf("sample-2", "sample-1", "sample-1"),
            listOf("runde-2", "sample-1", "runde-1", "sample-2"),
        )
        val randomGroups = List(28) {
            List(random.nextInt(1, 7)) { pool.random(random) }
        }
        return (chosen + randomGroups).mapIndexed { index, group -> HistoryCase("ewig-${pad(index + 1)}", group) }
    }
}

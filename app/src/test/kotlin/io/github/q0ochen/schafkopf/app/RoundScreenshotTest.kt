package io.github.q0ochen.schafkopf.app

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.Density
import androidx.test.core.app.ApplicationProvider
import com.github.takahirom.roborazzi.captureRoboImage
import io.github.q0ochen.schafkopf.app.round.RoundCoordinator
import io.github.q0ochen.schafkopf.app.round.RoundMessage
import io.github.q0ochen.schafkopf.app.round.RoundProblem
import io.github.q0ochen.schafkopf.app.round.RoundState
import io.github.q0ochen.schafkopf.app.round.RoundTestKit
import io.github.q0ochen.schafkopf.app.ui.board.BoardScreen
import io.github.q0ochen.schafkopf.app.ui.navigation.Navigator
import io.github.q0ochen.schafkopf.app.ui.round.Farewell
import io.github.q0ochen.schafkopf.app.ui.round.PendingAttach
import io.github.q0ochen.schafkopf.app.ui.round.RoundActions
import io.github.q0ochen.schafkopf.app.ui.round.RoundContent
import io.github.q0ochen.schafkopf.app.ui.round.RoundUi
import io.github.q0ochen.schafkopf.app.ui.round.RoundUiError
import io.github.q0ochen.schafkopf.app.ui.round.SwitchRequest
import io.github.q0ochen.schafkopf.domain.round.SwitchFrom
import io.github.q0ochen.schafkopf.app.ui.settings.PlayerEditFields
import io.github.q0ochen.schafkopf.app.ui.settings.PlayerRow
import io.github.q0ochen.schafkopf.app.ui.settings.SettingsScreen
import io.github.q0ochen.schafkopf.app.ui.start.StartScreen
import io.github.q0ochen.schafkopf.app.ui.theme.SchafkopfTheme
import io.github.q0ochen.schafkopf.app.ui.waitFor
import io.github.q0ochen.schafkopf.app.ui.waitForText
import io.github.q0ochen.schafkopf.data.round.Account
import io.github.q0ochen.schafkopf.domain.evening.Evening
import io.github.q0ochen.schafkopf.domain.evening.EveningCalculator
import io.github.q0ochen.schafkopf.domain.evening.GameEntry
import io.github.q0ochen.schafkopf.domain.model.EntryId
import io.github.q0ochen.schafkopf.domain.model.EveningId
import io.github.q0ochen.schafkopf.domain.model.Player
import io.github.q0ochen.schafkopf.domain.model.PlayerId
import io.github.q0ochen.schafkopf.domain.model.ThemeMode
import io.github.q0ochen.schafkopf.domain.ports.EveningRepository
import io.github.q0ochen.schafkopf.domain.ports.PlayerRepository
import io.github.q0ochen.schafkopf.domain.ports.SettingsRepository
import io.github.q0ochen.schafkopf.domain.round.InviteInfo
import io.github.q0ochen.schafkopf.domain.round.RemotePlayer
import io.github.q0ochen.schafkopf.domain.round.RoundError
import io.github.q0ochen.schafkopf.domain.round.RoundId
import io.github.q0ochen.schafkopf.domain.round.RoundLink
import io.github.q0ochen.schafkopf.domain.round.RoundMember
import io.github.q0ochen.schafkopf.domain.round.RoundSummary
import io.github.q0ochen.schafkopf.domain.round.SyncNotice
import io.github.q0ochen.schafkopf.domain.rules.GameType
import io.github.q0ochen.schafkopf.domain.rules.RuleSet
import io.github.q0ochen.schafkopf.domain.rules.Suit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.GlobalContext
import org.koin.core.context.stopKoin
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.Instant

/**
 * Die gemeinsame Runde auf dem Bildschirm: zeichnet jede Lage des Bildschirms „Gemeinsame Runde“ und die Hinweise im
 * Abend als PNG nach `app/build/outputs/roborazzi` und prüft dabei, was zu sehen und zu bedienen ist.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(
    sdk = [36],
    qualifiers = "w411dp-h914dp-normal-long-notround-any-420dpi-keyshidden-nonav",
    application = TestSchafkopfApp::class,
)
class RoundScreenshotTest {

    @get:Rule
    val compose = createComposeRule()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val ids = listOf("sepp", "hans", "kathi", "vroni").map(::PlayerId)
    private val players = listOf("Sepp", "Hans", "Kathi", "Vroni").mapIndexed { index, name -> Player(ids[index], name, colorIndex = index) }
    private val round = RoundId("runde-1")
    private val account = Account("konto-1", "ich@example.org")

    private val link = RoundLink(
        roundId = round,
        roundName = "Schafkopfrunde",
        userId = "konto-1",
        playerId = ids[0],
        isCreator = true,
        members = listOf(RoundMember("konto-1", ids[0]), RoundMember("konto-2", ids[2]), RoundMember("konto-3", null)),
    )
    private val linked = RoundState(
        configured = true,
        loaded = true,
        account = account,
        link = link,
        players = mapOf(
            ids[0] to RemotePlayer(ids[0], "Sepp", 0, archived = false, owner = "konto-1", nameLocked = true),
            ids[2] to RemotePlayer(ids[2], "Kathi", 2, archived = false, owner = "konto-2", nameLocked = true),
        ),
    )

    @After
    fun stop() {
        scope.cancel()
        stopKoin()
    }

    private fun show(themeMode: ThemeMode = ThemeMode.DARK, fontScale: Float = 1f, content: @Composable () -> Unit) {
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, fontScale)) {
                SchafkopfTheme(themeMode) {
                    Surface(modifier = Modifier.fillMaxSize()) { content() }
                }
            }
        }
        compose.waitForIdle()
    }

    private fun capture(name: String) {
        compose.waitForIdle()
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/$name.png")
    }

    private fun content(
        state: RoundState,
        ui: RoundUi = RoundUi(),
        actions: RoundActions = RoundActions(),
        themeMode: ThemeMode = ThemeMode.DARK,
        fontScale: Float = 1f,
    ) = show(themeMode, fontScale) { RoundContent(state, ui, players, actions) }

    // --- Der Bildschirm „Gemeinsame Runde“ in jeder Lage ---

    @Test
    fun ohne_server_steht_nur_der_hinweis() {
        content(RoundState(configured = false, loaded = true))

        compose.onNodeWithText("Der Server ist noch nicht eingerichtet.").assertExists()
        compose.onNodeWithText("Mit Google anmelden").assertDoesNotExist()
        capture("40_runde_nicht_eingerichtet")
    }

    @Test
    fun nicht_angemeldet_mit_einladung() {
        var signIns = 0
        content(
            RoundState(configured = true, loaded = true, pendingInvite = "0123456789abcdef0123456789abcdef"),
            actions = RoundActions(signIn = { signIns++ }),
        )

        compose.onNodeWithText("Du bist eingeladen. Melde dich an, um beizutreten.").assertExists()
        compose.onNodeWithText("Namen, Abende und Beträge der Runde liegen dafür auf einem Server in Frankfurt (Supabase). Zum Anmelden brauchst du ein Google-Konto.").assertExists()
        compose.onNodeWithText("Mit Google anmelden").performClick()
        assertEquals(1, signIns)
        capture("41_runde_anmelden")
    }

    @Test
    fun angemeldet_ohne_runde() {
        val created = ArrayList<String>()
        val redeemed = ArrayList<String>()
        var joined = 0
        content(
            RoundState(
                configured = true,
                loaded = true,
                account = account,
                pendingInvite = "0123456789abcdef0123456789abcdef",
                rounds = listOf(RoundSummary(RoundId("runde-2"), "Betriebsrunde", rev = 12, memberCount = 1, myPlayerId = null, isCreator = true)),
            ),
            RoundUi(invite = InviteInfo(round, "Schafkopfrunde", memberCount = 3, alreadyMember = false)),
            RoundActions(create = { created += it }, redeem = { redeemed += it }, joinInvited = { joined++ }),
            themeMode = ThemeMode.LIGHT,
        )

        compose.onNodeWithText("Angemeldet als ich@example.org").assertExists()
        compose.onNodeWithText("Einladung in die Runde Schafkopfrunde").assertExists()
        compose.onNodeWithText("3 Mitglieder").assertExists()
        compose.onNodeWithText("1 Mitglied").assertExists()
        capture("42_runde_angemeldet")

        compose.onAllNodesWithText("Beitreten")[0].performClick()
        assertEquals(1, joined)
        // Der Name der Runde ist vorbelegt; „Einlösen“ geht erst mit einem eingefügten Text.
        compose.onNodeWithText("Runde anlegen").performScrollTo().performClick()
        assertEquals(listOf("Schafkopfrunde"), created)
        compose.onAllNodesWithText("Beitreten")[1].performScrollTo().assertIsNotEnabled()
        compose.onNodeWithText("Link oder Code").performScrollTo().performTextInput("0123456789abcdef0123456789abcdef")
        compose.onAllNodesWithText("Beitreten")[1].performScrollTo().performClick()
        assertEquals(listOf("0123456789abcdef0123456789abcdef"), redeemed)
    }

    @Test
    fun rueckfrage_vor_dem_ersetzen() {
        var confirmed = 0
        content(
            RoundState(configured = true, loaded = true, account = account),
            RoundUi(replace = PendingAttach.Join("0123456789abcdef0123456789abcdef"), eveningCount = 7, autoBackup = true),
            RoundActions(confirmReplace = { confirmed++ }),
        )

        compose.onNodeWithText("Daten dieses Geräts ersetzen?").assertExists()
        compose.onNodeWithText(
            "Auf diesem Gerät liegen 7 Abende. Sie werden durch die Daten der Runde ersetzt. Sichere sie vorher, wenn du sie behalten willst.",
            substring = true,
        ).assertExists()
        // Die automatische Sicherung ist eingeschaltet: Auch sie bekommt danach die Daten der Runde.
        compose.onNodeWithText("Die automatische Sicherung wird danach mit den Daten der Runde überschrieben.", substring = true).assertExists()
        compose.onNodeWithText("Zur Sicherung").assertExists()
        capture("43_runde_ersetzen")
        compose.onNodeWithText("Ersetzen").performClick()
        assertEquals(1, confirmed)
    }

    @Test
    fun das_bin_ich() {
        val claims = ArrayList<Pair<PlayerId?, String>>()
        // Sepp gehört schon einem anderen Mitglied; die anderen drei und „Ich bin neu“ stehen zur Wahl.
        val state = linked.copy(
            link = link.copy(
                playerId = null,
                isCreator = false,
                members = listOf(RoundMember("konto-2", ids[0]), RoundMember("konto-1", null)),
            ),
            players = mapOf(ids[0] to RemotePlayer(ids[0], "Sepp", 0, archived = false, owner = "konto-2", nameLocked = true)),
        )
        content(state, actions = RoundActions(claim = { player, name -> claims += player to name }))

        compose.onNodeWithText("Wer bist du?").assertExists()
        // Sepp steht nur bei den Mitgliedern, nicht zur Wahl.
        compose.onAllNodesWithText("Sepp").assertCountEquals(1)
        compose.onNodeWithText("Noch ohne Namen (du)").assertExists()
        compose.onNodeWithText("Das bin ich").assertIsNotEnabled()
        compose.onNodeWithText("Hans").performClick()
        compose.onNodeWithText("Das bin ich").assertIsEnabled()
        capture("44_runde_das_bin_ich")

        compose.onNodeWithText("Das bin ich").performClick()
        compose.onNodeWithText("Als Hans festlegen?").assertExists()
        compose.onNodeWithText("Der Name lässt sich danach nicht mehr ändern.").assertExists()
        capture("45_runde_name_festlegen")
        compose.onNodeWithText("Festlegen").performClick()
        assertEquals(listOf<Pair<PlayerId?, String>>(ids[1] to "Hans"), claims)

        // Wer neu ist, wählt keinen Spieler und gibt seinen Namen ein.
        compose.onNodeWithText("Ich bin neu").performClick()
        compose.onNodeWithText("Das bin ich").performClick()
        compose.onNodeWithText("Festlegen").performClick()
        assertEquals(null to "Hans", claims.last())
    }

    private fun androidx.compose.ui.test.SemanticsNodeInteractionCollection.assertCountEquals(expected: Int) =
        assertEquals(expected, fetchSemanticsNodes().size)

    @Test
    fun verbunden_dunkel() {
        var invited = 0
        var synced = 0
        val state = linked.copy(
            notices = listOf(SyncNotice(PlayerId("gast"), "Hans", RoundError.NAME_TAKEN, nowName = "Hans 2")),
        )
        content(state, actions = RoundActions(invite = { invited++ }, syncNow = { synced++ }))

        compose.onNodeWithText("Schafkopfrunde").assertExists()
        compose.onNodeWithText("Abgeglichen").assertExists()
        compose.onNodeWithText("Sepp (du)").assertExists()
        compose.onNodeWithText("Kathi").assertExists()
        compose.onNodeWithText("Noch ohne Namen").assertExists()
        compose.onNodeWithText("Der Link gilt 14 Tage.").assertExists()
        compose.onNodeWithText("Den Namen Hans gibt es in der Runde schon. Der Spieler heißt jetzt Hans 2.").assertExists()
        capture("46_runde_verbunden_dunkel")
        compose.onNodeWithText("Einladen").performClick()
        compose.onNodeWithText("Jetzt abgleichen").performClick()
        assertEquals(1 to 1, invited to synced)
    }

    @Test
    fun verbunden_hell_mit_wartenden_aenderungen() {
        content(linked.copy(pending = 1), themeMode = ThemeMode.LIGHT)

        compose.onNodeWithText("1 Änderung wartet").assertExists()
        capture("47_runde_verbunden_hell")
    }

    @Test
    fun stoerungen_stehen_in_worten_da() {
        content(linked.copy(problem = RoundProblem.OFFLINE, pending = 2), RoundUi(error = RoundUiError.Server(RoundError.OFFLINE)))
        compose.onNodeWithText("Kein Netz – 2 Änderungen warten").assertExists()
        compose.onNodeWithText("Keine Verbindung. Bitte später noch einmal versuchen.").assertExists()
        capture("48_runde_ohne_netz")
    }

    @Test
    fun abgelaufene_anmeldung_bietet_das_anmelden_an() {
        content(linked.copy(problem = RoundProblem.SIGNED_OUT, message = RoundMessage.SIGN_IN_FAILED))

        compose.onNodeWithText("Anmeldung abgelaufen – bitte neu anmelden").assertExists()
        compose.onNodeWithText("Die Anmeldung hat nicht geklappt. Bitte noch einmal versuchen.").assertExists()
        compose.onNodeWithText("Mit Google anmelden").assertIsEnabled()
        // Ohne Anmeldung gibt es nichts abzugleichen und niemanden einzuladen.
        compose.onNodeWithText("Jetzt abgleichen").assertDoesNotExist()
        compose.onNodeWithText("Einladen").assertDoesNotExist()
        compose.onNodeWithText("Runde verlassen").assertDoesNotExist()
        compose.onNodeWithText("Abmelden").assertExists()
        capture("49_runde_anmeldung_abgelaufen")
    }

    @Test
    fun liessen_sich_die_runden_nicht_laden_steht_das_da() {
        var refreshed = 0
        content(
            RoundState(configured = true, loaded = true, account = account, roundsFailed = true),
            RoundUi(),
            RoundActions(refresh = { refreshed++ }),
        )

        compose.onNodeWithText("Deine Runden ließen sich nicht laden.").assertExists()
        capture("56_runde_runden_nicht_geladen")
        compose.onNodeWithText("Erneut versuchen").performClick()
        assertEquals(1, refreshed)
    }

    @Test
    fun ein_anderes_konto_wird_gemeldet_und_die_runde_bleibt() {
        content(linked.copy(account = null, message = RoundMessage.OTHER_ACCOUNT, pending = 2))

        compose.onNodeWithText(
            "Diese Runde gehört zu einem anderen Google-Konto. Melde dich mit dem Konto an, mit dem du der Runde beigetreten bist.",
        ).assertExists()
        compose.onNodeWithText("Schafkopfrunde").assertExists()
        compose.onNodeWithText("Mit Google anmelden").assertIsEnabled()
        capture("57_runde_anderes_konto")
    }

    @Test
    fun eine_runde_ohne_verbindung_laesst_sich_verlassen() {
        var asked: RoundSummary? = null
        var confirmed = 0
        val other = RoundSummary(RoundId("runde-2"), "Betriebsrunde", rev = 12, memberCount = 1, myPlayerId = null, isCreator = true)
        content(
            RoundState(configured = true, loaded = true, account = account, rounds = listOf(other)),
            RoundUi(leaving = other),
            RoundActions(leaveRound = { asked = it }, confirmLeaveRound = { confirmed++ }),
        )

        compose.onNodeWithText("Betriebsrunde verlassen?").assertExists()
        compose.onNodeWithText("Du gehörst danach nicht mehr zu dieser Runde. Auf diesem Gerät ändert sich nichts.").assertExists()
        compose.onNodeWithText("Du bist das letzte Mitglied: Die Runde wird mit allen Abenden auf dem Server gelöscht.").assertExists()
        capture("58_runde_andere_verlassen")
        compose.onAllNodesWithText("Verlassen").let { it[it.fetchSemanticsNodes().lastIndex] }.performClick()
        assertEquals(1, confirmed)
        assertNull(asked)
    }

    @Test
    fun abmelden_ohne_runde_und_verlassen_als_letztes_mitglied_sagen_was_passiert() {
        content(RoundState(configured = true, loaded = true, account = account), RoundUi(farewell = Farewell.SIGN_OUT))
        compose.onNodeWithText("Dieses Gerät wird abgemeldet.").assertExists()
        compose.onNodeWithText("Dieses Gerät wird von der Runde getrennt. Die Daten bleiben als Kopie hier.").assertDoesNotExist()
    }

    @Test
    fun verlassen_als_letztes_mitglied_sagt_dass_die_runde_verschwindet() {
        val alone = linked.copy(link = link.copy(members = listOf(RoundMember("konto-1", ids[0]))))
        content(alone, RoundUi(farewell = Farewell.LEAVE))

        compose.onNodeWithText(
            "Du bist das letzte Mitglied: Die Runde wird mit allen Abenden auf dem Server gelöscht. Auf diesem Gerät bleiben sie als Kopie.",
        ).assertExists()
        compose.onNodeWithText("Die Daten bleiben als Kopie auf diesem Gerät. Dein Name bleibt in der Runde.").assertDoesNotExist()
        capture("59_runde_letztes_mitglied_verlassen")
    }

    @Test
    fun rueckfrage_vor_dem_verlassen_nennt_wartendes() {
        var confirmed = 0
        content(linked.copy(pending = 3), RoundUi(farewell = Farewell.LEAVE, farewellPending = 3), RoundActions(confirmFarewell = { confirmed++ }))

        compose.onNodeWithText("Runde verlassen?").assertExists()
        compose.onNodeWithText("Die Daten bleiben als Kopie auf diesem Gerät. Dein Name bleibt in der Runde.").assertExists()
        compose.onNodeWithText("3 Änderungen sind noch nicht hochgeladen und kommen nicht mehr in der Runde an.").assertExists()
        capture("50_runde_verlassen")
        compose.onAllNodesWithText("Runde verlassen").let { it[it.fetchSemanticsNodes().lastIndex] }.performClick()
        assertEquals(1, confirmed)
    }

    @Test
    fun grosse_schrift_passt_auf_den_bildschirm() {
        content(linked.copy(pending = 12, problem = RoundProblem.SERVER), fontScale = 1.6f)

        compose.onNodeWithText("Server nicht erreichbar – 12 Änderungen warten").assertExists()
        compose.onNodeWithText("Einladen").performScrollTo().assertIsEnabled()
        capture("51_runde_grosse_schrift")
    }

    // --- Die Hinweise in den anderen Bildschirmen, mit einer wirklich verbundenen Runde ---

    /**
     * Legt mit den Ablagen der App eine Runde auf einem nachgestellten Server an und tauscht den Koordinator der App
     * gegen einen, der daran hängt. Der Abend enthält dasselbe Spiel zweimal direkt hintereinander.
     *
     * @param meDeals Ob der eigene Spieler der ist, der als Nächster gibt.
     */
    private fun linkedApp(meDeals: Boolean): RoundTestKit = runBlocking {
        ApplicationProvider.getApplicationContext<TestSchafkopfApp>()
        val koin = GlobalContext.get()
        val playerRepository = koin.get<PlayerRepository>()
        val eveningRepository = koin.get<EveningRepository>()
        players.forEach { playerRepository.save(it) }
        val start = Instant.parse("2026-12-12T18:30:00Z")
        val game = GameEntry(EntryId("e1"), start.plusSeconds(600), GameType.RUFSPIEL, Suit.EICHEL, declarer = ids[0], partner = ids[2], declarerWon = true)
        val evening = Evening(
            id = EveningId("abend-1"),
            startedAt = start,
            location = "Wirtshaus zur Post",
            seating = ids,
            firstDealer = ids[0],
            rules = RuleSet.standard(),
            entries = listOf(
                game,
                GameEntry(EntryId("e2"), start.plusSeconds(1200), GameType.WENZ, declarer = ids[1], declarerWon = false, laufende = 2),
                // Zwei Handys haben dasselbe Spiel eingetragen.
                GameEntry(EntryId("e3"), start.plusSeconds(1800), GameType.FARBSOLO, Suit.HERZ, declarer = ids[3], declarerWon = true),
                GameEntry(EntryId("e4"), start.plusSeconds(1812), GameType.FARBSOLO, Suit.HERZ, declarer = ids[3], declarerWon = true),
            ),
        )
        eveningRepository.save(evening)
        val kit = RoundTestKit(scope, players = playerRepository, evenings = eveningRepository, settings = koin.get<SettingsRepository>())
        kit.coordinator.start()
        // Wie in der App: Erst wenn die Anmeldung gelesen ist, lässt sich eine Runde anlegen.
        withTimeout(10_000) { kit.coordinator.state.first { it.loaded && it.account != null } }
        kit.coordinator.create("Schafkopfrunde")
        val dealer = EveningCalculator.compute(evening).nextDealer
        kit.coordinator.claim(if (meDeals) dealer else ids.first { it != dealer }, if (meDeals) "Ich" else "Jemand")
        koin.declare<RoundCoordinator>(kit.coordinator, allowOverride = true)
        kit
    }

    @Test
    fun im_abend_steht_wer_gibt_und_was_doppelt_ist() {
        linkedApp(meDeals = true)
        show { BoardScreen("abend-1", Navigator()) }
        compose.waitForText("Du gibst – trag dieses Spiel ein.")

        compose.onAllNodesWithText("Doppelt eingetragen?").assertCountEquals(1)
        compose.onNodeWithContentDescription("Gemeinsame Runde: Abgeglichen").assertExists()
        capture("52_abend_du_gibst")
    }

    @Test
    fun gibt_ein_anderer_steht_sein_name_da_und_ohne_netz_das_symbol() {
        val kit = linkedApp(meDeals = false)
        // Ohne Netz wird der Ort nachgetragen: Die Änderung wartet, und das Symbol in der Kopfzeile sagt es.
        kit.session.offline = true
        runBlocking {
            kit.editor.update(EveningId("abend-1")) { it.copy(location = "Nebenzimmer") }
            kit.coordinator.syncInBackground()
        }
        show(ThemeMode.LIGHT) { BoardScreen("abend-1", Navigator()) }
        // Nach vier Spielen gibt wieder der Erste am Tisch; der eigene Spieler ist ein anderer.
        compose.waitForText("Sepp gibt und trägt ein.")

        compose.onNodeWithText("Du gibst – trag dieses Spiel ein.").assertDoesNotExist()
        compose.onNodeWithContentDescription("Gemeinsame Runde: Kein Netz – 1 Änderung wartet").assertExists()
        capture("53_abend_ohne_netz")
    }

    @Test
    fun auf_der_startseite_steht_die_runde_mit_ihrem_stand() {
        linkedApp(meDeals = true)
        show { StartScreen(Navigator()) }
        compose.waitForText("Runde: Schafkopfrunde")
        compose.waitForText("Wirtshaus zur Post")

        compose.onNodeWithText("Abgeglichen").assertExists()
        // Jeder Abend sagt, zu welcher Runde er gehört (docs/ARCHITEKTUR.md 8.9): die Zeile der Runde und die Karte.
        compose.onAllNodesWithText("Runde: Schafkopfrunde").assertCountEquals(2)
        compose.onNode(hasText("Wirtshaus zur Post", substring = true) and hasText("Runde: Schafkopfrunde")).assertExists()
        capture("54_start_mit_runde")
    }

    // --- Zwischen Runden wechseln (docs/ARCHITEKTUR.md 8.7 und 8.9) ---

    /**
     * Das Konto von [linkedApp] gehört außerdem dem „Stammtisch“ von Anna an und einer zweiten „Schafkopfrunde“ von Bert
     * (gleicher Name wie die eigene: Die Auswahl nennt bei beiden die Mitglieder).
     */
    private fun moreRounds(kit: RoundTestKit) = runBlocking {
        val anna = kit.server.sessionFor("konto-anna")
        val stammtisch = anna.createRound("Stammtisch")
        anna.claimPlayer(stammtisch, PlayerId("anna"), "Anna", 1)
        kit.session.joinRound(anna.createInvite(stammtisch))
        val bert = kit.server.sessionFor("konto-bert")
        val other = bert.createRound("Schafkopfrunde")
        bert.claimPlayer(other, PlayerId("bert"), "Bert", 2)
        kit.session.joinRound(bert.createInvite(other))
        kit.coordinator.refreshRounds()
    }

    @Test
    fun auf_der_startseite_laesst_sich_die_runde_wechseln() {
        val kit = linkedApp(meDeals = true)
        moreRounds(kit)
        show { StartScreen(Navigator()) }
        compose.waitForText("Wirtshaus zur Post")
        val choice = "Runde: Schafkopfrunde (Ich). Tippen, um eine andere Runde zu wählen."
        compose.waitFor("Die Auswahl der Runde") { compose.onAllNodesWithContentDescription(choice).fetchSemanticsNodes().isNotEmpty() }

        // Die Auswahl zeigt den Namen; in der Zeile steht er deshalb nicht noch einmal, nur an der Karte des Abends.
        compose.onNodeWithText("Gemeinsame Runden").assertExists()
        compose.onAllNodesWithText("Runde: Schafkopfrunde").assertCountEquals(1)
        compose.onNodeWithContentDescription(choice).performClick()
        compose.waitForText("Schafkopfrunde (Bert)")
        compose.onNodeWithText("Stammtisch").assertExists()
        capture("67_start_auswahl_der_runden")

        compose.onNodeWithText("Stammtisch").performClick()
        compose.waitForText("Zur Runde Stammtisch wechseln?")
        compose.onNodeWithText("Auf diesem Gerät stehen dann die Abende und Spieler von Stammtisch.").assertExists()
        compose.onNodeWithText("Die Abende von Schafkopfrunde bleiben dort; du kannst jederzeit zurückwechseln.").assertExists()
        capture("68_start_rueckfrage_wechseln")

        compose.onNodeWithText("Wechseln").performClick()
        // Gewartet wird auf die Anzeige (die Suche lässt den Hauptthread laufen, auf dem das ViewModel wechselt) und auf
        // den Endstand: Zwischendurch ist das Gerät kurz leer und ohne Runde.
        val switched = "Runde: Stammtisch. Tippen, um eine andere Runde zu wählen."
        compose.waitFor("Die Auswahl mit dem Stammtisch") {
            compose.onAllNodesWithContentDescription(switched).fetchSemanticsNodes().isNotEmpty()
        }
        assertEquals("Stammtisch", kit.state.link?.roundName)
        compose.waitForText("Noch kein Abend eingetragen")
        compose.onAllNodesWithText("Wirtshaus zur Post", substring = true).assertCountEquals(0)
        // Der Abend ist nicht verloren, er liegt in der Runde auf dem Server.
        assertEquals(listOf("abend-1"), kit.server.evenings(round).map { it.id.value })
    }

    @Test
    fun die_auswahl_kommt_auch_wenn_die_anmeldung_erst_nach_dem_ersten_bild_gelesen_ist() {
        val kit = linkedApp(meDeals = true)
        moreRounds(kit)
        // Wie beim Start der App: Die Anmeldung ist noch nicht gelesen, die Liste der Runden gehört dann zu niemandem.
        val signedIn = kit.account.account.value
        kit.account.account.value = null
        compose.waitFor("Der Stand ohne Anmeldung") { kit.state.account == null && kit.state.rounds == null }
        show { StartScreen(Navigator()) }
        compose.waitForText("Gemeinsame Runden")
        val choice = "Runde: Schafkopfrunde (Ich). Tippen, um eine andere Runde zu wählen."
        compose.onAllNodesWithContentDescription(choice).assertCountEquals(0)

        kit.account.account.value = signedIn

        compose.waitFor("Die Auswahl der Runde") { compose.onAllNodesWithContentDescription(choice).fetchSemanticsNodes().isNotEmpty() }
    }

    @Test
    fun verbunden_stehen_die_anderen_runden_und_neue_runde_anlegen_da() {
        val others = listOf(
            RoundSummary(round, "Schafkopfrunde", rev = 9, memberCount = 3, myPlayerId = ids[0], isCreator = true, memberNames = listOf("Kathi", "Sepp")),
            RoundSummary(RoundId("runde-2"), "Stammtisch", rev = 4, memberCount = 2, myPlayerId = null, isCreator = false, memberNames = listOf("Anna")),
            RoundSummary(RoundId("runde-3"), "Betriebsrunde", rev = 1, memberCount = 1, myPlayerId = null, isCreator = true),
        )
        val used = ArrayList<RoundSummary>()
        val created = ArrayList<String>()
        content(linked.copy(rounds = others), actions = RoundActions(use = { used += it }, create = { created += it }))

        compose.onNodeWithText("Deine anderen Runden").performScrollTo()
        compose.onNodeWithText("2 Mitglieder: Anna").assertExists()
        compose.onNodeWithText("1 Mitglied").assertExists()
        // Die verbundene Runde steht nicht unter den anderen.
        compose.onAllNodesWithText("3 Mitglieder: Kathi, Sepp").assertCountEquals(0)
        capture("69_runde_verbunden_andere_runden")

        compose.onAllNodesWithText("Wechseln")[0].performClick()
        assertEquals(listOf("runde-2"), used.map { it.id.value })
        // Der Vorschlag für den Namen ist frei; der Hinweis sagt, dass die neue Runde leer beginnt.
        compose.onNodeWithText("Die neue Runde beginnt leer. Die Abende von Schafkopfrunde bleiben dort.").performScrollTo()
        compose.onNodeWithText("Runde anlegen").performScrollTo().performClick()
        assertEquals(listOf("Schafkopfrunde 2"), created)
        compose.onNodeWithText("Einladung einlösen").performScrollTo().assertExists()
    }

    @Test
    fun die_rueckfrage_vor_einer_neuen_runde_nennt_was_verloren_ginge() {
        var confirmed = 0
        content(
            linked,
            RoundUi(switching = SwitchRequest.Create("Freitagsrunde"), switchFrom = SwitchFrom(round, acceptedLoss = 2), autoBackup = true),
            RoundActions(confirmSwitch = { confirmed++ }),
            themeMode = ThemeMode.LIGHT,
        )

        compose.onNodeWithText("Neue Runde Freitagsrunde anlegen?").assertExists()
        compose.onNodeWithText("Auf diesem Gerät beginnt dann die neue, leere Runde.").assertExists()
        compose.onNodeWithText("Die Abende von Schafkopfrunde bleiben dort; du kannst jederzeit zurückwechseln.").assertExists()
        compose.onNodeWithText("2 Änderungen sind noch nicht hochgeladen und gehen dabei verloren.").assertExists()
        compose.onNodeWithText("Die automatische Sicherung wird danach mit den Daten der Runde überschrieben.").assertExists()
        capture("70_runde_rueckfrage_neue_runde")
        compose.onNodeWithText("Anlegen").performClick()
        assertEquals(1, confirmed)
    }

    @Test
    fun in_den_einstellungen_stehen_gemeinsame_runden_mit_runde_und_stand() {
        linkedApp(meDeals = true)
        show { SettingsScreen(Navigator()) }
        compose.waitForText("Runde: Schafkopfrunde")

        compose.onNodeWithText("Gemeinsame Runden").assertExists()
        compose.onNodeWithText("Runde: Schafkopfrunde\nAbgeglichen").performScrollTo()
        capture("71_einstellungen_mit_runde")
    }

    @Test
    fun eine_einladung_in_die_eigene_runde_ist_ein_hinweis() {
        content(linked, RoundUi(error = RoundUiError.AlreadyInRound))

        compose.onNodeWithText("Du bist schon in dieser Runde.").assertExists()
    }

    @Test
    fun ohne_runde_fuehrt_die_startseite_zur_gemeinsamen_runde() {
        ApplicationProvider.getApplicationContext<TestSchafkopfApp>()
        show { StartScreen(Navigator()) }
        compose.waitForText("Gemeinsame Runden")

        compose.onNodeWithText("Alle tragen vom eigenen Handy ein").assertExists()
    }

    @Test
    fun ein_fester_name_laesst_sich_bei_den_spielern_nicht_aendern() {
        // Der Inhalt des Dialogs „Spieler bearbeiten“ für einen Spieler, der einem Mitglied gehört.
        var deletes = 0
        val row = PlayerRow(players[0], eveningCount = 0, nameLocked = true, owned = true)
        show {
            PlayerEditFields(row, name = "Sepp", onNameChange = {}, nameError = false, archived = false, onArchivedChange = {}, onDelete = { deletes++ })
        }

        compose.onNodeWithText("Sepp").assertIsNotEnabled()
        compose.onNodeWithText("Name selbst festgelegt").assertExists()
        compose.onNodeWithText("Dieser Spieler gehört zu einem Mitglied der Runde und lässt sich nicht löschen.").assertExists()
        compose.onNodeWithText("Löschen").assertDoesNotExist()
        capture("55_spieler_fester_name")
    }

    @Test
    fun ein_gast_laesst_sich_weiter_umbenennen_und_loeschen() {
        var deletes = 0
        show {
            PlayerEditFields(PlayerRow(players[1], eveningCount = 0), name = "Hans", onNameChange = {}, nameError = false, archived = false, onArchivedChange = {}, onDelete = { deletes++ })
        }

        compose.onNodeWithText("Hans").assertIsEnabled()
        compose.onNodeWithText("Name selbst festgelegt").assertDoesNotExist()
        compose.onNodeWithText("Spieler löschen").performClick()
        assertEquals(1, deletes)
    }
}

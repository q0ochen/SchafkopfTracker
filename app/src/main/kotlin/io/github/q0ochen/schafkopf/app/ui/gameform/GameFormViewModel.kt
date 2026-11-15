package io.github.q0ochen.schafkopf.app.ui.gameform

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.q0ochen.schafkopf.domain.evening.BockOverride
import io.github.q0ochen.schafkopf.domain.evening.Evening
import io.github.q0ochen.schafkopf.domain.evening.EveningCalculator
import io.github.q0ochen.schafkopf.domain.evening.EveningEditor
import io.github.q0ochen.schafkopf.domain.evening.GameEntry
import io.github.q0ochen.schafkopf.domain.model.EntryId
import io.github.q0ochen.schafkopf.domain.model.EveningId
import io.github.q0ochen.schafkopf.domain.model.PlayerId
import io.github.q0ochen.schafkopf.domain.ports.EveningRepository
import io.github.q0ochen.schafkopf.domain.ports.PlayerRepository
import io.github.q0ochen.schafkopf.domain.rules.Announcement
import io.github.q0ochen.schafkopf.domain.rules.GameFacts
import io.github.q0ochen.schafkopf.domain.rules.GameType
import io.github.q0ochen.schafkopf.domain.rules.KontraLevel
import io.github.q0ochen.schafkopf.domain.rules.ResultLevel
import io.github.q0ochen.schafkopf.domain.rules.ScoreCalculator
import io.github.q0ochen.schafkopf.domain.rules.Suit
import io.github.q0ochen.schafkopf.domain.rules.ValueBreakdown
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Was im Formular gerade gewählt ist. */
data class GameDraft(
    val type: GameType = GameType.RUFSPIEL,
    val suit: Suit? = null,
    val declarer: PlayerId? = null,
    val partner: PlayerId? = null,
    val klopfer: Set<PlayerId> = emptySet(),
    val kontra: KontraLevel = KontraLevel.NONE,
    val kontraBy: PlayerId? = null,
    val level: ResultLevel = ResultLevel.NORMAL,
    val laufende: Int = 0,
    val announcement: Announcement = Announcement.NONE,
    val bockOverride: BockOverride = BockOverride.AUTO,
    /** Nur beim Ändern eines Spiels: das gewählte Ergebnis. */
    val declarerWon: Boolean = true,
)

/** Was noch fehlt, bevor das Spiel gespeichert werden kann. */
enum class MissingInput { DECLARER, PARTNER }

data class GameFormUiState(
    val loading: Boolean = true,
    val evening: Evening? = null,
    val names: Map<PlayerId, String> = emptyMap(),
    val editing: Boolean = false,
    val gameNumber: Int = 1,
    val draft: GameDraft = GameDraft(),
    /** Bock-Stufe, die für dieses Spiel gilt (laut Zähler oder von Hand gesetzt). */
    val bockExponent: Int = 0,
    /** Bock-Stufe laut Zähler, unabhängig von der Auswahl im Formular. */
    val autoBockExponent: Int = 0,
    val breakdown: ValueBreakdown? = null,
    /** Betrag des Ansagers bei Gewinn (bei Verlust derselbe Betrag mit Minus). */
    val declarerAmount: Int = 0,
    val missing: MissingInput? = MissingInput.DECLARER,
) {
    val canSave: Boolean get() = !loading && evening != null && missing == null
    fun name(player: PlayerId?): String = names[player] ?: "?"
}

class GameFormViewModel(
    private val eveningId: EveningId,
    private val entryId: EntryId?,
    evenings: EveningRepository,
    players: PlayerRepository,
    private val editor: EveningEditor,
) : ViewModel() {

    private val draft = MutableStateFlow<GameDraft?>(null)

    /**
     * Schutz gegen doppeltes Antippen: Das Formular speichert oder löscht nur einmal. Ohne ihn konnte ein schneller
     * Doppeltipp auf „Gewonnen“ dasselbe Spiel zweimal eintragen, bevor der Bildschirm zurück zur Liste ging.
     */
    private var finished = false

    val state: StateFlow<GameFormUiState> =
        combine(evenings.evening(eveningId), players.players, draft) { evening, allPlayers, currentDraft ->
            if (evening == null) return@combine GameFormUiState(loading = false)
            val existing = entryId?.let { id -> evening.entries.firstOrNull { it.id == id } as? GameEntry }
            val shown = currentDraft ?: existing?.toDraft() ?: GameDraft(type = defaultType(evening))
            buildState(evening, allPlayers.associate { it.id to it.name }, existing, shown)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), GameFormUiState())

    private fun defaultType(evening: Evening): GameType =
        GameType.entries.firstOrNull { it in evening.rules.enabledTypes } ?: GameType.RUFSPIEL

    private fun buildState(evening: Evening, names: Map<PlayerId, String>, existing: GameEntry?, shown: GameDraft): GameFormUiState {
        val before = if (existing != null) {
            evening.copy(entries = evening.entries.takeWhile { it.id != existing.id })
        } else {
            evening
        }
        val computedBefore = EveningCalculator.compute(before)
        val auto = computedBefore.nextBockExponent
        val bockExponent = when (shown.bockOverride) {
            BockOverride.AUTO -> auto
            BockOverride.FORCE_ON -> maxOf(1, auto)
            BockOverride.FORCE_OFF -> 0
        }
        val missing = when {
            shown.declarer == null -> MissingInput.DECLARER
            !shown.type.isSolo && shown.partner == null -> MissingInput.PARTNER
            else -> null
        }
        val facts = shown.declarer?.let { declarer ->
            GameFacts(
                type = shown.type,
                players = evening.seating,
                declarer = declarer,
                partner = shown.partner.takeIf { !shown.type.isSolo },
                declarerWon = true,
                level = shown.level,
                laufende = shown.laufende,
                announcement = shown.announcement,
                klopferCount = shown.klopfer.size,
                kontra = shown.kontra,
                bockExponent = bockExponent,
            )
        }
        val breakdown = facts?.let { ScoreCalculator.breakdown(it, evening.rules) }
        val declarerAmount = if (facts != null && missing == null) {
            // Sitzt der Ansager nicht am Tisch (das kann nur aus beschädigten Daten stammen), gibt es für ihn keinen
            // Betrag: Das Formular zeigt dann 0, statt abzustürzen.
            ScoreCalculator.amounts(facts, evening.rules)[facts.declarer] ?: 0
        } else {
            0
        }
        return GameFormUiState(
            loading = false,
            evening = evening,
            names = names,
            editing = existing != null,
            gameNumber = computedBefore.gamesPlayed + 1,
            draft = shown,
            bockExponent = bockExponent,
            autoBockExponent = auto,
            breakdown = breakdown,
            declarerAmount = declarerAmount,
            missing = missing,
        )
    }

    private fun change(transform: (GameDraft, Evening) -> GameDraft) {
        val current = state.value
        val evening = current.evening ?: return
        draft.update { transform(it ?: current.draft, evening) }
    }

    fun selectType(type: GameType) = change { d, evening ->
        val rules = evening.rules
        d.copy(
            type = type,
            partner = when {
                type.isSolo -> null
                // Über Kreuz: Zum Gewählten gehört immer sein Gegenüber.
                !type.hasDeclarer -> d.declarer?.let { oppositeOf(it, evening.seating) }
                // Von Über Kreuz zurück zum Rufspiel: Der Mitspieler wird neu gewählt.
                !d.type.hasDeclarer -> null
                else -> d.partner
            },
            suit = null,
            laufende = d.laufende.takeIf { it <= rules.laufendeMax(type) && it >= rules.laufendeMin(type) } ?: 0,
            announcement = when (d.announcement) {
                Announcement.NONE -> Announcement.NONE
                Announcement.TOUT -> if (rules.allowsTout(type)) Announcement.TOUT else Announcement.NONE
                Announcement.SIE -> if (rules.allowsSie(type)) Announcement.SIE else Announcement.NONE
            },
            kontraBy = null,
        )
    }

    /**
     * Antippen eines Spielers. Einzelspiel: der Angetippte spielt. Rufspiel: der erste ist der Spieler,
     * der zweite der Mitspieler; ein dritter ersetzt den Mitspieler. Über Kreuz: Ein Tipp wählt das Paar aus dem
     * Angetippten und seinem Gegenüber. Erneutes Antippen hebt die Wahl auf.
     */
    fun tapPlayer(player: PlayerId) = change { d, evening ->
        val changed = when {
            !d.type.hasDeclarer ->
                if (player == d.declarer || player == d.partner) {
                    d.copy(declarer = null, partner = null)
                } else {
                    d.copy(declarer = player, partner = oppositeOf(player, evening.seating))
                }

            d.declarer == player -> d.copy(declarer = null, partner = null)
            d.partner == player -> d.copy(partner = null)
            d.declarer == null -> d.copy(declarer = player)
            d.type.isSolo -> d.copy(declarer = player)
            else -> d.copy(partner = player)
        }
        // Wer Kontra gegeben hat, muss Gegenspieler sein.
        if (changed.kontraBy == changed.declarer || changed.kontraBy == changed.partner) changed.copy(kontraBy = null) else changed
    }

    /** Wer dem Spieler am Tisch gegenübersitzt (zwei Plätze weiter). */
    private fun oppositeOf(player: PlayerId, seating: List<PlayerId>): PlayerId? {
        val index = seating.indexOf(player)
        return if (index < 0 || seating.isEmpty()) null else seating[(index + seating.size / 2) % seating.size]
    }

    fun selectSuit(suit: Suit) = change { d, _ -> d.copy(suit = suit.takeIf { it != d.suit }) }

    fun toggleKlopfer(player: PlayerId) = change { d, _ ->
        d.copy(klopfer = if (player in d.klopfer) d.klopfer - player else d.klopfer + player)
    }

    fun selectKontra(level: KontraLevel) = change { d, _ ->
        d.copy(kontra = level, kontraBy = d.kontraBy.takeIf { level != KontraLevel.NONE })
    }

    fun selectKontraBy(player: PlayerId) = change { d, _ -> d.copy(kontraBy = player.takeIf { it != d.kontraBy }) }

    fun selectLevel(level: ResultLevel) = change { d, _ -> d.copy(level = level) }

    fun selectLaufende(count: Int) = change { d, _ -> d.copy(laufende = count) }

    fun selectAnnouncement(announcement: Announcement) = change { d, _ ->
        val chosen = if (d.announcement == announcement) Announcement.NONE else announcement
        d.copy(
            announcement = chosen,
            level = if (chosen == Announcement.NONE) d.level else ResultLevel.NORMAL,
            declarerWon = if (chosen == Announcement.SIE) true else d.declarerWon,
        )
    }

    /**
     * Schaltet „Bock“ für dieses Spiel ein oder aus. Stimmt die Wahl mit dem Bock-Zähler überein,
     * entscheidet wieder der Zähler; sonst gilt die Wahl von Hand.
     */
    fun setBock(on: Boolean) {
        val auto = state.value.autoBockExponent > 0
        val override = when {
            on == auto -> BockOverride.AUTO
            on -> BockOverride.FORCE_ON
            else -> BockOverride.FORCE_OFF
        }
        change { d, _ -> d.copy(bockOverride = override) }
    }

    fun selectResult(won: Boolean) = change { d, _ -> d.copy(declarerWon = won) }

    /**
     * Speichert das Spiel. Neu: [won] ist das angetippte Ergebnis. Ändern: das im Formular gewählte Ergebnis.
     */
    fun save(won: Boolean?, onSaved: () -> Unit) {
        val current = state.value
        val d = current.draft
        val declarer = d.declarer
        if (finished || !current.canSave || declarer == null) return
        finished = true
        viewModelScope.launch {
            editor.update(eveningId) { evening ->
                val existing = entryId?.let { id -> evening.entries.firstOrNull { it.id == id } as? GameEntry }
                val entry = GameEntry(
                    id = existing?.id ?: editor.newEntryId(),
                    recordedAt = existing?.recordedAt ?: editor.now(),
                    type = d.type,
                    suit = d.suit,
                    declarer = declarer,
                    partner = d.partner.takeIf { !d.type.isSolo },
                    declarerWon = d.announcement == Announcement.SIE || (won ?: d.declarerWon),
                    level = if (d.announcement == Announcement.NONE) d.level else ResultLevel.NORMAL,
                    laufende = d.laufende,
                    announcement = d.announcement,
                    klopfer = d.klopfer,
                    kontra = d.kontra,
                    kontraBy = d.kontraBy,
                    retourBy = existing?.retourBy.takeIf { d.kontra == KontraLevel.RETOUR },
                    bockOverride = d.bockOverride,
                    declarerEyes = existing?.declarerEyes,
                    note = existing?.note.orEmpty(),
                )
                if (existing != null) evening.replace(entry) else evening.append(entry)
            }
            onSaved()
        }
    }

    fun delete(onDeleted: () -> Unit) {
        val id = entryId ?: return
        if (finished) return
        finished = true
        viewModelScope.launch {
            editor.update(eveningId) { it.remove(id) }
            onDeleted()
        }
    }

    private fun GameEntry.toDraft() = GameDraft(
        type = type,
        suit = suit,
        declarer = declarer,
        partner = partner,
        klopfer = klopfer,
        kontra = kontra,
        kontraBy = kontraBy,
        level = level,
        laufende = laufende,
        announcement = announcement,
        bockOverride = bockOverride,
        declarerWon = declarerWon,
    )
}

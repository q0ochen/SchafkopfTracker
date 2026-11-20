package io.github.q0ochen.schafkopf.domain.round

/**
 * Einladungen in eine gemeinsame Runde: ein Code aus 32 Zeichen (`0-9a-f`), verpackt in einen Link zur Web-App oder
 * direkt in die Android-App.
 */
object InviteLinks {
    /** Eigenes Schema der Android-App (auch für die Rückkehr aus der Anmeldung). */
    const val APP_SCHEME = "schafkopftracker"

    private const val PATH = "einladung/"
    private const val CODE_LENGTH = 32
    private val HEX_RUN = Regex("[0-9a-f]+")

    /**
     * Der Einladungscode aus dem, was jemand einfügt oder antippt: der bloße Code, ein Link zur Web-App
     * (`…#/einladung/<code>`) oder der Link in die Android-App, auch mitten in einer Nachricht. Genommen wird die erste
     * Folge aus genau 32 Zeichen `0-9a-f`; gibt es keine, `null`.
     */
    fun tokenFrom(text: String): String? = HEX_RUN.findAll(text).firstOrNull { it.value.length == CODE_LENGTH }?.value

    /** Der Link zum Weitergeben: öffnet die Web-App, die auch „In der Android-App öffnen“ anbietet. */
    fun webLink(webAppUrl: String, token: String): String = webAppUrl.trimEnd('/') + "/#/" + PATH + token

    /** Der Link, der die Android-App direkt öffnet. */
    fun appLink(token: String): String = "$APP_SCHEME://$PATH$token"
}

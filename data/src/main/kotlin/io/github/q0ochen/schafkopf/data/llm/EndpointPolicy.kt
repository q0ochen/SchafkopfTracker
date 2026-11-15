package io.github.q0ochen.schafkopf.data.llm

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * Legt fest, an welche Adressen die App die Zahlen eines Abends und den Schlüssel schicken darf.
 *
 * - Verschlüsselt (`https`) ist jede Adresse erlaubt.
 * - Unverschlüsselt (`http`) geht es nur zum eigenen Gerät und ins eigene Netz, zum Beispiel zu einem
 *   Sprachmodell auf dem Rechner zu Hause: `localhost`, `127.x.x.x`, `[::1]`, `10.x.x.x`, `172.16.x.x` bis
 *   `172.31.x.x` und `192.168.x.x`. Solche Adressen müssen als Zahlen dastehen. Namen werden nicht nachgeschlagen
 *   (kein DNS-Aufruf); darum zählt `127.0.0.1.example.com` nicht als eigenes Gerät.
 * - Abgelehnt werden Adressen mit Benutzerangaben (`name@rechner`), mit Abfrage (`?…`) oder Sprungmarke (`#…`)
 *   und alles, was Leerraum, Steuerzeichen oder einen Rückstrich enthält.
 * - Abgelehnt werden auch Adressen, die sich nach dem Bereinigen nicht genauso wieder lesen lassen. Was als erlaubt
 *   gilt, ist dadurch immer auch ansprechbar und hat einen Rechnernamen, siehe [host].
 *
 * Beurteilt wird die Adresse so, wie die Netzbibliothek (OkHttp) sie versteht und später auch anspricht. Dadurch
 * lässt sich die Regel nicht mit trickreichen Schreibweisen umgehen.
 */
object EndpointPolicy {

    private const val LOCALHOST = "localhost"

    /** So schreibt die Netzbibliothek die IPv6-Adresse des eigenen Geräts, gleich wie sie eingegeben wurde. */
    private const val IPV6_LOOPBACK = "::1"

    /**
     * Prüft eine Basis-Adresse und bringt sie in eine einheitliche Form: Rechnername in Kleinbuchstaben, ohne
     * Standard-Port und ohne Schrägstrich am Ende.
     *
     * @return die bereinigte Adresse oder `null`, wenn die Adresse nicht erlaubt ist.
     */
    fun normalize(baseUrl: String): String? {
        val input = baseUrl.trim()
        if (input.any { it.isWhitespace() || it.isISOControl() || it == '\\' }) return null

        val url = input.toHttpUrlOrNull() ?: return null
        if (url.username.isNotEmpty() || url.password.isNotEmpty()) return null
        if (url.query != null || url.fragment != null) return null
        if (!url.isHttps && !isOwnNetwork(url.host)) return null

        // Die bereinigte Adresse muss sich genauso wieder lesen lassen, denn aus ihr entstehen die Aufrufe. Steht in
        // einem Namensteil ein Umlaut neben einem Zeichen wie dem Unterstrich (`büro_nas`), schreibt die
        // Netzbibliothek den Namen in eine Form um, die sie selbst nicht mehr annimmt: Auch das ist nicht erlaubt.
        val cleaned = url.toString().trimEnd('/')
        return cleaned.takeIf { it.toHttpUrlOrNull()?.toString()?.trimEnd('/') == it }
    }

    /**
     * Der Rechnername einer erlaubten Adresse, genau so, wie er angesprochen wird. Die Oberfläche braucht ihn
     * für die Frage, ob an diesen Dienst gesendet werden darf.
     *
     * @return der Rechnername oder `null`, wenn die Adresse nicht erlaubt ist.
     */
    fun host(baseUrl: String): String? = parse(baseUrl)?.host

    /** Die geprüfte Adresse, an die die einzelnen Aufrufe angehängt werden; `null`, wenn sie nicht erlaubt ist. */
    internal fun parse(baseUrl: String): HttpUrl? = normalize(baseUrl)?.toHttpUrlOrNull()

    /** Ob der Rechnername das eigene Gerät oder eine Zahlenadresse aus einem privaten Netz ist. */
    private fun isOwnNetwork(host: String): Boolean {
        if (host == LOCALHOST || host == IPV6_LOOPBACK) return true
        val (first, second) = ipv4Parts(host) ?: return false
        return first == 127 || first == 10 || (first == 172 && second in 16..31) || (first == 192 && second == 168)
    }

    /**
     * Zerlegt eine IPv4-Adresse in der üblichen Schreibweise `a.b.c.d` in ihre vier Zahlen; `null` bei allem
     * anderen. Zugelassen sind nur schlichte Dezimalzahlen von 0 bis 255 ohne führende Null: Kurzformen wie
     * `127.1`, Hexadezimalzahlen und führende Nullen lesen manche Systeme anders, als man es der Adresse ansieht.
     */
    private fun ipv4Parts(host: String): List<Int>? {
        val parts = host.split('.')
        if (parts.size != 4) return null
        return parts.map { part ->
            if (part.isEmpty() || part.length > 3 || part.any { it !in '0'..'9' }) return null
            if (part.length > 1 && part[0] == '0') return null
            part.toInt().takeIf { it <= 255 } ?: return null
        }
    }
}

package io.github.q0ochen.schafkopf.data.llm

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class EndpointPolicyTest {

    private fun assertAllowed(expected: String, input: String) {
        assertEquals(expected, EndpointPolicy.normalize(input), "Eingabe: $input")
    }

    /** Für Adressen, die schon in der einheitlichen Form dastehen. */
    private fun assertUnchanged(input: String) = assertAllowed(input, input)

    private fun assertRejected(input: String) {
        assertNull(EndpointPolicy.normalize(input), "Eingabe: $input")
    }

    // ---------- Erlaubt ----------

    @Test
    fun `https ist fuer jeden Rechner erlaubt`() {
        assertUnchanged("https://generativelanguage.googleapis.com")
        assertUnchanged("https://api.example.com/v1")
        assertUnchanged("https://8.8.8.8")
        assertUnchanged("https://192.168.1.20:8443/v1")
        assertUnchanged("https://[2001:db8::1]/v1")
        // Mit https spielt es keine Rolle, wie der Name aussieht.
        assertUnchanged("https://localhost.example.com")
        assertUnchanged("https://127.0.0.1.example.com:8443")
    }

    @Test
    fun `http ist zum eigenen Geraet erlaubt`() {
        assertUnchanged("http://localhost")
        assertUnchanged("http://localhost:11434/v1")
        assertUnchanged("http://127.0.0.1")
        assertUnchanged("http://127.0.0.1:8080/v1")
        // Der ganze Bereich 127.0.0.0/8 ist das eigene Gerät.
        assertUnchanged("http://127.1.2.3")
        assertUnchanged("http://127.255.255.254")
        assertUnchanged("http://[::1]")
        assertUnchanged("http://[::1]:11434/v1")
        // Ausgeschriebene Formen derselben Adresse.
        assertAllowed("http://[::1]", "http://[0:0:0:0:0:0:0:1]")
        assertAllowed("http://127.0.0.1:8080", "http://[::ffff:127.0.0.1]:8080")
    }

    @Test
    fun `http ist in die privaten Netze erlaubt`() {
        // 10.0.0.0/8
        assertUnchanged("http://10.0.0.0")
        assertUnchanged("http://10.1.2.3:8000/v1")
        assertUnchanged("http://10.255.255.255")
        // 172.16.0.0/12 reicht von 172.16.0.0 bis 172.31.255.255.
        assertUnchanged("http://172.16.0.0")
        assertUnchanged("http://172.20.10.5:1234")
        assertUnchanged("http://172.31.255.255")
        // 192.168.0.0/16
        assertUnchanged("http://192.168.0.0")
        assertUnchanged("http://192.168.1.20:11434/v1")
        assertUnchanged("http://192.168.255.255")
        // Dieselbe private Adresse in IPv6-Verpackung.
        assertAllowed("http://10.0.0.1", "http://[::ffff:10.0.0.1]")
    }

    // ---------- Vereinheitlichen ----------

    @Test
    fun `ein abschliessender Schraegstrich wird entfernt`() {
        assertAllowed("https://example.com", "https://example.com/")
        assertAllowed("https://example.com/v1", "https://example.com/v1/")
        assertAllowed("https://example.com", "https://example.com//")
        assertAllowed("https://example.com/a/b", "https://example.com/a/b///")
        assertAllowed("http://localhost:11434/v1", "http://localhost:11434/v1/")
        assertAllowed("http://[::1]:8080", "http://[::1]:8080/")
    }

    @Test
    fun `die Adresse wird in eine einheitliche Form gebracht`() {
        // Protokoll und Rechnername klein, der Pfad bleibt, wie er ist; der Standard-Port fällt weg.
        assertAllowed("https://api.example.com/V1/Chat", "HTTPS://API.Example.COM:443/V1/Chat")
        assertAllowed("http://localhost", "http://LOCALHOST:80")
        // Leerraum an den Rändern (auch ein Zeilenumbruch vom Einfügen) zählt nicht.
        assertAllowed("https://example.com/v1", "  https://example.com/v1  ")
        assertAllowed("https://example.com/v1", "https://example.com/v1/\n")
        // Umlaute im Namen werden so geschrieben, wie sie im Netz tatsächlich angesprochen werden.
        assertAllowed("https://xn--bcher-kva.example/v1", "https://bücher.example/v1")
        assertAllowed("https://example.com/b/c", "https://example.com/a/../b/./c/")
        assertAllowed("http://127.0.0.1", "http:127.0.0.1")
        // Eine leere Benutzerangabe ist keine.
        assertAllowed("http://127.0.0.1", "http://@127.0.0.1")
        // Breite Ziffern (U+FF11, U+FF12, U+FF17) liest die Netzbibliothek als 127: Geprüft wird, was sie anspricht.
        assertAllowed("http://127.0.0.1", "http://" + Char(0xFF11) + Char(0xFF12) + Char(0xFF17) + ".0.0.1")
    }

    @Test
    fun `eine bereinigte Adresse bleibt beim erneuten Bereinigen gleich`() {
        val inputs = listOf(
            "https://generativelanguage.googleapis.com/",
            "HTTPS://API.Example.COM:443/V1/Chat/",
            "http://LOCALHOST:11434/v1/",
            "http://[0:0:0:0:0:0:0:1]:8080/",
            "http://[::ffff:127.0.0.1]/v1/",
            "https://example.com//",
            "https://bücher.example/",
        )
        for (input in inputs) {
            val once = checkNotNull(EndpointPolicy.normalize(input)) { "Eingabe: $input" }
            assertEquals(once, EndpointPolicy.normalize(once), "Eingabe: $input")
        }
    }

    // ---------- Abgelehnt ----------

    @Test
    fun `http zu anderen Rechnern wird abgelehnt`() {
        assertRejected("http://example.com")
        assertRejected("http://generativelanguage.googleapis.com")
        assertRejected("http://8.8.8.8")
        assertRejected("http://0.0.0.0")
        assertRejected("http://255.255.255.255")
        assertRejected("http://169.254.1.1")
        assertRejected("http://100.64.0.1")
        // Knapp neben 127.0.0.0/8 und 10.0.0.0/8.
        assertRejected("http://126.255.255.255")
        assertRejected("http://128.0.0.0")
        assertRejected("http://9.255.255.255")
        assertRejected("http://11.0.0.0")
        // Knapp neben 172.16.0.0/12 und 192.168.0.0/16.
        assertRejected("http://172.15.255.255")
        assertRejected("http://172.32.0.0")
        assertRejected("http://192.167.255.255")
        assertRejected("http://192.169.0.0")
        // Bei IPv6 ist allein das eigene Gerät erlaubt, auch keine IPv6-Adressen aus dem eigenen Netz.
        assertRejected("http://[::]")
        assertRejected("http://[::2]")
        assertRejected("http://[fe80::1]")
        assertRejected("http://[fc00::1]")
        assertRejected("http://[fd12:3456:789a::1]")
        assertRejected("http://[2001:db8::1]")
        assertRejected("http://[::ffff:8.8.8.8]")
    }

    @Test
    fun `Namen, die nur wie das eigene Geraet aussehen, werden abgelehnt`() {
        assertRejected("http://127.0.0.1.example.com")
        assertRejected("http://localhost.example.com")
        assertRejected("http://10.0.0.1.example.com")
        assertRejected("http://192.168.1.20.nip.io")
        assertRejected("http://www.10.0.0.1")
        assertRejected("http://mein-localhost")
        assertRejected("http://localhost.localdomain")
        // Ein Punkt am Ende macht aus der Zahlenadresse einen Namen, der nachgeschlagen würde.
        assertRejected("http://localhost.")
        assertRejected("http://127.0.0.1.")
        // Die eigene Adresse steht nur im Pfad, der Rechner ist ein anderer.
        assertRejected("http://example.com/127.0.0.1")
        assertRejected("http://example.com/localhost")
        // Verkleidete Punkte: „%2e“ und der ostasiatische Punkt (U+3002) werden beim Lesen zu einem Punkt.
        assertRejected("http://127.0.0.1%2eexample.com")
        assertRejected("http://127.0.0.1" + Char(0x3002) + "example.com")
    }

    @Test
    fun `andere Schreibweisen von Zahlenadressen gelten nicht als eigenes Geraet`() {
        // Manche Systeme lesen diese Formen als 127.0.0.1, andere als Namen oder als ganz andere Adresse.
        assertRejected("http://127.1")
        assertRejected("http://2130706433")
        assertRejected("http://0x7f.0.0.1")
        assertRejected("http://0x7f000001")
        assertRejected("http://0177.0.0.1")
        assertRejected("http://127.0.0.01")
        // Mit führender Null könnte 010 als Oktalzahl 8 gelesen werden: 8.0.0.1 läge im Internet.
        assertRejected("http://010.0.0.1")
        assertRejected("http://192.168.01.1")
        // Zu wenige oder zu viele Teile, zu große Zahlen.
        assertRejected("http://10.0.0")
        assertRejected("http://192.168.1")
        assertRejected("http://10.0.0.1.2")
        assertRejected("http://127.0.0.256")
        assertRejected("http://1270.0.0.1")
    }

    @Test
    fun `Adressen mit Benutzerangaben werden abgelehnt`() {
        assertRejected("https://nutzer@example.com")
        assertRejected("https://nutzer:geheim@example.com/v1")
        assertRejected("https://:geheim@example.com")
        assertRejected("http://nutzer@localhost:11434")
        // Der Klassiker: Vor dem Klammeraffen steht nur ein Benutzername, der Rechner kommt danach.
        assertRejected("http://127.0.0.1@example.com")
        assertRejected("http://127.0.0.1:80@example.com")
        assertRejected("http://localhost@example.com")
        assertRejected("https://example.com@127.0.0.1")
    }

    @Test
    fun `Leerraum, Steuerzeichen und Rueckstriche werden abgelehnt`() {
        // Einen Rückstrich liest die Netzbibliothek als Schrägstrich; was davor steht, wäre der Rechner.
        assertRejected("http://127.0.0.1\\@example.com")
        assertRejected("http://example.com\\@127.0.0.1")
        assertRejected("https://example.com\\v1")
        assertRejected("http://127.0.0.1 .example.com")
        assertRejected("https://exa mple.com")
        assertRejected("https://example.com/a b")
        assertRejected("https://example.com/\tv1")
        assertRejected("https://example.com/v1\nHost: example.org")
        assertRejected("https://example.com/" + Char(0) + "v1")
        assertRejected("https://example.com/v1" + Char(0x7F))
    }

    @Test
    fun `Abfragen und Sprungmarken werden abgelehnt`() {
        assertRejected("https://example.com/v1?key=abc")
        assertRejected("https://example.com/?")
        assertRejected("http://localhost:11434/v1?x=1")
        assertRejected("https://example.com/v1#teil")
        assertRejected("https://example.com/#")
        // Auch hier steht die eigene Adresse nicht dort, wo der Rechner steht.
        assertRejected("http://example.com#@127.0.0.1")
        assertRejected("http://example.com?@127.0.0.1")
        assertRejected("http://127.0.0.1#@example.com")
    }

    @Test
    fun `nur vollstaendige Adressen mit http oder https sind erlaubt`() {
        assertRejected("")
        assertRejected("   ")
        assertRejected("https://")
        assertRejected("http://")
        assertRejected("https:///")
        // Ohne Protokoll.
        assertRejected("localhost:11434")
        assertRejected("example.com/v1")
        assertRejected("//example.com")
        // Andere Protokolle.
        assertRejected("ftp://example.com")
        assertRejected("file:///etc/hosts")
        assertRejected("ws://localhost")
        assertRejected("wss://example.com")
        assertRejected("javascript:alert(1)")
        // Kaputte Ports und Klammern.
        assertRejected("https://example.com:99999")
        assertRejected("https://example.com:0")
        assertRejected("https://example.com:port")
        assertRejected("http://localhost:11434:11434")
        assertRejected("http://[::1")
        assertRejected("http://::1")
    }

    // ---------- Rechnername ----------

    @Test
    fun `host nennt den Rechner so, wie er angesprochen wird`() {
        assertEquals("generativelanguage.googleapis.com", EndpointPolicy.host(GeminiTextGenerator.DEFAULT_BASE_URL))
        assertEquals("api.example.com", EndpointPolicy.host("https://API.Example.com:8443/v1/"))
        assertEquals("192.168.1.20", EndpointPolicy.host("http://192.168.1.20:11434/v1"))
        assertEquals("localhost", EndpointPolicy.host("http://localhost:11434"))
        assertEquals("::1", EndpointPolicy.host("http://[::1]:11434"))
        assertEquals("127.0.0.1", EndpointPolicy.host("http://[::ffff:127.0.0.1]"))
        assertEquals("xn--bcher-kva.example", EndpointPolicy.host("https://bücher.example"))
    }

    @Test
    fun `host liefert nichts fuer Adressen, die nicht erlaubt sind`() {
        assertNull(EndpointPolicy.host("http://example.com"))
        assertNull(EndpointPolicy.host("http://127.0.0.1.example.com"))
        assertNull(EndpointPolicy.host("https://nutzer@example.com"))
        assertNull(EndpointPolicy.host("example.com"))
        assertNull(EndpointPolicy.host(""))
    }
}

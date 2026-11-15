package io.github.q0ochen.schafkopf.domain.summary

/*
 * Ein kleiner JSON-Schreiber, damit die Fachlogik ohne JSON-Bibliothek auskommt. Er schreibt kompakt (ohne
 * Leerraum) und lässt leere Angaben weg: `null`, leere Texte, leere Listen und leere Objekte.
 */

/** Schreibt ein JSON-Objekt. */
internal fun jsonObject(build: JsonObjectBuilder.() -> Unit): String = JsonObjectBuilder().apply(build).toString()

/** Sammelt die Felder eines JSON-Objekts in der Reihenfolge, in der sie eingetragen werden. */
internal class JsonObjectBuilder {
    private val members = ArrayList<String>()

    val isEmpty: Boolean get() = members.isEmpty()

    fun string(name: String, value: String?) {
        if (!value.isNullOrEmpty()) add(name, jsonString(value))
    }

    fun number(name: String, value: Int?) {
        if (value != null) add(name, value.toString())
    }

    fun boolean(name: String, value: Boolean?) {
        if (value != null) add(name, value.toString())
    }

    fun strings(name: String, values: List<String>) {
        val kept = values.filter { it.isNotEmpty() }
        if (kept.isNotEmpty()) add(name, kept.joinToString(",", "[", "]") { jsonString(it) })
    }

    fun obj(name: String, build: JsonObjectBuilder.() -> Unit) {
        val child = JsonObjectBuilder().apply(build)
        if (!child.isEmpty) add(name, child.toString())
    }

    fun <T> objects(name: String, items: List<T>, build: JsonObjectBuilder.(T) -> Unit) {
        val kept = items.map { item -> JsonObjectBuilder().apply { build(item) } }.filterNot { it.isEmpty }
        if (kept.isNotEmpty()) add(name, kept.joinToString(",", "[", "]"))
    }

    private fun add(name: String, json: String) {
        members += jsonString(name) + ":" + json
    }

    override fun toString(): String = members.joinToString(",", "{", "}")
}

/**
 * Ein Text als JSON-Zeichenkette in Anführungszeichen. Anführungszeichen, Backslash und Steuerzeichen werden
 * maskiert; alle übrigen Zeichen (auch Umlaute, das Euro-Zeichen und Emojis) bleiben, wie sie sind.
 */
internal fun jsonString(text: String): String {
    val out = StringBuilder(text.length + 2)
    out.append('"')
    for (char in text) {
        when (char) {
            '"' -> out.append("\\\"")
            '\\' -> out.append("\\\\")
            '\n' -> out.append("\\n")
            '\r' -> out.append("\\r")
            '\t' -> out.append("\\t")
            '\b' -> out.append("\\b")
            '\u000C' -> out.append("\\f")
            else -> if (char < ' ') out.append(unicodeEscape(char)) else out.append(char)
        }
    }
    out.append('"')
    return out.toString()
}

private fun unicodeEscape(char: Char): String = "\\u" + char.code.toString(16).padStart(4, '0')

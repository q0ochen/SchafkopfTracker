package io.github.q0ochen.schafkopf.data.drive

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.time.Instant

/** Warum ein Aufruf bei Google Drive nicht geklappt hat. Die Oberfläche zeigt zu jedem Fall einen Hinweis. */
enum class DriveError {
    /** Nicht (mehr) angemeldet, oder die Anmeldung gilt nicht mehr: neu mit Google verbinden. */
    SIGN_IN_REQUIRED,

    /** Google verweigert den Zugriff (zum Beispiel fehlt die Freigabe für Drive). */
    ACCESS_DENIED,

    /** Der Speicherplatz in Drive ist voll. */
    STORAGE_FULL,

    /** Zu viele Anfragen; später noch einmal. */
    RATE_LIMITED,

    /** Die Datei gibt es nicht (mehr). */
    NOT_FOUND,

    /** Google ist gerade nicht erreichbar oder überlastet. */
    SERVER_UNAVAILABLE,

    /** Kein Netz oder Zeitüberschreitung. */
    NETWORK,

    /** Die Datei ist zu groß für eine Sicherung dieser App. */
    TOO_LARGE,

    /** Alles andere. */
    UNKNOWN,
}

/** Ergebnis eines Aufrufs bei Google Drive; nie eine Ausnahme (außer dem Abbruch der Coroutine). */
sealed interface DriveResult<out T> {
    data class Success<T>(val value: T) : DriveResult<T>

    /** @property detail Kurzer technischer Zusatz (zum Beispiel „HTTP 503“); nie ein Schlüssel oder Inhalt. */
    data class Failure(val error: DriveError, val detail: String = "") : DriveResult<Nothing>
}

/** Eine Datei in Google Drive. */
data class DriveFile(val id: String, val name: String, val modifiedTime: Instant? = null, val size: Long? = null)

/**
 * Die wenigen Aufrufe der Drive-Schnittstelle (REST, Version 3), die die Sicherung braucht: Ordner suchen und
 * anlegen, eine Datei hochladen oder ersetzen, Dateien auflisten und herunterladen. Ohne Bibliothek von Google,
 * damit die App F-Droid-tauglich bleibt.
 *
 * Mit der Freigabe `drive.file` sieht die App nur, was sie selbst angelegt hat; an andere Dateien im Drive des
 * Nutzers kommt sie nicht heran.
 *
 * @param accessToken Liefert ein gültiges Zugriffstoken, oder `null`, wenn niemand angemeldet ist. Es steht nur in
 *   der Kopfzeile `Authorization`, nie in einer Adresse, einem Fehler oder einem Protokoll.
 * @param baseUrl Adresse der Schnittstelle; Tests setzen ihren eigenen Server ein.
 */
class GoogleDriveClient(
    client: OkHttpClient,
    private val accessToken: suspend () -> String?,
    baseUrl: String = DEFAULT_BASE_URL,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    /** Folgt keiner Weiterleitung: Das Token soll nirgendwo anders hingehen. */
    private val http: OkHttpClient = client.newBuilder().followRedirects(false).followSslRedirects(false).build()
    private val base: HttpUrl = baseUrl.toHttpUrl()

    /** Der Ordner mit diesem Namen (nicht im Papierkorb); `null`, wenn es ihn nicht gibt. */
    suspend fun findFolder(name: String): DriveResult<DriveFile?> =
        query("name = '${escape(name)}' and mimeType = '$FOLDER_TYPE' and trashed = false").map { it.firstOrNull() }

    suspend fun createFolder(name: String): DriveResult<DriveFile> = call {
        val metadata = buildJsonObject {
            put("name", name)
            put("mimeType", FOLDER_TYPE)
        }
        val request = Request.Builder()
            .url(apiUrl("drive/v3/files").withFields().build())
            .post(metadata.toString().toRequestBody(JSON_TYPE))
        fileFrom(send(request))
    }

    /** Die Dateien mit diesem Namen im Ordner, die neueste zuerst. */
    suspend fun findFiles(folderId: String, name: String): DriveResult<List<DriveFile>> =
        query("name = '${escape(name)}' and '${escape(folderId)}' in parents and trashed = false")

    /** Alle Dateien im Ordner, die neueste zuerst. */
    suspend fun listFiles(folderId: String): DriveResult<List<DriveFile>> =
        query("'${escape(folderId)}' in parents and trashed = false and mimeType != '$FOLDER_TYPE'")

    /** Legt eine neue JSON-Datei im Ordner an. */
    suspend fun createFile(folderId: String, name: String, content: String): DriveResult<DriveFile> = call {
        val metadata = buildJsonObject {
            put("name", name)
            put("mimeType", JSON_MIME)
            put("parents", JsonArray(listOf(JsonPrimitive(folderId))))
        }
        val body = MultipartBody.Builder()
            .setType(RELATED_TYPE)
            .addPart(metadata.toString().toRequestBody(JSON_TYPE))
            .addPart(content.toRequestBody(JSON_TYPE))
            .build()
        val request = Request.Builder()
            .url(apiUrl("upload/drive/v3/files").addQueryParameter("uploadType", "multipart").withFields().build())
            .post(body)
        fileFrom(send(request))
    }

    /**
     * Ersetzt den Inhalt einer Datei. Drive hebt den alten Inhalt als frühere Version auf, und der neue gilt erst,
     * wenn er vollständig angekommen ist: Ein abgebrochenes Hochladen hinterlässt keine halbe Datei.
     */
    suspend fun updateFile(fileId: String, content: String): DriveResult<DriveFile> = call {
        val request = Request.Builder()
            .url(apiUrl("upload/drive/v3/files").addPathSegment(fileId).addQueryParameter("uploadType", "media").withFields().build())
            .patch(content.toRequestBody(JSON_TYPE))
        fileFrom(send(request))
    }

    /** Lädt eine Datei als Text herunter; größere als [maxBytes] werden abgelehnt. */
    suspend fun download(fileId: String, maxBytes: Long): DriveResult<String> = call {
        val request = Request.Builder().url(apiUrl("drive/v3/files").addPathSegment(fileId).addQueryParameter("alt", "media").build()).get()
        val answer = send(request, maxBytes)
        if (answer.tooLarge) DriveResult.Failure(DriveError.TOO_LARGE) else checked(answer) { DriveResult.Success(answer.body) }
    }

    private suspend fun query(q: String): DriveResult<List<DriveFile>> = call {
        val url = apiUrl("drive/v3/files")
            .addQueryParameter("q", q)
            .addQueryParameter("spaces", "drive")
            .addQueryParameter("orderBy", "modifiedTime desc")
            .addQueryParameter("pageSize", PAGE_SIZE)
            .addQueryParameter("fields", "files($FILE_FIELDS)")
            .build()
        val answer = send(Request.Builder().url(url).get())
        checked(answer) {
            val list = JSON.decodeFromString(FileList.serializer(), answer.body)
            DriveResult.Success(list.files.mapNotNull { it.toDriveFile() })
        }
    }

    private fun fileFrom(answer: Answer): DriveResult<DriveFile> = checked(answer) {
        JSON.decodeFromString(FileDto.serializer(), answer.body).toDriveFile()
            ?.let { DriveResult.Success(it) }
            ?: DriveResult.Failure(DriveError.UNKNOWN, "keine Kennung")
    }

    private fun apiUrl(path: String): HttpUrl.Builder = base.newBuilder().addPathSegments(path)

    private fun HttpUrl.Builder.withFields(): HttpUrl.Builder = addQueryParameter("fields", FILE_FIELDS)

    private class Answer(val status: Int, val body: String, val tooLarge: Boolean = false)

    /** Schickt die Anfrage mit dem Token; ohne Token wird nichts gesendet. */
    private suspend fun send(builder: Request.Builder, maxBytes: Long = MAX_RESPONSE_BYTES): Answer {
        val token = accessToken() ?: throw SignInRequired()
        val request = builder.header("Authorization", "Bearer $token").build()
        return withContext(dispatcher) {
            http.newCall(request).execute().use { response ->
                val source = response.body.source()
                // Mehr als erlaubt: nicht weiterlesen.
                if (source.request(maxBytes + 1)) return@use Answer(response.code, "", tooLarge = true)
                Answer(response.code, source.readUtf8())
            }
        }
    }

    private fun <T> checked(answer: Answer, onSuccess: () -> DriveResult<T>): DriveResult<T> =
        if (answer.status in 200..299) onSuccess() else DriveResult.Failure(errorOf(answer), "HTTP ${answer.status}")

    private fun errorOf(answer: Answer): DriveError = when (answer.status) {
        401 -> DriveError.SIGN_IN_REQUIRED
        403 -> when {
            "storageQuotaExceeded" in answer.body -> DriveError.STORAGE_FULL
            "ateLimitExceeded" in answer.body -> DriveError.RATE_LIMITED
            else -> DriveError.ACCESS_DENIED
        }
        404 -> DriveError.NOT_FOUND
        429 -> DriveError.RATE_LIMITED
        in 500..599 -> DriveError.SERVER_UNAVAILABLE
        else -> DriveError.UNKNOWN
    }

    /** Macht aus allem, was schiefgeht, ein Ergebnis; nur der Abbruch der Coroutine geht durch. */
    private suspend fun <T> call(work: suspend () -> DriveResult<T>): DriveResult<T> = try {
        work()
    } catch (e: CancellationException) {
        throw e
    } catch (_: SignInRequired) {
        DriveResult.Failure(DriveError.SIGN_IN_REQUIRED)
    } catch (e: IOException) {
        DriveResult.Failure(DriveError.NETWORK, e.javaClass.simpleName)
    } catch (e: Exception) {
        DriveResult.Failure(DriveError.UNKNOWN, e.javaClass.simpleName)
    }

    private class SignInRequired : Exception()

    @Serializable
    private class FileList(val files: List<FileDto> = emptyList())

    @Serializable
    private class FileDto(
        val id: String? = null,
        val name: String? = null,
        @SerialName("modifiedTime") val modifiedTime: String? = null,
        val size: String? = null,
    ) {
        fun toDriveFile(): DriveFile? {
            val id = id?.takeIf { it.isNotBlank() } ?: return null
            return DriveFile(
                id = id,
                name = name.orEmpty(),
                modifiedTime = modifiedTime?.let { runCatching { Instant.parse(it) }.getOrNull() },
                size = size?.toLongOrNull(),
            )
        }
    }

    companion object {
        const val DEFAULT_BASE_URL = "https://www.googleapis.com/"

        /** Die einzige Freigabe, die die App verlangt: nur die eigenen Dateien. */
        const val SCOPE_DRIVE_FILE = "https://www.googleapis.com/auth/drive.file"

        private const val FOLDER_TYPE = "application/vnd.google-apps.folder"
        private const val JSON_MIME = "application/json"
        private const val FILE_FIELDS = "id,name,modifiedTime,size"
        private const val PAGE_SIZE = "50"
        private const val MAX_RESPONSE_BYTES = 1L * 1024 * 1024
        private val JSON_TYPE = "$JSON_MIME; charset=utf-8".toMediaType()
        private val RELATED_TYPE = "multipart/related".toMediaType()
        private val JSON = Json { ignoreUnknownKeys = true }

        /** Hochkomma und Rückstrich müssen in Suchausdrücken von Drive mit einem Rückstrich geschützt werden. */
        internal fun escape(value: String): String = value.replace("\\", "\\\\").replace("'", "\\'")
    }
}

private inline fun <T, R> DriveResult<T>.map(transform: (T) -> R): DriveResult<R> = when (this) {
    is DriveResult.Success -> DriveResult.Success(transform(value))
    is DriveResult.Failure -> this
}

package io.github.q0ochen.schafkopf.app.drive

import android.content.Intent
import io.github.q0ochen.schafkopf.data.drive.DriveBackup
import io.github.q0ochen.schafkopf.data.drive.GoogleDriveClient
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import java.io.IOException

/**
 * Ein nachgebautes Google Drive im Arbeitsspeicher: beantwortet die wenigen Aufrufe der Sicherung, ohne ins Netz zu
 * gehen. [failWith] lässt jeden Aufruf mit diesem HTTP-Status scheitern, [offline] mit einem Netzfehler.
 */
class FakeDriveServer {
    var folderId: String? = null
    val files = LinkedHashMap<String, String>()
    var failWith: Int? = null
    var offline = false
    val authorizations = ArrayList<String?>()
    private var next = 0

    val client: OkHttpClient = OkHttpClient.Builder().addInterceptor { chain ->
        val request = chain.request()
        authorizations += request.header("Authorization")
        if (offline) throw IOException("kein Netz")
        fun answer(code: Int, body: String) = Response.Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .code(code)
            .message("x")
            .body(body.toResponseBody("application/json".toMediaType()))
            .build()
        failWith?.let { return@addInterceptor answer(it, """{"error":{"code":$it}}""") }
        val path = request.url.encodedPath
        val q = request.url.queryParameter("q").orEmpty()
        fun file(id: String) = """{"id":"$id","name":"${DriveBackup.FILE_NAME}","modifiedTime":"2026-11-16T20:00:00Z"}"""
        when {
            request.method == "GET" && path == "/drive/v3/files" && q.contains("vnd.google-apps.folder") ->
                answer(200, """{"files":[${folderId?.let { """{"id":"$it","name":"${DriveBackup.FOLDER_NAME}"}""" }.orEmpty()}]}""")
            request.method == "GET" && path == "/drive/v3/files" ->
                answer(200, """{"files":[${files.keys.reversed().joinToString(",") { file(it) }}]}""")
            request.method == "POST" && path == "/drive/v3/files" -> {
                folderId = "ordner-${++next}"
                answer(200, """{"id":"$folderId","name":"${DriveBackup.FOLDER_NAME}"}""")
            }
            request.method == "POST" && path == "/upload/drive/v3/files" -> {
                val id = "datei-${++next}"
                files[id] = contentPart(request)
                answer(200, file(id))
            }
            request.method == "PATCH" && path.startsWith("/upload/drive/v3/files/") -> {
                val id = path.substringAfterLast('/')
                files[id] = Buffer().also { request.body!!.writeTo(it) }.readUtf8()
                answer(200, file(id))
            }
            request.method == "GET" && path.startsWith("/drive/v3/files/") ->
                files[path.substringAfterLast('/')]?.let { answer(200, it) } ?: answer(404, "{}")
            else -> answer(400, "{}")
        }
    }.build()

    /** Der zweite Teil einer Mehrteil-Anfrage (der erste sind die Angaben zur Datei). */
    private fun contentPart(request: okhttp3.Request): String {
        val boundary = request.body!!.contentType()!!.parameter("boundary")!!
        val body = Buffer().also { request.body!!.writeTo(it) }.readUtf8()
        val part = body.split("--$boundary")[2]
        return part.substringAfter("\r\n\r\n").removeSuffix("\r\n")
    }

    fun backup(token: () -> String?): DriveBackup = DriveBackup(GoogleDriveClient(client, { token() }, "https://drive.test/"))
}

/** Anmeldung zum Testen: [signInSucceeds] entscheidet, ob die Anmeldung klappt. */
class FakeDriveAccount(override val configured: Boolean = true, signedIn: Boolean = false) : DriveAccount {
    private val state = MutableStateFlow(DriveSignIn(signedIn, if (signedIn) "runde@example.com" else null))
    override val status: StateFlow<DriveSignIn> = state
    var signInSucceeds = true
    var token: String? = "TOKEN"
    var signedOut = false

    override suspend fun load() = Unit

    override fun signInIntent(): Intent {
        if (noBrowser) throw android.content.ActivityNotFoundException("kein Browser")
        return Intent("anmelden")
    }

    var result = SignInResult.OK
    var noBrowser = false

    override suspend fun completeSignIn(result: Intent?): SignInResult {
        if (!signInSucceeds) return SignInResult.FAILED
        if (this.result == SignInResult.OK) state.value = DriveSignIn(true, "runde@example.com")
        return this.result
    }

    override suspend fun accessToken(): String? = if (state.value.signedIn) token else null

    override suspend fun signOut() {
        signedOut = true
        state.value = DriveSignIn()
    }
}

package app.voidgram
import kotlinx.serialization.json.decodeFromJsonElement

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

val AppJson = Json { ignoreUnknownKeys = true; explicitNulls = false; encodeDefaults = true }

class ApiException(val status: Int, message: String) : Exception(message)

class Api {
    var baseUrl: String = ""
    var token: String? = null

    val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .pingInterval(25, TimeUnit.SECONDS)
        .addInterceptor { chain ->
            val t = token
            val req = if (t != null && chain.request().header("Authorization") == null)
                chain.request().newBuilder().header("Authorization", "Bearer $t").build()
            else chain.request()
            chain.proceed(req)
        }
        .build()

    private val jsonType = "application/json; charset=utf-8".toMediaType()

    private fun url(path: String) = baseUrl.trimEnd('/') + path

    private fun parse(resp: Response): JsonElement {
        val text = resp.body?.string().orEmpty()
        if (!resp.isSuccessful) {
            val msg = runCatching { AppJson.parseToJsonElement(text).jsonObject["error"]?.jsonPrimitive?.content }.getOrNull()
            throw ApiException(resp.code, msg ?: "HTTP ${resp.code}")
        }
        return AppJson.parseToJsonElement(text)
    }

    suspend fun call(method: String, path: String, body: JsonObject? = null): JsonElement =
        withContext(Dispatchers.IO) {
            val rb: RequestBody? = if (method == "GET" || method == "DELETE" && body == null) null
            else (body?.toString() ?: "{}").toRequestBody(jsonType)
            val req = Request.Builder().url(url(path)).method(method, rb).build()
            try {
                client.newCall(req).execute().use { parse(it) }
            } catch (e: java.io.IOException) {
                throw ApiException(0, "Нет соединения с сервером")
            }
        }

    suspend inline fun <reified T> get(path: String): T = AppJson.decodeFromJsonElement(call("GET", path))
    suspend inline fun <reified T> send(method: String, path: String, body: JsonObject? = null): T =
        AppJson.decodeFromJsonElement(call(method, path, body))

    suspend fun upload(bytes: ByteArray, mime: String, filename: String): Media = withContext(Dispatchers.IO) {
        val req = Request.Builder().url(url("/api/upload"))
            .header("X-Filename", URLEncoder.encode(filename, "UTF-8").replace("+", "%20"))
            .post(bytes.toRequestBody(mime.toMediaType()))
            .build()
        try {
            client.newCall(req).execute().use {
                AppJson.decodeFromJsonElement<MediaResponse>(parse(it)).media
            }
        } catch (e: java.io.IOException) {
            throw ApiException(0, "Нет соединения с сервером")
        }
    }

    fun mediaUrl(id: String) = url("/media/$id")

    fun openSocket(onEvent: (JsonObject) -> Unit, onClosed: () -> Unit): WebSocket {
        val wsUrl = url("/ws").replaceFirst("http", "ws") + "?token=" + token
        return client.newWebSocket(Request.Builder().url(wsUrl).build(), object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) {
                runCatching { AppJson.parseToJsonElement(text).jsonObject }.getOrNull()?.let(onEvent)
            }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = onClosed()
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) = onClosed()
        })
    }
}

package dev.varch.controller.net

import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject

/** An error the daemon reported, with a message fit to show as-is. */
class ApiException(message: String, val status: Int) : IOException(message)

class VarchClient(
    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(4, TimeUnit.SECONDS)
        .pingInterval(10, TimeUnit.SECONDS)
        .build(),
) {
    /** Returns the desktop's host name, proving a daemon is listening there. */
    suspend fun hostName(endpoint: Endpoint): String =
        call(Request.Builder().url(endpoint.http("/v1/info")).build()).optString("name")

    /** Makes the desktop show a pairing code; returns the pairing id. */
    suspend fun startPairing(endpoint: Endpoint, device: String): String =
        post(endpoint, "/v1/pair/start", JSONObject().put("device", device)).getString("pairing_id")

    /** Exchanges the code for a long-lived token. */
    suspend fun finishPairing(endpoint: Endpoint, pairingId: String, code: String): String =
        post(endpoint, "/v1/pair/finish", JSONObject().put("pairing_id", pairingId).put("code", code)).getString("token")

    suspend fun unpair(endpoint: Endpoint, token: String) {
        post(endpoint, "/v1/unpair", JSONObject(), token)
    }

    /** Performs one action without holding a connection open, as widgets and tiles do. */
    suspend fun action(endpoint: Endpoint, token: String, action: String, value: Double = 0.0, text: String = ""): ServerMessage.Result =
        parseResult(post(endpoint, "/v1/action", actionBody(action, value, text), token))

    suspend fun status(endpoint: Endpoint, token: String): SystemState =
        parseSystem(call(authorized(endpoint, "/v1/status", token).build()))

    /** Fetches an image endpoint such as /v1/screenshot or /v1/art. */
    suspend fun bytes(endpoint: Endpoint, token: String, path: String): ByteArray = withContext(Dispatchers.IO) {
        http.newCall(authorized(endpoint, path, token).build()).execute().use { response ->
            if (!response.isSuccessful) throw ApiException("HTTP ${response.code}", response.code)
            response.body.bytes()
        }
    }

    private fun authorized(endpoint: Endpoint, path: String, token: String) =
        Request.Builder().url(endpoint.http(path)).header("Authorization", "Bearer $token")

    fun open(endpoint: Endpoint, token: String, listener: WebSocketListener): WebSocket =
        http.newWebSocket(authorized(endpoint, "/v1/ws", token).build(), listener)

    private suspend fun post(endpoint: Endpoint, path: String, body: JSONObject, token: String? = null): JSONObject {
        val request = Request.Builder()
            .url(endpoint.http(path))
            .post(body.toString().toRequestBody(JSON))
        if (token != null) request.header("Authorization", "Bearer $token")
        return call(request.build())
    }

    private suspend fun call(request: Request): JSONObject = withContext(Dispatchers.IO) {
        http.newCall(request).execute().use { response ->
            val json = runCatching { JSONObject(response.body.string()) }.getOrNull()
            if (!response.isSuccessful) {
                throw ApiException(json?.optString("error")?.ifEmpty { null } ?: "HTTP ${response.code}", response.code)
            }
            json ?: throw IOException("Unexpected response from ${request.url.host}")
        }
    }

    private companion object {
        val JSON = "application/json".toMediaType()
    }
}

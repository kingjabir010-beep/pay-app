package com.piprapay.liteforwarder.net

import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.TimeUnit

// Plain HttpURLConnection on purpose: zero extra Gradle dependencies
// means one less thing that can fail to resolve during a CI build.
// Every call is synchronous — always run this from a background
// thread/coroutine (Worker, IO dispatcher), never from the main thread.
object ApiClient {

    data class Result(val ok: Boolean, val code: Int, val body: JSONObject?, val error: String? = null)

    private fun request(urlStr: String, method: String, jsonBody: JSONObject?, headers: Map<String, String> = emptyMap()): Result {
        return try {
            val url = URL(urlStr)
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = method
            conn.connectTimeout = TimeUnit.SECONDS.toMillis(15).toInt()
            conn.readTimeout = TimeUnit.SECONDS.toMillis(20).toInt()
            conn.setRequestProperty("Content-Type", "application/json")
            headers.forEach { (k, v) -> conn.setRequestProperty(k, v) }

            if (jsonBody != null) {
                conn.doOutput = true
                OutputStreamWriter(conn.outputStream, Charsets.UTF_8).use { it.write(jsonBody.toString()) }
            }

            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.let { s ->
                BufferedReader(InputStreamReader(s, Charsets.UTF_8)).use { it.readText() }
            } ?: ""

            val json = try { if (text.isNotBlank()) JSONObject(text) else null } catch (e: Exception) { null }
            Result(ok = code in 200..299, code = code, body = json)
        } catch (e: Exception) {
            Result(ok = false, code = -1, body = null, error = e.message ?: e.toString())
        }
    }

    /** Exchanges a one-time-password (shown on the dashboard) for a permanent device token. */
    fun deviceLogin(baseUrl: String, otp: String, deviceModel: String): Result {
        val body = JSONObject().apply {
            put("otp", otp)
            put("model", deviceModel)
            put("app_version", "1.0")
        }
        return request("$baseUrl/api/device-login", "POST", body)
    }

    /** Forwards one captured SMS to the webhook, authenticated with the paired device token. */
    fun sendSms(baseUrl: String, deviceToken: String, sender: String, message: String, receivedAtIso: String): Result {
        val body = JSONObject().apply {
            put("message", message)
            put("from", sender)
            put("receivedAt", receivedAtIso)
        }
        val url = "$baseUrl/api/webhook?device_token=$deviceToken"
        return request(url, "POST", body)
    }

    fun ping(baseUrl: String): Result = request("$baseUrl/api/ping", "GET", null)
}

package eu.kanade.tachiyomi.extension.ru.agnamer

import android.util.Base64
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.NetworkHelper
import eu.kanade.tachiyomi.network.POST
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.CacheControl
import okhttp3.Headers
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit

class ExMangaManager(
    private var actualToken: String,
    private val email: String,
    private val password: String,
    private val preferenceTokenSetter: (newToken: String) -> Unit,
    network: NetworkHelper,
) {
    private val url = "https://api.exmanga.org"

    private val headers =
        Headers.Builder().set("User-Agent", "Tachiyomi").set("Version", "4").build()

    private val json = Json

    private var expiredTokenUnixTimestamp = try {
        getTokenExpiredUnixTimestamp(actualToken)
    } catch (error: Exception) {
        0
    }

    private val isTokenNotExpired
        get() = expiredTokenUnixTimestamp >= System.currentTimeMillis() / 1000

    private val client = network.cloudflareClient.newBuilder().build()

    fun getToken(): String {
        if (isTokenNotExpired) {
            return actualToken
        }

        return tokenRequest().let {
            actualToken = it
            expiredTokenUnixTimestamp = getTokenExpiredUnixTimestamp(it)
            preferenceTokenSetter(it)
            it
        }
    }

    fun handleResponse(response: Response): JsonElement {
        val jsonElement = json.parseToJsonElement(response.peekBody(Long.MAX_VALUE).string())

        val data = jsonElement.jsonObject["data"]

        if (data == null) {
            val message =
                jsonElement.jsonObject["message"]?.jsonPrimitive?.contentOrNull?.let { ": $it" }
                    ?: ""
            throw IOException("Ошибка ExManga$message")
        }

        return data
    }

    fun imageRequest(imageUrl: String) = GET(imageUrl, headers)

    fun pageListRequest(chapterUrl: String) = GET(url + chapterUrl, headers)

    fun chapterRequest(body: String) = Request.Builder().url("$url/chapter").put(
        body.toRequestBody(
            "application/json; charset=utf-8".toMediaTypeOrNull(),
        ),
    ).headers(headers.newBuilder().add("Content-Type", "application/json").build())
        .cacheControl(CacheControl.Builder().maxAge(10, TimeUnit.MINUTES).build()).build()

    fun searchMangaRequest(page: Int, query: String) = GET(
        "$url/manga?take=20&skip=${10 * (page - 1)}&name=$query",
        headers,
    )

    fun fetchChapterListRequest(mangaId: Long?) = GET("$url/chapter/history/$mangaId", headers)

    private fun getTokenExpiredUnixTimestamp(token: String): Int {
        if (token.isBlank()) {
            return 0
        }

        val payload = token.substringAfter(".").substringBefore(".")

        val payloadJsonString = Base64.decode(payload, Base64.DEFAULT).decodeToString()

        val jsonElement = json.parseToJsonElement(payloadJsonString)

        return jsonElement.jsonObject["exp"]!!.jsonPrimitive.int
    }

    private fun tokenRequest(): String {
        if (email == "" || password == "") {
            throw IOException("Введите email и password для ExManga")
        }

        val jsonObject = buildJsonObject {
            put("email", email)
            put("password", password)
        }

        val body = jsonObject.toString()
            .toRequestBody("application/json; charset=utf-8".toMediaTypeOrNull())

        val response = client.newCall(POST("$url/auth/login", headers, body)).execute()

        return handleResponse(response).jsonPrimitive.content
    }
}

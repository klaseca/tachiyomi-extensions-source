package eu.kanade.tachiyomi.extension.ru.agnamer

import android.util.Base64
import eu.kanade.tachiyomi.extension.ru.agnamer.dto.ExBookDto
import eu.kanade.tachiyomi.extension.ru.agnamer.dto.ExLoginDto
import eu.kanade.tachiyomi.extension.ru.agnamer.dto.ExTokenDto
import eu.kanade.tachiyomi.extension.ru.agnamer.dto.ExTokenResponseDto
import eu.kanade.tachiyomi.extension.ru.agnamer.dto.ExWrapperDto
import eu.kanade.tachiyomi.extension.ru.agnamer.dto.PagesDto
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.NetworkHelper
import eu.kanade.tachiyomi.network.POST
import keiyoushi.network.Fetch
import keiyoushi.network.get
import keiyoushi.network.put
import keiyoushi.utils.parseAs
import keiyoushi.utils.toJsonRequestBody
import kotlinx.serialization.json.JsonElement
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Response
import java.io.IOException

class ExMangaManager(
    private var actualToken: String,
    private val email: String,
    private val password: String,
    userAgent: String,
    apiVersion: String,
    private val preferenceTokenSetter: (newToken: String) -> Unit,
    network: NetworkHelper,
) {
    private val url = "https://api.exmanga.org"

    private val headers = Headers.Builder()
        .set("User-Agent", userAgent)
        .set("Version", apiVersion)
        .build()

    private val authClient = network.client

    private var expiredTokenUnixTimestamp = runCatching {
        getTokenExpiredUnixTimestamp(actualToken)
    }.getOrDefault(0L)

    fun getToken(): String {
        if (expiredTokenUnixTimestamp > System.currentTimeMillis() / 1000) {
            return actualToken
        }

        return tokenRequest().also {
            actualToken = it
            expiredTokenUnixTimestamp = getTokenExpiredUnixTimestamp(it)
            preferenceTokenSetter(it)
        }
    }

    fun imageRequest(imageUrl: String) = GET(imageUrl, headers)

    suspend fun searchManga(client: OkHttpClient, page: Int, query: String): Response {
        val searchUrl = "$url/manga".toHttpUrl().newBuilder()
            .addQueryParameter("take", "20")
            .addQueryParameter("skip", (10 * (page - 1)).toString())
            .addQueryParameter("name", query)
            .build()
        return client.get(searchUrl, headers, Fetch.XHR)
    }

    suspend fun getChapterList(client: OkHttpClient, mangaId: Long): List<ExBookDto> = client.get("$url/chapter/history/$mangaId", headers, Fetch.XHR)
        .parseAs<ExWrapperDto<List<ExBookDto>>>().data

    suspend fun getPages(client: OkHttpClient, chapterUrl: String): List<List<PagesDto>> = client.get(url + chapterUrl, headers, Fetch.XHR)
        .parseAs<ExWrapperDto<List<List<PagesDto>>>>().data

    suspend fun shareChapter(client: OkHttpClient, payload: JsonElement) {
        client.put("$url/chapter", headers, payload.toJsonRequestBody()).use { }
    }

    private fun getTokenExpiredUnixTimestamp(token: String): Long {
        if (token.isBlank()) return 0L
        val payload = token.substringAfter('.').substringBefore('.')
        return Base64.decode(payload, Base64.URL_SAFE).decodeToString().parseAs<ExTokenDto>().exp
    }

    private fun tokenRequest(): String {
        if (email.isBlank() || password.isBlank()) {
            throw IOException("Введите email и password для ExManga")
        }

        // Token refresh runs inside the source's blocking authentication interceptor.
        val body = ExLoginDto(email, password).toJsonRequestBody()
        val response = authClient.newCall(POST("$url/auth/login", headers, body)).execute()
            .parseAs<ExTokenResponseDto>()
        return response.data ?: throw IOException("Ошибка ExManga${response.message?.let { ": $it" }.orEmpty()}")
    }
}

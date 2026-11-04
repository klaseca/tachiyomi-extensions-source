package eu.kanade.tachiyomi.extension.ru.agnamer

import android.widget.Toast
import androidx.preference.CheckBoxPreference
import androidx.preference.EditTextPreference
import androidx.preference.ListPreference
import eu.kanade.tachiyomi.extension.ru.agnamer.dto.BookDto
import eu.kanade.tachiyomi.extension.ru.agnamer.dto.BranchesDto
import eu.kanade.tachiyomi.extension.ru.agnamer.dto.ChunksPageDto
import eu.kanade.tachiyomi.extension.ru.agnamer.dto.ExBookDto
import eu.kanade.tachiyomi.extension.ru.agnamer.dto.ExLibraryDto
import eu.kanade.tachiyomi.extension.ru.agnamer.dto.ExTokenResponseDto
import eu.kanade.tachiyomi.extension.ru.agnamer.dto.ExWrapperDto
import eu.kanade.tachiyomi.extension.ru.agnamer.dto.LibraryDto
import eu.kanade.tachiyomi.extension.ru.agnamer.dto.MangaDto
import eu.kanade.tachiyomi.extension.ru.agnamer.dto.MyLibraryDto
import eu.kanade.tachiyomi.extension.ru.agnamer.dto.PageDto
import eu.kanade.tachiyomi.extension.ru.agnamer.dto.PageWrapperDto
import eu.kanade.tachiyomi.extension.ru.agnamer.dto.SearchPageDto
import eu.kanade.tachiyomi.extension.ru.agnamer.dto.SeriesWrapperDto
import eu.kanade.tachiyomi.extension.ru.agnamer.dto.UserDto
import eu.kanade.tachiyomi.source.ConfigurableSource
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.SMangaUpdate
import keiyoushi.annotation.Source
import keiyoushi.network.Fetch
import keiyoushi.network.get
import keiyoushi.network.rateLimit
import keiyoushi.source.KeiSource
import keiyoushi.utils.booleanOrNull
import keiyoushi.utils.getPreferences
import keiyoushi.utils.parseAs
import keiyoushi.utils.toJsonElement
import keiyoushi.utils.tryParseDateTime
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.asResponseBody
import java.io.IOException
import java.net.URLDecoder
import java.time.format.DateTimeFormatter
import kotlin.math.absoluteValue
import kotlin.random.Random
import kotlin.time.Duration.Companion.seconds

@Source
abstract class Agnamer :
    KeiSource(),
    ConfigurableSource {
    private val preferences = getPreferences {
        getString("overrideBaseUrl", null)?.takeIf { it != DEFAULT_BASE_URL && it.isNotBlank() }?.let { domain ->
            val selectedDomain = getString(DOMAIN_PREF, null)?.takeIf { it.isNotBlank() } ?: domain
            val domains = nullableStringToArray(getString(DOMAIN_LIST_PREF, null)).toList()
            edit()
                .putString(DOMAIN_PREF, selectedDomain)
                .putString(DOMAIN_LIST_PREF, (domains + selectedDomain).distinct().joinToString(","))
                .apply()
        }
        edit().remove("overrideBaseUrl").remove("defaultBaseUrl").remove("fetchedBaseUrl").apply()
    }

    // The mirror list is edited by the user, rather than fixed in the Gradle DSL.
    override val baseUrl: String
        get() = preferences.getString(DOMAIN_PREF, null)?.takeIf { it.isNotBlank() } ?: DEFAULT_BASE_URL

    private val exMangaManager by lazy {
        ExMangaManager(
            actualToken = preferences.getString(EX_TOKEN, null) ?: "",
            email = preferences.getString(EX_EMAIL_PREF, null) ?: "",
            password = preferences.getString(EX_PASSWORD_PREF, null) ?: "",
            userAgent = preferences.getString(EX_USER_AGENT_PREF, "Tachiyomi") ?: "Tachiyomi",
            apiVersion = preferences.getString(EX_API_VERSION_PREF, "4") ?: "4",
            preferenceTokenSetter = { preferences.edit().putString(EX_TOKEN, it).apply() },
            network = network,
        )
    }

    private val exMangaUrl = "https://api.exmanga.org"

    private val mainUrl
        get() = baseUrl.let {
            if (it == DEFAULT_BASE_URL) {
                throw Exception("Установите домен в настройках")
            } else {
                it
            }
        }

    private val apiUrl get() = "$mainUrl/api/v2"

    private val userAgentRandomizer = "${Random.nextInt().absoluteValue}"

    private val chapterChunkSize = 100

    override fun Headers.Builder.configureHeaders(): Headers.Builder = apply {
        // Magic User-Agent, no change/update, does not cause 403
        if (!preferences.getBoolean(USER_AGENT_PREF, false)) {
            set(
                "User-Agent",
                "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/100.0.4896.127 Safari/537.36 Edg/100.0.$userAgentRandomizer",
            )
        }
        set(
            "Accept",
            "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/jxl,image/webp,*/*;q=0.8",
        )
        set("Referer", "${baseUrl.replace("api.", "")}/")
    }

    private fun authIntercept(chain: Interceptor.Chain): Response {
        val request = chain.request()

        if (request.url.host == exMangaUrl.toHttpUrl().host) {
            val token = exMangaManager.getToken()

            val exAuthRequest =
                request.newBuilder().addHeader("Authorization", "Bearer $token").build()

            return chain.proceed(exAuthRequest)
        }

        if (request.url.host != mainUrl.toHttpUrl().host && request.url.host != getHomeUrl().toHttpUrl().host) {
            return chain.proceed(request)
        }
        val cookies = client.cookieJar.loadForRequest(mainUrl.replace("api.", "").toHttpUrl())
        val authCookie = cookies.firstOrNull { cookie -> cookie.name == "user" }
            ?.let { cookie -> URLDecoder.decode(cookie.value, "UTF-8") }
            ?.let { jsonString -> jsonString.parseAs<UserDto>() }
            ?: return chain.proceed(request)

        val accessToken = cookies.firstOrNull { cookie -> cookie.name == "token" }
            ?.let { cookie -> URLDecoder.decode(cookie.value, "UTF-8") } ?: return chain.proceed(
            request,
        )

        userId = authCookie.id.toString()

        val authRequest =
            request.newBuilder().addHeader("Authorization", "Bearer $accessToken").build()
        return chain.proceed(authRequest)
    }

    private fun imageContentTypeIntercept(chain: Interceptor.Chain): Response {
        val originalRequest = chain.request()
        val response = chain.proceed(originalRequest)
        val urlRequest = originalRequest.url.toString()
        val possibleType = urlRequest.substringAfterLast("/").substringBefore("?").split(".")
        return if (urlRequest.contains("/images/") and (possibleType.size == 2)) {
            val realType = possibleType[1]
            val body = response.body
            val image = body.source().asResponseBody("image/$realType".toMediaType(), body.contentLength())
            response.newBuilder().body(image).build()
        } else {
            response
        }
    }

    private fun nullableStringToArray(value: String?) = if (value.isNullOrBlank()) {
        emptyArray()
    } else {
        value.filterNot { it.isWhitespace() }.split(",").toTypedArray()
    }

    override fun OkHttpClient.Builder.configureClient(): OkHttpClient.Builder = apply {
        val loadLimit = if (!preferences.getBoolean(BOOST_LOAD_PREF, false)) 1 else 3
        preferences.getString(DOMAIN_IMG_LIST_PREF, "").let(::nullableStringToArray)
            .forEach { domain ->
                val host = domain.toHttpUrl().host
                rateLimit(loadLimit, 2.seconds) { it.host == host }
            }
    }.rateLimit(3) { it.host == exMangaUrl.toHttpUrl().host }.addInterceptor { imageContentTypeIntercept(it) }
        .addInterceptor { authIntercept(it) }.addInterceptor { chain ->
            val originalRequest = chain.request()
            val response = chain.proceed(originalRequest)
            if (originalRequest.url.host == exMangaUrl.toHttpUrl().host && !response.isSuccessful) {
                val error = response.parseAs<ExTokenResponseDto>()
                val errorText = error.data ?: error.message.orEmpty()
                if (errorText.isEmpty()) {
                    throw IOException(
                        "HTTP error ${response.code}. Домен ${
                            exMangaUrl.substringAfter(
                                "//",
                            )
                        } недоступен, выберите другой в настройках ⚙️ расширения",
                    )
                } else {
                    throw IOException("HTTP error ${response.code}. ExManga: $errorText")
                }
            }
            response
        }.addNetworkInterceptor { chain ->
            val originalRequest = chain.request()
            val response = chain.proceed(originalRequest)
            if (originalRequest.url.toString()
                    .contains(mainUrl) and ((response.code == 403) or (response.code == 500))
            ) {
                val indicateUAgant =
                    if (headers["User-Agent"].orEmpty().contains(userAgentRandomizer)) {
                        "☒"
                    } else {
                        "☑"
                    }
                response.close()
                throw IOException(
                    "HTTP error ${response.code}. Попробуйте сменить домен и/или User-Agent$indicateUAgant в настройках ⚙️ расширения",
                )
            }
            response
        }

    private val count = 30

    override suspend fun getPopularManga(page: Int): MangasPage {
        val url =
            "$apiUrl/search/catalog/?ordering=-rating&count=$count&page=$page&count_chapters_gte=1".toHttpUrl()
                .newBuilder()
        if (preferences.getBoolean(IS_LIB_PREF, false)) {
            url.addQueryParameter("exclude_bookmarks", "1")
        }
        return searchMangaParse(client.get(url.build(), Fetch.XHR))
    }

    override suspend fun getLatestUpdates(page: Int): MangasPage {
        val url =
            "$apiUrl/search/catalog/?ordering=-chapter_date&count=$count&page=$page&count_chapters_gte=1".toHttpUrl()
                .newBuilder()
        if (preferences.getBoolean(IS_LIB_PREF, false)) {
            url.addQueryParameter("exclude_bookmarks", "1")
        }
        return searchMangaParse(client.get(url.build(), Fetch.XHR))
    }

    private fun searchMangaParse(response: Response): MangasPage {
        if (response.request.url.host == exMangaUrl.toHttpUrl().host) {
            val page =
                response.parseAs<ExWrapperDto<List<ExLibraryDto>>>()
            val mangas = page.data.map {
                it.toSManga(mainUrl)
            }

            return MangasPage(mangas, true)
        } else if (response.request.url.toString().contains("/bookmarks/")) {
            val page = response.parseAs<PageWrapperDto<MyLibraryDto>>()
            val mangas = page.results.map {
                it.title.toSManga(mainUrl, isEng)
            }

            return MangasPage(mangas, page.hasNextPage)
        } else if (response.request.url.encodedPath.endsWith("/search/")) {
            val page = response.parseAs<SearchPageDto>()
            return MangasPage(page.results.map { it.toSManga(mainUrl, isEng) }, page.hasNextPage)
        } else {
            val page = response.parseAs<PageWrapperDto<LibraryDto>>()

            val mangas = page.results.map {
                it.toSManga(mainUrl, isEng)
            }

            return MangasPage(mangas, page.hasNextPage)
        }
    }

    override suspend fun getSearchMangaList(page: Int, query: String, filters: FilterList): MangasPage {
        if (query.startsWith(PREFIX_SLUG_SEARCH)) {
            val manga = getMangaBySlug(query.removePrefix(PREFIX_SLUG_SEARCH))
            return MangasPage(listOf(manga), false)
        }
        var url =
            "$apiUrl/search/catalog/?page=$page&count_chapters_gte=1".toHttpUrl().newBuilder()

        if (query.isNotEmpty()) {
            url = "$apiUrl/search/?page=$page".toHttpUrl().newBuilder()
            url.addQueryParameter("query", query)
        }

        (if (filters.isEmpty()) getFilterList() else filters).forEach { filter ->
            when (filter) {
                is OrderBy -> {
                    val ord = arrayOf(
                        "id",
                        "chapter_date",
                        "rating",
                        "votes",
                        "views",
                        "count_chapters",
                        "random",
                    )[filter.state!!.index]
                    url.addQueryParameter(
                        "ordering",
                        if (filter.state!!.ascending) ord else "-$ord",
                    )
                }

                is Category -> {
                    url.addFilter("categories", filter.state.filter { it.isIncluded() }.map { it.id })
                    url.addFilter("exclude_categories", filter.state.filter { it.isExcluded() }.map { it.id })
                }

                is Type -> {
                    url.addFilter("types", filter.state.filter { it.isIncluded() }.map { it.id })
                    url.addFilter("exclude_types", filter.state.filter { it.isExcluded() }.map { it.id })
                }

                is Status -> url.addFilter("status", filter.state.filter { it.state }.map { it.id })

                is TranslateStatus -> url.addFilter("translate_status", filter.state.filter { it.state }.map { it.id })

                is Age -> url.addFilter("age_limit", filter.state.filter { it.state }.map { it.id })

                is Genre -> {
                    url.addFilter("genres", filter.state.filter { it.isIncluded() }.map { it.id })
                    url.addFilter("exclude_genres", filter.state.filter { it.isExcluded() }.map { it.id })
                }

                is My -> {
                    if (filter.state > 0) {
                        if (userId == "") {
                            throw Exception("Пользователь не найден, необходима авторизация через WebView\uD83C\uDF0E")
                        }
                        val typeQ = myList[filter.state].id
                        return searchMangaParse(
                            client.get(
                                "$apiUrl/users/$userId/bookmarks/?type=$typeQ&page=$page",
                                Fetch.XHR,
                            ),
                        )
                    }
                }

                is RequireChapters -> {
                    if (filter.state == 1) {
                        url.setQueryParameter("count_chapters_gte", "0")
                    }
                }

                is RequireEX -> {
                    if (filter.state == 1) {
                        return searchMangaParse(exMangaManager.searchManga(client, page, query))
                    }
                }

                else -> {}
            }
        }

        if (preferences.getBoolean(IS_LIB_PREF, false)) {
            url.addQueryParameter("exclude_bookmarks", "1")
        }

        return searchMangaParse(client.get(url.build(), Fetch.XHR))
    }

    private fun HttpUrl.Builder.addFilter(name: String, values: List<String>) {
        if (values.isNotEmpty()) addQueryParameter(name, values.joinToString(","))
    }

    override fun getHomeUrl(): String = mainUrl.replace("api.", "")

    override fun getMangaUrl(manga: SManga): String = getHomeUrl() + "/manga/" + manga.url.substringAfterLast("/titles/")

    override suspend fun getMangaByUrl(url: HttpUrl): SManga? {
        if (url.host != getHomeUrl().toHttpUrl().host && url.host != mainUrl.toHttpUrl().host) return null
        if (url.pathSegments.firstOrNull() != "manga") return null
        val slug = url.pathSegments.getOrNull(1)?.takeIf { it.isNotBlank() } ?: return null
        return getMangaBySlug(slug)
    }

    private suspend fun getMangaBySlug(slug: String): SManga = fetchSeries(SManga.create().apply { url = "/api/titles/$slug/" }).toSManga(mainUrl, isEng)

    override suspend fun fetchMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate {
        val series = fetchSeries(manga)
        return SMangaUpdate(
            if (fetchDetails) series.toSManga(mainUrl, isEng) else manga,
            if (fetchChapters) getChapterList(manga, series) else chapters,
        )
    }

    private suspend fun fetchSeries(manga: SManga): MangaDto = client.get(
        "$apiUrl/titles/${manga.url.substringAfterLast("/titles/").trimEnd('/')}/",
        Fetch.XHR,
        ensureSuccess = false,
    ).use { response ->
        if (!response.isSuccessful) {
            val message = if (userId.isEmpty()) {
                ". Для просмотра контента необходима авторизация через WebView\uD83C\uDF0E"
            } else {
                ""
            }
            throw IOException("HTTP error ${response.code}$message")
        }
        response.parseAs<MangaDto>()
    }

    private fun filterPaid(tempChaptersList: List<SChapter>): List<SChapter> = if (!preferences.getBoolean(PAID_PREF, false)) {
        val lastEx = tempChaptersList.find { !it.name.contains("\uD83D\uDCB2") }
        tempChaptersList.filterNot {
            it.name.contains("\uD83D\uDCB2") && if (lastEx != null) {
                val volCor = it.name.substringBefore(
                    ". Глава",
                ).toIntOrNull()!!
                val volLast = lastEx.name.substringBefore(". Глава").toIntOrNull()!!
                (volCor > volLast) || ((volCor == volLast) && (it.chapter_number > lastEx.chapter_number))
            } else {
                false
            }
        }
    } else {
        tempChaptersList
    }

    private fun selector(b: BranchesDto): Int = b.countChapters

    private suspend fun getChapterList(manga: SManga, series: MangaDto): List<SChapter> {
        val branch = series.branches
        val selectedBranch = branch.maxByOrNull { selector(it) }
            ?.takeIf { it.countChapters > 0 } ?: return emptyList()
        val exChapters = if (preferences.getBoolean(EX_PAID_PREF, true)) {
            exMangaManager.getChapterList(client, series.id)
        } else {
            emptyList()
        }
        val tempChaptersList = getBranchChapters(selectedBranch.id, manga, exChapters).toMutableList()
        if (branch.size > 1) {
            val selectedBranch2 =
                branch.filter { it.id != selectedBranch.id }.maxByOrNull { selector(it) }!!
            if (selectedBranch2.countChapters > 0) {
                if (selectedBranch.countChapters < (
                        chapterListRequest(selectedBranch2.id, 1).results.firstOrNull()?.chapter?.toFloatOrNull() ?: -2F
                        )
                ) {
                    tempChaptersList.addAll(0, getBranchChapters(selectedBranch2.id, manga, exChapters))
                    return filterPaid(tempChaptersList).distinctBy {
                        it.name.substringBefore(
                            ". Глава",
                        ) + "--" + it.chapter_number
                    }.sortedWith(
                        compareBy(
                            {
                                it.name.substringBefore(". Глава").toIntOrNull()!!
                            },
                            { it.chapter_number },
                        ),
                    ).reversed()
                }
            }
        }

        return filterPaid(tempChaptersList)
    }

    private suspend fun getBranchChapters(branch: Long, manga: SManga, exChapters: List<ExBookDto>): List<SChapter> {
        val chapters = mutableListOf<SChapter>()
        var page = 1
        while (true) {
            val response = chapterListRequest(branch, page++)
            chapters.addAll(chapterListParse(response, manga, exChapters))
            if (!response.hasNextPage) return chapters
        }
    }

    private suspend fun chapterListRequest(branch: Long, page: Int): PageWrapperDto<BookDto> = client.get(
        "$apiUrl/titles/chapters/?branch_id=$branch&page=$page&count=$chapterChunkSize",
        Fetch.XHR,
    ).parseAs()

    private fun chapterListParse(
        response: PageWrapperDto<BookDto>,
        manga: SManga,
        exChapters: List<ExBookDto>,
    ): List<SChapter> {
        val chapters = response.results

        val chaptersList = chapters.map { chapter ->
            SChapter.create().apply {
                chapter_number = chapter.chapter.split(".").take(2).joinToString(".").toFloat()
                url = "/manga/${manga.url.substringAfterLast("/titles/")}ch${chapter.id}"
                date_upload = dateFormat.tryParseDateTime(chapter.uploadDate)
                scanlator = if (chapter.publishers.isNotEmpty()) {
                    chapter.publishers.joinToString { it.name }
                } else {
                    null
                }

                var exChID =
                    exChapters.find { (it.id == chapter.id) || ((it.tome == chapter.tome) && (it.chapter == chapter.chapter)) }
                if (preferences.getBoolean(EX_PAID_PREF, true)) {
                    if (chapter.isPaid and (chapter.isBought != true)) {
                        if (exChID != null) {
                            url = "/chapter?id=${exChID.id}"
                            scanlator = "exmanga"
                        }
                    }

                    if (chapter.isPaid and (chapter.isBought == true)) {
                        memo = buildJsonObject { put("is_bought", true) }
                    }
                } else {
                    exChID = null
                }

                var chapterName = "${chapter.tome}. Глава ${chapter.chapter}"
                if (chapter.isPaid and (chapter.isBought != true) and (exChID == null)) {
                    chapterName += " \uD83D\uDCB2 "
                }
                if (chapter.name.isNotBlank()) {
                    chapterName += " ${chapter.name.replaceFirstChar(Char::titlecase)}"
                }

                name = chapterName
            }
        }
        return chaptersList
    }

    private fun linkTransform(link: String): String {
        val forceLink = preferences.getString(FORCE_DOMAIN_IMG_PREF, "")
        if (forceLink?.isNotBlank() == true) {
            val path = link.substringAfter("://").substringAfter("/", "")
            return if (path.isBlank()) {
                forceLink
            } else {
                "$forceLink/$path"
            }
        }
        if (!link.startsWith("http")) {
            return mainUrl.replace("api.", "") + link
        }
        return link
    }

    override suspend fun getPageList(chapter: SChapter): List<Page> {
        val heightEmptyChunks = 10
        if (chapter.scanlator.equals("exmanga")) {
            try {
                val exPage = exMangaManager.getPages(client, chapter.url)
                val result = mutableListOf<Page>()
                exPage.forEach {
                    it.filter { page -> page.height > heightEmptyChunks }.forEach { page ->
                        result.add(Page(result.size, imageUrl = page.link))
                    }
                }
                return result
            } catch (e: SerializationException) {
                throw IOException("Главы больше нет на ExManga. Попробуйте обновить список глав (свайп сверху)")
            }
        } else {
            if (chapter.name.contains("\uD83D\uDCB2")) {
                val noEX = if (preferences.getBoolean(EX_PAID_PREF, true)) {
                    "Расширение отправляет данные на удаленный сервер ExManga только при открытии глав покупаемой манги"
                } else {
                    "Функции ExManga отключены"
                }
                throw IOException("Глава платная. $noEX")
            }
            val chapterId = chapter.url.substringAfterLast("/ch").substringBefore('#')
            // Keep the complete payload when sharing purchased chapters with ExManga.
            val body = client.get("$apiUrl/titles/chapters/$chapterId/", Fetch.XHR).parseAs<JsonElement>()
            val isBought = chapter.memo["is_bought"]?.booleanOrNull == true || chapter.url.endsWith("#is_bought")
            if (isBought && preferences.getBoolean(EX_PAID_PREF, true)) {
                // ExManga still expects the v1 response envelope.
                exMangaManager.shareChapter(client, SeriesWrapperDto(body).toJsonElement())
            }
            return try {
                val resultDto = body.parseAs<PageDto>()
                resultDto.pages.filter { it.height > heightEmptyChunks }
                    .mapIndexed { index, it ->
                        Page(index, imageUrl = linkTransform(it.link))
                    }
            } catch (e: SerializationException) {
                val resultDto = body.parseAs<ChunksPageDto>()
                val result = mutableListOf<Page>()
                resultDto.pages.forEach {
                    it.forEach { page ->
                        if (page.height > heightEmptyChunks) {
                            result.add(Page(result.size, imageUrl = linkTransform(page.link)))
                        }
                    }
                }
                return result
            }
        }
    }

    override fun getChapterUrl(chapter: SChapter): String = if (chapter.scanlator.equals("exmanga")) {
        exMangaUrl + chapter.url
    } else {
        mainUrl.replace(
            "api.",
            "",
        ) + chapter.url.substringBefore("#is_bought")
    }

    override fun getImageRequest(page: Page): Request = if (page.imageUrl!!.contains(exMangaUrl)) {
        exMangaManager.imageRequest(page.imageUrl!!)
    } else {
        super.getImageRequest(page)
    }

    override fun getFilterList(data: JsonElement?) = filterList

    private val isEng get() = preferences.getString(LANGUAGE_PREF, "eng")

    override fun setupPreferenceScreen(screen: androidx.preference.PreferenceScreen) {
        CheckBoxPreference(screen.context).apply {
            key = USER_AGENT_PREF
            title = "User-Agent приложения"
            summary =
                "Использует User-Agent приложения, прописанный в настройках приложения (Настройки -> Дополнительно)"
            setDefaultValue(false)

            setOnPreferenceChangeListener { _, _ ->
                Toast.makeText(
                    screen.context,
                    "Для смены User-Agent(а) необходимо перезапустить приложение с полной остановкой",
                    Toast.LENGTH_LONG,
                ).show()
                true
            }
        }.also(screen::addPreference)

        EditTextPreference(screen.context).apply {
            key = EX_TOKEN
            title = "ExManga token"
            setDefaultValue("")
            setVisible(false)
        }.also(screen::addPreference)

        val domainPreference = ListPreference(screen.context).apply {
            key = DOMAIN_PREF
            title = "Выбор домена"
            summary = "%s"
            setDefaultValue("")
        }

        fun updateDomains(value: String?) {
            val domains = nullableStringToArray(value)
            domainPreference.entries = domains
            domainPreference.entryValues = domains
            domainPreference.setVisible(domains.isNotEmpty())
            if (domainPreference.value !in domains) {
                domainPreference.value = domains.firstOrNull().orEmpty()
            }
        }

        EditTextPreference(screen.context).apply {
            key = DOMAIN_LIST_PREF
            title = "Список доменов"
            summary = "Введите список доменов через ','"
            setDefaultValue("")
            setOnPreferenceChangeListener { _, newValue ->
                updateDomains(newValue as String)
                true
            }
        }.also(screen::addPreference)

        screen.addPreference(domainPreference)
        updateDomains(preferences.getString(DOMAIN_LIST_PREF, ""))

        val domainImgListPref = EditTextPreference(screen.context).apply {
            key = DOMAIN_IMG_LIST_PREF
            title = "Список доменов изображений"
            summary = "Введите список доменов через ','"
            setDefaultValue("")
            setOnPreferenceChangeListener { _, _ ->
                Toast.makeText(
                    screen.context,
                    "Для смены списка доменов изображений необходимо перезапустить приложение с полной остановкой",
                    Toast.LENGTH_LONG,
                ).show()
                true
            }
        }.also(screen::addPreference)

        val domainsImg = domainImgListPref.text.let(::nullableStringToArray)

        if (domainsImg.isNotEmpty()) {
            ListPreference(screen.context).apply {
                key = FORCE_DOMAIN_IMG_PREF
                title = "Выбор домена изображений"
                entries = arrayOf("Авто", *domainsImg)
                entryValues = arrayOf("", *domainsImg)
                summary = "%s"
                setDefaultValue("")
                setOnPreferenceChangeListener { _, _ ->
                    Toast.makeText(
                        screen.context,
                        "Для смены домена необходимо перезапустить приложение с полной остановкой",
                        Toast.LENGTH_LONG,
                    ).show()
                    true
                }
            }.also(screen::addPreference)
        }

        ListPreference(screen.context).apply {
            key = LANGUAGE_PREF
            title = "Выбор языка на обложке"
            entries = arrayOf("Английский", "Русский")
            entryValues = arrayOf("eng", "rus")
            summary = "%s"
            setDefaultValue("eng")
            setOnPreferenceChangeListener { _, _ ->
                Toast.makeText(
                    screen.context,
                    "Если язык обложки не изменился очистите базу данных в приложении (Настройки -> Дополнительно -> Очистить базу данных)",
                    Toast.LENGTH_LONG,
                ).show()
                true
            }
        }.also(screen::addPreference)

        CheckBoxPreference(screen.context).apply {
            key = PAID_PREF
            title = "Показывать все платные главы"
            summary =
                "Показывает не купленные\uD83D\uDCB2 главы(может вызвать ошибки при обновлении/автозагрузке)"
            setDefaultValue(false)
        }.also(screen::addPreference)

        CheckBoxPreference(screen.context).apply {
            key = EX_PAID_PREF
            title = "Показывать главы из ExManga"
            summary =
                "Показывает главы купленные другими людьми и поделившиеся ими через браузерное расширение ExManga \n\n" + "ⓘЧастично отображает не купленные\uD83D\uDCB2 главы для соблюдения порядка глав \n\n" + "ⓘТакже отправляет купленные главы из Tachiyomi в ExManga"
            setDefaultValue(true)
        }.also(screen::addPreference)

        EditTextPreference(screen.context).apply {
            key = EX_EMAIL_PREF
            title = "ExManga email"
            setDefaultValue("")
        }.also(screen::addPreference)

        EditTextPreference(screen.context).apply {
            key = EX_PASSWORD_PREF
            title = "ExManga password"
            setDefaultValue("")
        }.also(screen::addPreference)

        EditTextPreference(screen.context).apply {
            key = EX_USER_AGENT_PREF
            title = "ExManga user agent"
            setDefaultValue("Tachiyomi")
        }.also(screen::addPreference)

        EditTextPreference(screen.context).apply {
            key = EX_API_VERSION_PREF
            title = "ExManga api version"
            setDefaultValue("4")
        }.also(screen::addPreference)

        CheckBoxPreference(screen.context).apply {
            key = IS_LIB_PREF
            title = "Скрыть «Закладки»"
            summary = "Скрывает мангу находящуюся в закладках пользователя на сайте"
            setDefaultValue(false)
        }.also(screen::addPreference)

        CheckBoxPreference(screen.context).apply {
            key = BOOST_LOAD_PREF
            title = "Ускорить скачивание глав"
            summary =
                "Увеличивает количество скачиваемых страниц в секунду, но Remanga быстрее ограничит скорость скачивания"
            setDefaultValue(false)

            setOnPreferenceChangeListener { _, _ ->
                Toast.makeText(
                    screen.context,
                    "Для применения настройки необходимо перезапустить приложение с полной остановкой",
                    Toast.LENGTH_LONG,
                ).show()
                true
            }
        }.also(screen::addPreference)
    }

    companion object {
        private const val PREFIX_SLUG_SEARCH = "slug:"

        private const val DEFAULT_BASE_URL = "https://agnamer.local"

        private var userId = ""

        private const val USER_AGENT_PREF = "USER_AGENT_PREF"

        private const val BOOST_LOAD_PREF = "BOOST_LOAD_PREF"

        private const val DOMAIN_PREF = "DOMAIN_PREF"

        private const val DOMAIN_LIST_PREF = "DOMAIN_LIST_PREF"

        private const val DOMAIN_IMG_LIST_PREF = "DOMAIN_IMG_LIST_PREF"

        private const val FORCE_DOMAIN_IMG_PREF = "FORCE_DOMAIN_IMG_PREF"

        private const val LANGUAGE_PREF = "LANGUAGE_PREF"

        private const val PAID_PREF = "PAID_PREF"

        private const val EX_PAID_PREF = "EX_PAID_PREF"

        private const val EX_EMAIL_PREF = "EX_EMAIL_PREF"

        private const val EX_PASSWORD_PREF = "EX_PASSWORD_PREF"

        private const val EX_USER_AGENT_PREF = "EX_USER_AGENT_PREF"

        private const val EX_API_VERSION_PREF = "EX_API_VERSION_PREF"

        private const val EX_TOKEN = "EX_TOKEN"

        private const val IS_LIB_PREF = "IS_LIB_PREF"

        private val dateFormat = DateTimeFormatter.ISO_DATE_TIME
    }
}

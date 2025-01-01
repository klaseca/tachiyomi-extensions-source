package eu.kanade.tachiyomi.extension.ru.agnamer

import android.annotation.TargetApi
import android.app.Application
import android.content.SharedPreferences
import android.os.Build
import android.widget.Toast
import androidx.preference.CheckBoxPreference
import androidx.preference.EditTextPreference
import androidx.preference.ListPreference
import eu.kanade.tachiyomi.extension.ru.agnamer.dto.BookDto
import eu.kanade.tachiyomi.extension.ru.agnamer.dto.BranchesDto
import eu.kanade.tachiyomi.extension.ru.agnamer.dto.ChunksPageDto
import eu.kanade.tachiyomi.extension.ru.agnamer.dto.ExBookDto
import eu.kanade.tachiyomi.extension.ru.agnamer.dto.ExLibraryDto
import eu.kanade.tachiyomi.extension.ru.agnamer.dto.ExWrapperDto
import eu.kanade.tachiyomi.extension.ru.agnamer.dto.LibraryDto
import eu.kanade.tachiyomi.extension.ru.agnamer.dto.MangaDto
import eu.kanade.tachiyomi.extension.ru.agnamer.dto.MyLibraryDto
import eu.kanade.tachiyomi.extension.ru.agnamer.dto.PageDto
import eu.kanade.tachiyomi.extension.ru.agnamer.dto.PageWrapperDto
import eu.kanade.tachiyomi.extension.ru.agnamer.dto.PagesDto
import eu.kanade.tachiyomi.extension.ru.agnamer.dto.SeriesWrapperDto
import eu.kanade.tachiyomi.extension.ru.agnamer.dto.TagsDto
import eu.kanade.tachiyomi.extension.ru.agnamer.dto.UserDto
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.asObservable
import eu.kanade.tachiyomi.network.asObservableSuccess
import eu.kanade.tachiyomi.network.interceptor.rateLimitHost
import eu.kanade.tachiyomi.source.ConfigurableSource
import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import kotlinx.serialization.SerializationException
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.jsoup.Jsoup
import rx.Observable
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import uy.kohesive.injekt.injectLazy
import java.io.IOException
import java.net.URLDecoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.absoluteValue
import kotlin.random.Random

class Agnamer : ConfigurableSource, HttpSource() {
    override val id = 8983242087533137528

    override val name = "Agnamer"

    override val lang = "ru"

    private val preferences: SharedPreferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)
    }

    private val stubUrl = "https://agnamer.local"

    private val exMangaManager = ExMangaManager(
        actualToken = preferences.getString(EX_TOKEN, null) ?: "",
        email = preferences.getString(EX_EMAIL_PREF, null) ?: "",
        password = preferences.getString(EX_PASSWORD_PREF, null) ?: "",
        preferenceTokenSetter = { preferences.edit().putString(EX_TOKEN, it).apply() },
        network = network,
    )

    private val exMangaUrl = "https://api.exmanga.org"

    override val baseUrl = preferences.getString(DOMAIN_PREF, null).let {
        if (it.isNullOrBlank()) {
            stubUrl
        } else {
            it
        }
    }

    private val mainUrl
        get() = baseUrl.let {
            if (it === stubUrl) {
                throw Exception("Установите домен в настройках")
            } else {
                it
            }
        }

    override val supportsLatest = true

    private val userAgentRandomizer = "${Random.nextInt().absoluteValue}"

    private val chapterChunkSize = 100

    override fun headersBuilder() = Headers.Builder().apply {
        // Magic User-Agent, no change/update, does not cause 403
        if (!preferences.getBoolean(USER_AGENT_PREF, false)) {
            add(
                "User-Agent",
                "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/100.0.4896.127 Safari/537.36 Edg/100.0.$userAgentRandomizer",
            )
        }
        add(
            "Accept",
            "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/jxl,image/webp,*/*;q=0.8",
        )
        add("Referer", mainUrl.replace("api.", ""))
    }

    private fun authIntercept(chain: Interceptor.Chain): Response {
        val request = chain.request()

        if (request.url.toString().contains(exMangaUrl)) {
            val token = exMangaManager.getToken()

            val exAuthRequest =
                request.newBuilder().addHeader("Authorization", "Bearer $token").build()

            val exAuthResponse = chain.proceed(exAuthRequest)

            exMangaManager.handleResponse(exAuthResponse)

            return exAuthResponse
        }

        val cookies = client.cookieJar.loadForRequest(mainUrl.replace("api.", "").toHttpUrl())
        val authCookie = cookies.firstOrNull { cookie -> cookie.name == "user" }
            ?.let { cookie -> URLDecoder.decode(cookie.value, "UTF-8") }
            ?.let { jsonString -> json.decodeFromString<UserDto>(jsonString) }
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
            val image = response.body.byteString().toResponseBody("image/$realType".toMediaType())
            response.newBuilder().body(image).build()
        } else {
            response
        }
    }

    private val loadLimit = if (!preferences.getBoolean(BOOST_LOAD_PREF, false)) 1 else 3

    private fun nullableStringToArray(value: String?) = if (value.isNullOrBlank()) {
        emptyArray()
    } else {
        value.filterNot { it.isWhitespace() }.split(",").toTypedArray()
    }

    override val client = network.cloudflareClient.newBuilder().apply {
        preferences.getString(DOMAIN_IMG_LIST_PREF, "").let(::nullableStringToArray)
            .forEach { rateLimitHost(it.toHttpUrl(), loadLimit, 2) }
    }.rateLimitHost(exMangaUrl.toHttpUrl(), 3).addInterceptor { imageContentTypeIntercept(it) }
        .addInterceptor { authIntercept(it) }.addInterceptor { chain ->
            val originalRequest = chain.request()
            val response = chain.proceed(originalRequest)
            if (originalRequest.url.toString().contains(exMangaUrl) and !response.isSuccessful) {
                val errorText =
                    json.decodeFromString<ExWrapperDto<String>>(response.body.string()).data
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
                throw IOException(
                    "HTTP error ${response.code}. Попробуйте сменить домен и/или User-Agent$indicateUAgant в настройках ⚙️ расширения",
                )
            }
            response
        }.build()

    private val count = 30

    private var branches = mutableMapOf<String, List<BranchesDto>>()

    private var mangaIDs = mutableMapOf<String, Long>()

    override fun popularMangaRequest(page: Int): Request {
        val url =
            "$mainUrl/api/search/catalog/?ordering=-rating&count=$count&page=$page&count_chapters_gte=1".toHttpUrl()
                .newBuilder()
        if (preferences.getBoolean(IS_LIB_PREF, false)) {
            url.addQueryParameter("exclude_bookmarks", "1")
        }
        return GET(url.build(), headers)
    }

    override fun popularMangaParse(response: Response): MangasPage = searchMangaParse(response)

    override fun latestUpdatesRequest(page: Int): Request {
        val url =
            "$mainUrl/api/search/catalog/?ordering=-chapter_date&count=$count&page=$page&count_chapters_gte=1".toHttpUrl()
                .newBuilder()
        if (preferences.getBoolean(IS_LIB_PREF, false)) {
            url.addQueryParameter("exclude_bookmarks", "1")
        }
        return GET(url.build(), headers)
    }

    override fun latestUpdatesParse(response: Response): MangasPage = searchMangaParse(response)

    override fun searchMangaParse(response: Response): MangasPage {
        if (response.request.url.toString().contains(exMangaUrl)) {
            val page =
                json.decodeFromString<ExWrapperDto<List<ExLibraryDto>>>(response.body.string())
            val mangas = page.data.map {
                it.toSManga()
            }

            return MangasPage(mangas, true)
        } else if (response.request.url.toString().contains("/bookmarks/")) {
            val page = json.decodeFromString<PageWrapperDto<MyLibraryDto>>(response.body.string())
            val mangas = page.content.map {
                it.title.toSManga()
            }

            return MangasPage(mangas, true)
        } else {
            val page = json.decodeFromString<PageWrapperDto<LibraryDto>>(response.body.string())

            val mangas = page.content.map {
                it.toSManga()
            }

            return MangasPage(mangas, page.props.page < page.props.total_pages!!)
        }
    }

    private fun ExLibraryDto.toSManga(): SManga = SManga.create().apply {
        // Do not change the title name to ensure work with a multilingual catalog!
        title = name
        url = "/api/titles/$dir/"
        thumbnail_url = mainUrl + img
    }

    private fun LibraryDto.toSManga(): SManga = SManga.create().apply {
        // Do not change the title name to ensure work with a multilingual catalog!
        title = if (isEng.equals("rus")) rus_name else en_name
        url = "/api/titles/$dir/"
        thumbnail_url = mainUrl + img.mid
    }

    private val simpleDateFormat by lazy { SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US) }

    private fun parseDate(date: String?): Long {
        date ?: return Date().time
        return try {
            simpleDateFormat.parse(date)!!.time
        } catch (_: Exception) {
            Date().time
        }
    }

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        var url =
            "$mainUrl/api/search/catalog/?page=$page&count_chapters_gte=1".toHttpUrl().newBuilder()

        if (query.isNotEmpty()) {
            url = "$mainUrl/api/search/?page=$page".toHttpUrl().newBuilder()
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

                is Category -> filter.state.forEach { category ->
                    if (category.state != Filter.TriState.STATE_IGNORE) {
                        url.addQueryParameter(
                            if (category.isIncluded()) "categories" else "exclude_categories",
                            category.id,
                        )
                    }
                }

                is Type -> filter.state.forEach { type ->
                    if (type.state != Filter.TriState.STATE_IGNORE) {
                        url.addQueryParameter(
                            if (type.isIncluded()) "types" else "exclude_types",
                            type.id,
                        )
                    }
                }

                is Status -> filter.state.forEach { status ->
                    if (status.state) {
                        url.addQueryParameter("status", status.id)
                    }
                }

                is Age -> filter.state.forEach { age ->
                    if (age.state) {
                        url.addQueryParameter("age_limit", age.id)
                    }
                }

                is Genre -> filter.state.forEach { genre ->
                    if (genre.state != Filter.TriState.STATE_IGNORE) {
                        url.addQueryParameter(
                            if (genre.isIncluded()) "genres" else "exclude_genres",
                            genre.id,
                        )
                    }
                }

                is My -> {
                    if (filter.state > 0) {
                        if (userId == "") {
                            throw Exception("Пользователь не найден, необходима авторизация через WebView\uD83C\uDF0E")
                        }
                        val typeQ = myList[filter.state].id
                        return GET(
                            "$mainUrl/api/users/$userId/bookmarks/?type=$typeQ&page=$page",
                            headers,
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
                        return exMangaManager.searchMangaRequest(page, query)
                    }
                }

                else -> {}
            }
        }

        if (preferences.getBoolean(IS_LIB_PREF, false)) {
            url.addQueryParameter("exclude_bookmarks", "1")
        }

        return GET(url.build(), headers)
    }

    private fun parseStatus(status: Int): Int {
        return when (status) {
            1 -> SManga.COMPLETED // Закончен
            2 -> SManga.ONGOING // Продолжается
            3 -> SManga.ON_HIATUS // Заморожен
            4 -> SManga.ON_HIATUS // Нет переводчика
            5 -> SManga.ONGOING // Анонс
            // 6 -> SManga.LICENSED // Лицензировано // Hides available chapters!
            else -> SManga.UNKNOWN
        }
    }

    private fun parseType(type: TagsDto): String {
        return when (type.name) {
            "Западный комикс" -> "Комикс"
            else -> type.name
        }
    }

    private fun parseAge(ageLimit: Int): String {
        return when (ageLimit) {
            2 -> "18+"
            1 -> "16+"
            else -> ""
        }
    }

    private fun MangaDto.toSManga(): SManga {
        val ratingValue = avg_rating.jsonPrimitive.floatOrNull ?: 0f
        val ratingStar = when {
            ratingValue > 9.5 -> "★★★★★"
            ratingValue > 8.5 -> "★★★★✬"
            ratingValue > 7.5 -> "★★★★☆"
            ratingValue > 6.5 -> "★★★✬☆"
            ratingValue > 5.5 -> "★★★☆☆"
            ratingValue > 4.5 -> "★★✬☆☆"
            ratingValue > 3.5 -> "★★☆☆☆"
            ratingValue > 2.5 -> "★✬☆☆☆"
            ratingValue > 1.5 -> "★☆☆☆☆"
            ratingValue > 0.5 -> "✬☆☆☆☆"
            else -> "☆☆☆☆☆"
        }
        val self = this
        return SManga.create().apply {
            // Do not change the title name to ensure work with a multilingual catalog!
            title = if (isEng.equals("rus")) rus_name else en_name
            url = "/api/titles/$dir/"
            thumbnail_url = mainUrl + img.high
            var altName = ""
            if (another_name.isNotEmpty()) {
                altName = "Альтернативные названия:\n$another_name\n"
            }
            val mediaNameLanguage = if (isEng.equals("rus")) en_name else rus_name
            this.description =
                "$mediaNameLanguage\n$ratingStar $ratingValue (голосов: $count_rating)\n$altName" + self.description?.let {
                Jsoup.parse(it)
            }?.select("body:not(:has(p)),p,br")?.prepend("\\n")?.text()?.replace("\\n", "\n")
                ?.replace("\n ", "\n").orEmpty()
            genre =
                (parseType(type) + ", " + parseAge(age_limit) + ", " + (genres + categories).joinToString { it.name }).split(
                    ", ",
                ).filter { it.isNotEmpty() }.joinToString { it.trim() }
            status = parseStatus(self.status.id)
        }
    }

    private fun titleDetailsRequest(manga: SManga): Request {
        return GET(mainUrl + manga.url, headers)
    }

    // Workaround to allow "Open in browser" use the real URL.
    override fun fetchMangaDetails(manga: SManga): Observable<SManga> {
        var warnLogin = false
        return client.newCall(titleDetailsRequest(manga)).asObservable().doOnNext { response ->
            if (!response.isSuccessful) {
                response.close()
                if (userId == "") {
                    warnLogin = true
                } else {
                    throw Exception("HTTP error ${response.code}")
                }
            }
        }.map { response ->
            (
                if (warnLogin) {
                    manga.apply {
                        description =
                            "Для просмотра контента необходима авторизация через WebView\uD83C\uDF0E︎"
                    }
                } else {
                    mangaDetailsParse(response)
                }
                ).apply {
                initialized = true
            }
        }
    }

    override fun mangaDetailsRequest(manga: SManga): Request {
        return GET(
            mainUrl.replace("api.", "") + "/manga/" + manga.url.substringAfter("/api/titles/", "/"),
            headers,
        )
    }

    override fun mangaDetailsParse(response: Response): SManga {
        val series = json.decodeFromString<SeriesWrapperDto<MangaDto>>(response.body.string())
        branches[series.content.dir] = series.content.branches
        mangaIDs[series.content.dir] = series.content.id
        return series.content.toSManga()
    }

    private fun mangaBranches(manga: SManga): List<BranchesDto> {
        val requestString = client.newCall(GET(mainUrl + manga.url, headers)).execute()
        if (!requestString.isSuccessful) {
            if (userId == "") {
                throw Exception("HTTP error ${requestString.code}. Для просмотра контента необходима авторизация через WebView\uD83C\uDF0E")
            }
            throw Exception("HTTP error ${requestString.code}")
        }
        val responseString = requestString.body.string()
        // manga requiring login return "content" as a JsonArray instead of the JsonObject we expect
        // callback request for update outside the library
        val content = json.decodeFromString<JsonObject>(responseString)["content"]
        return if (content is JsonObject) {
            val series = json.decodeFromJsonElement<MangaDto>(content)
            branches[series.dir] = series.branches
            mangaIDs[series.dir] = series.id
            if (series.status.id == 5 && series.branches.maxByOrNull { selector(it) }!!.count_chapters == 0) {
                throw Exception("Лицензировано - Нет глав")
            }
            series.branches
        } else {
            emptyList()
        }
    }

    private fun filterPaid(tempChaptersList: MutableList<SChapter>): MutableList<SChapter> {
        return if (!preferences.getBoolean(PAID_PREF, false)) {
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
            } as MutableList<SChapter>
        } else {
            tempChaptersList
        }
    }

    private fun selector(b: BranchesDto): Int = b.count_chapters

    override fun fetchChapterList(manga: SManga): Observable<List<SChapter>> {
        val seriesDir =
            manga.url.substringAfter("/api/titles/").substringBefore("/").substringBefore("?")

        val branch = branches.getOrElse(seriesDir) { mangaBranches(manga) }

        return when {
            branch.maxByOrNull { selector(it) }!!.count_chapters == 0 -> {
                Observable.error(Exception("Лицензировано - Нет глав"))
            }

            branch.isEmpty() -> {
                if (userId == "") {
                    Observable.error(Exception("Для просмотра контента необходима авторизация через WebView\uD83C\uDF0E"))
                } else {
                    return Observable.just(listOf())
                }
            }

            else -> {
                val mangaID = mangaIDs[seriesDir]
                val exChapters = if (preferences.getBoolean(EX_PAID_PREF, true)) {
                    json.decodeFromString<ExWrapperDto<List<ExBookDto>>>(
                        client.newCall(exMangaManager.fetchChapterListRequest(mangaID))
                            .execute().body.string(),
                    ).data
                } else {
                    emptyList()
                }
                val selectedBranch = branch.maxByOrNull { selector(it) }!!
                val tempChaptersList = mutableListOf<SChapter>()
                (1..(selectedBranch.count_chapters / chapterChunkSize + 1)).map {
                    val response = chapterListRequest(selectedBranch.id, it)
                    chapterListParse(response, manga, exChapters)
                }.let { tempChaptersList.addAll(it.flatten()) }
                if (branch.size > 1) {
                    val selectedBranch2 =
                        branch.filter { it.id != selectedBranch.id }.maxByOrNull { selector(it) }!!
                    if (selectedBranch2.count_chapters > 0) {
                        if (selectedBranch.count_chapters < (
                            json.decodeFromString<SeriesWrapperDto<List<BookDto>>>(
                                    chapterListRequest(selectedBranch2.id, 1).body.string(),
                                ).content.firstOrNull()?.chapter?.toFloatOrNull() ?: -2F
                            )
                        ) {
                            (1..(selectedBranch2.count_chapters / chapterChunkSize + 1)).map {
                                val response = chapterListRequest(selectedBranch2.id, it)
                                chapterListParse(response, manga, exChapters)
                            }.let { tempChaptersList.addAll(0, it.flatten()) }
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
                            ).reversed().let { Observable.just(it) }
                        }
                    }
                }

                return filterPaid(tempChaptersList).let { Observable.just(it) }
            }
        }
    }

    private fun chapterListRequest(branch: Long, page: Number): Response = client.newCall(
        GET(
            "$mainUrl/api/titles/chapters/?branch_id=$branch&page=$page&count=$chapterChunkSize",
            headers,
        ),
    ).execute().run {
        if (!isSuccessful) {
            close()
            throw Exception("HTTP error $code")
        }
        this
    }

    override fun chapterListParse(response: Response) = throw UnsupportedOperationException()

    private fun chapterListParse(
        response: Response,
        manga: SManga,
        exChapters: List<ExBookDto>,
    ): List<SChapter> {
        val chapters =
            json.decodeFromString<SeriesWrapperDto<List<BookDto>>>(response.body.string()).content

        val chaptersList = chapters.map { chapter ->
            SChapter.create().apply {
                chapter_number = chapter.chapter.split(".").take(2).joinToString(".").toFloat()
                url = "/manga/${manga.url.substringAfterLast("/api/titles/")}ch${chapter.id}"
                date_upload = parseDate(chapter.upload_date)
                scanlator = if (chapter.publishers.isNotEmpty()) {
                    chapter.publishers.joinToString { it.name }
                } else {
                    null
                }

                var exChID =
                    exChapters.find { (it.id == chapter.id) || ((it.tome == chapter.tome) && (it.chapter == chapter.chapter)) }
                if (preferences.getBoolean(EX_PAID_PREF, true)) {
                    if (chapter.is_paid and (chapter.is_bought != true)) {
                        if (exChID != null) {
                            url = "/chapter?id=${exChID.id}"
                            scanlator = "exmanga"
                        }
                    }

                    if (chapter.is_paid and (chapter.is_bought == true)) {
                        url = "$url#is_bought"
                    }
                } else {
                    exChID = null
                }

                var chapterName = "${chapter.tome}. Глава ${chapter.chapter}"
                if (chapter.is_paid and (chapter.is_bought != true) and (exChID == null)) {
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

    private fun fixLink(link: String): String {
        if (!link.startsWith("http")) {
            return mainUrl.replace("api.", "") + link
        }
        return link
    }

    @TargetApi(Build.VERSION_CODES.N)
    private fun pageListParse(response: Response, chapter: SChapter): List<Page> {
        val body = response.body.string()
        val heightEmptyChunks = 10
        if (chapter.scanlator.equals("exmanga")) {
            try {
                val exPage = json.decodeFromString<ExWrapperDto<List<List<PagesDto>>>>(body)
                val result = mutableListOf<Page>()
                exPage.data.forEach {
                    it.filter { page -> page.height > heightEmptyChunks }.forEach { page ->
                        result.add(Page(result.size, "", page.link))
                    }
                }
                return result
            } catch (e: SerializationException) {
                throw IOException("Главы больше нет на ExManga. Попробуйте обновить список глав (свайп сверху)")
            }
        } else {
            if (chapter.url.contains("#is_bought") && (
                preferences.getBoolean(
                        EX_PAID_PREF,
                        true,
                    )
                )
            ) {
                client.newCall(exMangaManager.chapterRequest(body)).execute()
            }
            return try {
                val page = json.decodeFromString<SeriesWrapperDto<PageDto>>(body)
                page.content.pages.filter { it.height > heightEmptyChunks }
                    .mapIndexed { index, it ->
                        Page(index, "", fixLink(it.link))
                    }
            } catch (e: SerializationException) {
                val page = json.decodeFromString<SeriesWrapperDto<ChunksPageDto>>(body)
                val result = mutableListOf<Page>()
                page.content.pages.forEach {
                    it.filter { page -> page.height > heightEmptyChunks }.forEach { page ->
                        result.add(Page(result.size, "", fixLink(page.link)))
                    }
                }
                return result
            }
        }
    }

    override fun pageListParse(response: Response): List<Page> =
        throw UnsupportedOperationException()

    override fun pageListRequest(chapter: SChapter): Request {
        return if (chapter.scanlator.equals("exmanga")) {
            exMangaManager.pageListRequest(chapter.url)
        } else {
            if (chapter.name.contains("\uD83D\uDCB2")) {
                val noEX = if (preferences.getBoolean(EX_PAID_PREF, true)) {
                    "Расширение отправляет данные на удаленный сервер ExManga только при открытии глав покупаемой манги"
                } else {
                    "Функции ExManga отключены"
                }
                throw IOException("Глава платная. $noEX")
            }
            GET(
                "$mainUrl/api/titles/chapters/" + chapter.url.substringAfterLast("/ch")
                    .substringBefore("#is_bought") + "/",
                headers,
            )
        }
    }

    override fun fetchPageList(chapter: SChapter): Observable<List<Page>> {
        return client.newCall(pageListRequest(chapter)).asObservableSuccess().map { response ->
            pageListParse(response, chapter)
        }
    }

    override fun getChapterUrl(chapter: SChapter): String {
        return if (chapter.scanlator.equals("exmanga")) {
            exMangaUrl + chapter.url
        } else {
            mainUrl.replace(
                "api.",
                "",
            ) + chapter.url.substringBefore("#is_bought")
        }
    }

    override fun fetchImageUrl(page: Page): Observable<String> = Observable.just(page.imageUrl!!)

    override fun imageUrlRequest(page: Page): Request = throw NotImplementedError("Unused")

    override fun imageUrlParse(response: Response): String = throw NotImplementedError("Unused")

    private fun searchMangaByIdRequest(id: String): Request {
        return GET("$mainUrl/api/titles/$id/", headers)
    }

    override fun fetchSearchManga(
        page: Int,
        query: String,
        filters: FilterList,
    ): Observable<MangasPage> {
        return if (query.startsWith(PREFIX_SLUG_SEARCH)) {
            val realQuery = query.removePrefix(PREFIX_SLUG_SEARCH)
            client.newCall(searchMangaByIdRequest(realQuery)).asObservableSuccess()
                .map { response ->
                    val details = mangaDetailsParse(response)
                    details.url = "/api/titles/$realQuery/"
                    MangasPage(listOf(details), false)
                }
        } else {
            client.newCall(searchMangaRequest(page, query, filters)).asObservableSuccess()
                .map { response ->
                    searchMangaParse(response)
                }
        }
    }

    override fun imageRequest(page: Page): Request {
        val refererHeaders = headersBuilder().build()
        return if (page.imageUrl!!.contains(exMangaUrl)) {
            exMangaManager.imageRequest(page.imageUrl!!)
        } else {
            GET(page.imageUrl!!, refererHeaders)
        }
    }

    override fun getFilterList() = filterList

    private var isEng = preferences.getString(LANGUAGE_PREF, "eng")

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

        val domainPrefList = EditTextPreference(screen.context).apply {
            key = DOMAIN_LIST_PREF
            title = "Список доменов"
            summary = "Введите список доменов через ','"
            setDefaultValue("")
            setOnPreferenceChangeListener { _, newValue ->
                try {
                    val isSuccess =
                        preferences.edit().putString(DOMAIN_LIST_PREF, newValue as String).commit()

                    Toast.makeText(
                        screen.context,
                        "Для смены списка доменов необходимо перезапустить приложение с полной остановкой",
                        Toast.LENGTH_LONG,
                    ).show()

                    isSuccess
                } catch (e: Exception) {
                    e.printStackTrace()
                    false
                }
            }
        }.also(screen::addPreference)

        val domains = domainPrefList.text.let(::nullableStringToArray)

        if (domains.isNotEmpty()) {
            ListPreference(screen.context).apply {
                key = DOMAIN_PREF
                title = "Выбор домена"
                entries = domains
                entryValues = domains
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

        EditTextPreference(screen.context).apply {
            key = DOMAIN_IMG_LIST_PREF
            title = "Список доменов изображений"
            summary = "Введите список доменов через ','"
            setDefaultValue("")
            setOnPreferenceChangeListener { _, newValue ->
                try {
                    val isSuccess =
                        preferences.edit().putString(DOMAIN_LIST_PREF, newValue as String).commit()

                    Toast.makeText(
                        screen.context,
                        "Для смены списка доменов изображений необходимо перезапустить приложение с полной остановкой",
                        Toast.LENGTH_LONG,
                    ).show()

                    isSuccess
                } catch (e: Exception) {
                    e.printStackTrace()
                    false
                }
            }
        }.also(screen::addPreference)

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
            setOnPreferenceChangeListener { _, newValue ->
                try {
                    val isSuccess =
                        preferences.edit().putString(EX_EMAIL_PREF, newValue as String).commit()

                    isSuccess
                } catch (e: Exception) {
                    e.printStackTrace()
                    false
                }
            }
        }.also(screen::addPreference)

        EditTextPreference(screen.context).apply {
            key = EX_PASSWORD_PREF
            title = "ExManga password"
            setDefaultValue("")
            setOnPreferenceChangeListener { _, newValue ->
                try {
                    val isSuccess =
                        preferences.edit().putString(EX_PASSWORD_PREF, newValue as String).commit()

                    isSuccess
                } catch (e: Exception) {
                    e.printStackTrace()
                    false
                }
            }
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

    private val json: Json by injectLazy()

    companion object {
        const val PREFIX_SLUG_SEARCH = "slug:"

        private var userId = ""

        private const val USER_AGENT_PREF = "USER_AGENT_PREF"

        private const val BOOST_LOAD_PREF = "BOOST_LOAD_PREF"

        private const val DOMAIN_PREF = "DOMAIN_PREF"

        private const val DOMAIN_LIST_PREF = "DOMAIN_LIST_PREF"

        private const val DOMAIN_IMG_LIST_PREF = "DOMAIN_IMG_LIST_PREF"

        private const val LANGUAGE_PREF = "LANGUAGE_PREF"

        private const val PAID_PREF = "PAID_PREF"

        private const val EX_PAID_PREF = "EX_PAID_PREF"

        private const val EX_EMAIL_PREF = "EX_EMAIL_PREF"

        private const val EX_PASSWORD_PREF = "EX_PASSWORD_PREF"

        private const val EX_TOKEN = "EX_TOKEN"

        private const val IS_LIB_PREF = "IS_LIB_PREF"
    }
}

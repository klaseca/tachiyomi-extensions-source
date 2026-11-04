package eu.kanade.tachiyomi.extension.ru.agnamer.dto

import eu.kanade.tachiyomi.source.model.SManga
import keiyoushi.utils.MarkdownDescription
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.jsonPrimitive
import org.jsoup.Jsoup

@Serializable
class TagsDto(
    val name: String,
)

@Serializable
class BranchesDto(
    val id: Long,
    @SerialName("count_chapters") val countChapters: Int,
)

@Serializable
class ImgDto(
    val high: String? = null,
    val mid: String? = null,
)

@Serializable
class LibraryDto(
    @SerialName("secondary_name") private val secondaryName: String,
    @SerialName("main_name") private val mainName: String,
    private val dir: String,
    private val cover: ImgDto,
) {
    fun toSManga(baseUrl: String, language: String?): SManga = SManga.create().apply {
        title = if (language == "rus") mainName else secondaryName
        url = "/api/titles/$dir/"
        thumbnail_url = cover.mid?.let { baseUrl + it }
    }
}

@Serializable
class MyLibraryDto(
    val title: LibraryDto,
)

@Serializable
class StatusDto(
    val id: Int,
)

@Serializable
class MangaDto(
    val id: Long,
    @SerialName("secondary_name") private val secondaryName: String,
    @SerialName("main_name") private val mainName: String,
    @SerialName("another_name") private val anotherName: String,
    private val dir: String,
    private val description: String?,
    private val cover: ImgDto,
    private val type: TagsDto,
    private val genres: List<TagsDto>,
    private val categories: List<TagsDto>,
    val branches: List<BranchesDto>,
    private val status: StatusDto,
    @SerialName("avg_rating") private val avgRating: JsonElement,
    @SerialName("count_rating") private val countRating: Int,
    @SerialName("age_limit") private val ageLimit: StatusDto,
) {
    fun toSManga(baseUrl: String, language: String?): SManga = SManga.create().apply {
        title = if (language == "rus") mainName else secondaryName
        url = "/api/titles/$dir/"
        thumbnail_url = cover.high?.let { baseUrl + it }
        description = MarkdownDescription.Builder()
            .info(if (language == "rus") secondaryName else mainName)
            .rating(avgRating.jsonPrimitive.floatOrNull, votes = countRating)
            .altTitles(anotherName.split(" / "), label = "Альтернативные названия")
            .description(this@MangaDto.description?.let { Jsoup.parseBodyFragment(it, baseUrl).body() })
            .build()
        genre = buildList {
            add(if (type.name == "Западный комикс") "Комикс" else type.name)
            when (ageLimit.id) {
                1 -> add("16+")
                2 -> add("18+")
            }
            addAll((genres + categories).map { it.name })
        }.joinToString()
        status = when (this@MangaDto.status.id) {
            1 -> SManga.COMPLETED
            2, 5 -> SManga.ONGOING
            3, 4 -> SManga.ON_HIATUS
            // LICENSED hides chapters that may still be available.
            else -> SManga.UNKNOWN
        }
    }
}

@Serializable
class PropsDto(
    @SerialName("total_pages") val totalPages: Int,
    val page: Int,
)

@Serializable
class PageWrapperDto<T>(
    val results: List<T>,
    private val next: JsonPrimitive?,
) {
    val hasNextPage: Boolean get() = next != null
}

@Serializable
class SearchPageDto(
    val results: List<LibraryDto>,
    private val meta: PropsDto,
) {
    val hasNextPage: Boolean get() = meta.page < meta.totalPages
}

@Serializable
class SeriesWrapperDto<T>(
    val content: T,
)

@Serializable
class PublisherDto(
    val name: String,
)

@Serializable
class BookDto(
    val id: Long,
    val tome: Int,
    val chapter: String,
    val name: String,
    @SerialName("upload_date") val uploadDate: String,
    @SerialName("is_paid") val isPaid: Boolean,
    @SerialName("is_bought") val isBought: Boolean?,
    val publishers: List<PublisherDto>,
)

@Serializable
class ExWrapperDto<out T>(
    val data: T,
)

@Serializable
class ExBookDto(
    val id: Long,
    val tome: Int,
    val chapter: String,
)

@Serializable
class ExLibraryDto(
    private val dir: String,
    private val name: String,
    private val img: String?,
) {
    fun toSManga(baseUrl: String): SManga = SManga.create().apply {
        title = name
        url = "/api/titles/$dir/"
        thumbnail_url = img?.let { baseUrl + it }
    }
}

@Serializable
class PagesDto(
    val link: String,
    val height: Int,
)

@Serializable
class PageDto(
    val pages: List<PagesDto>,
)

@Serializable
class ChunksPageDto(
    val pages: List<List<PagesDto>>,
)

@Serializable
class UserDto(
    val id: Long,
)

@Serializable
class ExLoginDto(private val email: String, private val password: String)

@Serializable
class ExTokenDto(val exp: Long)

@Serializable
class ExTokenResponseDto(val data: String? = null, val message: String? = null)

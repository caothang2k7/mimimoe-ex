package eu.kanade.tachiyomi.extension.vi.mimimoe

import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.source.model.*
import eu.kanade.tachiyomi.source.online.HttpSource
import kotlinx.serialization.json.*
import okhttp3.Request
import okhttp3.Response
import uy.kohesive.injekt.injectLazy
import java.text.SimpleDateFormat
import java.util.Locale

class MimiMoe : HttpSource() {

    override val name = "MimiMoe"
    override val baseUrl = "https://mimimoe.moe"
    override val lang = "vi"
    override val supportsLatest = true

    // API URL gốc mà Onichan đã tìm ra
    private val apiUrl = "https://api.mimimoe.moe/api/v2/manga"

    // Dùng cái này để xử lý JSON
    private val json: Json by injectLazy()

    // --- 1. POPULAR & LATEST (Danh sách truyện) ---
    // API: tatcatruyen?page=0&sort=updated_at...
    override fun popularMangaRequest(page: Int): Request {
        // Page trong API bắt đầu từ 0, nhưng Mihon đếm từ 1 nên phải trừ đi 1
        val apiPage = page - 1
        return GET("$apiUrl/tatcatruyen?page=$apiPage&sort=views&type=all", headers)
    }

    override fun popularMangaParse(response: Response): MangasPage {
        val result = json.parseToJsonElement(response.body.string()).jsonObject
        val dataArray = result["data"]!!.jsonArray
        
        val mangas = dataArray.map { element ->
            val obj = element.jsonObject
            SManga.create().apply {
                // ID để lát nữa gọi chi tiết
                url = "/manga/${obj["id"]!!.jsonPrimitive.content}" 
                title = obj["title"]!!.jsonPrimitive.content
                thumbnail_url = obj["coverUrl"]!!.jsonPrimitive.content
            }
        }
        // Kiểm tra xem có trang tiếp theo không (dựa vào 'next_page_url' thường thấy của Laravel/Nuxt)
        // Hoặc đơn giản cứ check nếu list > 0
        return MangasPage(mangas, mangas.isNotEmpty())
    }

    override fun latestUpdatesRequest(page: Int): Request {
        val apiPage = page - 1
        return GET("$apiUrl/tatcatruyen?page=$apiPage&sort=updated_at&type=all", headers)
    }

    override fun latestUpdatesParse(response: Response): MangasPage = popularMangaParse(response)

    // --- 2. MANGA DETAILS (Chi tiết truyện) ---
    // API: /info/{id}
    override fun mangaDetailsRequest(manga: SManga): Request {
        // Lấy ID từ url mình đã lưu ở bước trên (vd: "/manga/28519" -> "28519")
        val id = manga.url.substringAfterLast("/")
        return GET("$apiUrl/info/$id", headers)
    }

    override fun mangaDetailsParse(response: Response): SManga {
        val obj = json.parseToJsonElement(response.body.string()).jsonObject
        
        return SManga.create().apply {
            title = obj["title"]!!.jsonPrimitive.content
            description = obj["description"]?.jsonPrimitive?.content
            thumbnail_url = obj["coverUrl"]?.jsonPrimitive?.content
            
            // Xử lý tác giả (API trả về mảng)
            val authorsArray = obj["authors"]?.jsonArray
            author = authorsArray?.joinToString(", ") { it.jsonObject["name"]!!.jsonPrimitive.content }
            
            // Xử lý thể loại
            val genresArray = obj["genres"]?.jsonArray
            genre = genresArray?.joinToString(", ") { it.jsonObject["name"]!!.jsonPrimitive.content }
            
            status = SManga.ONGOING // Tạm thời để Ongoing, Onichan có thể check trường "status" nếu có
        }
    }

    // --- 3. CHAPTER LIST (Danh sách chương) ---
    // API: /gallery/{id}
    override fun chapterListRequest(manga: SManga): Request {
        val id = manga.url.substringAfterLast("/")
        return GET("$apiUrl/gallery/$id", headers)
    }

    override fun chapterListParse(response: Response): List<SChapter> {
        val result = json.parseToJsonElement(response.body.string())
        
        // Vì dữ liệu trả về là dạng Object { "15": {...}, "16": {...} } chứ không phải Array thuần
        // Nên ta phải lấy các giá trị (values) của nó
        val chaptersData = if (result is JsonObject) result.values else result.jsonArray

        return chaptersData.map { element ->
            val obj = element.jsonObject
            SChapter.create().apply {
                val chapId = obj["id"]!!.jsonPrimitive.content
                url = "/chapter/$chapId" // Lưu ID chapter vào URL
                name = obj["title"]!!.jsonPrimitive.content
                
                // Parse ngày tháng (ví dụ: "2025-05-04T01:41:44...")
                val dateStr = obj["createdAt"]?.jsonPrimitive?.content
                date_upload = parseDate(dateStr)
            }
        }.sortedByDescending { it.chapter_number } // Sắp xếp lại cho chuẩn
    }

    // --- 4. PAGE LIST (Danh sách ảnh) ---
    // API: /chapter?id={id}
    override fun pageListRequest(chapter: SChapter): Request {
        val id = chapter.url.substringAfterLast("/")
        return GET("$apiUrl/chapter?id=$id", headers)
    }

    override fun pageListParse(response: Response): List<Page> {
        val jsonObject = json.parseToJsonElement(response.body.string()).jsonObject
        // Dựa vào ảnh Onichan gửi: Key chứa ảnh là "pages"
        val pagesArray = jsonObject["pages"]!!.jsonArray

        return pagesArray.mapIndexed { index, element ->
            val imgUrl = element.jsonObject["imageUrl"]!!.jsonPrimitive.content
            Page(index, "", imgUrl)
        }
    }

    override fun imageUrlParse(response: Response): String = throw UnsupportedOperationException("Not used")

    // Helper function để parse ngày tháng
    private fun parseDate(dateStr: String?): Long {
        if (dateStr == null) return 0L
        return try {
            val format = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US)
            format.parse(dateStr)?.time ?: 0L
        } catch (e: Exception) { 0L }
    }
}

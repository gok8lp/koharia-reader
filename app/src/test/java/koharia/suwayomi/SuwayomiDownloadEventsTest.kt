package koharia.suwayomi

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class SuwayomiDownloadEventsTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `graphql transport snapshot and dequeued update are applied in order`() {
        val queue = linkedMapOf<Int, SuwayomiDownloadItem>()
        val snapshot = json.parseToJsonElement(
            """{"state":"DOWNLOADING","initial":[{"chapter":{"id":7,"mangaId":3,"name":"1"},"state":"DOWNLOADING","progress":0.2,"position":0}],"updates":[]}""",
        ).jsonObject
        assertEquals(1, applyDownloadEvent(json, queue, snapshot).queue.size)
        val dequeued = json.parseToJsonElement(
            """{"state":"STOPPED","updates":[{"type":"DEQUEUED","download":{"chapter":{"id":7,"mangaId":3,"name":"1"},"state":"QUEUED","position":0}}]}""",
        ).jsonObject
        assertEquals(0, applyDownloadEvent(json, queue, dequeued).queue.size)
    }

    @Test
    fun `finished update removes the download like the server queue`() {
        val queue = linkedMapOf<Int, SuwayomiDownloadItem>()
        val snapshot = json.parseToJsonElement(
            """{"state":"STARTED","initial":[{"chapter":{"id":7,"mangaId":3,"name":"1"},"state":"DOWNLOADING","progress":0.9,"position":0},{"chapter":{"id":8,"mangaId":3,"name":"2"},"state":"QUEUED","progress":0.0,"position":1}],"updates":[]}""",
        ).jsonObject
        assertEquals(2, applyDownloadEvent(json, queue, snapshot).queue.size)
        val finished = json.parseToJsonElement(
            """{"state":"STARTED","updates":[{"type":"FINISHED","download":{"chapter":{"id":7,"mangaId":3,"name":"1"},"state":"FINISHED","progress":1.0,"position":0}}]}""",
        ).jsonObject
        val status = applyDownloadEvent(json, queue, finished)
        assertEquals(listOf(8), status.queue.map { it.chapterId })
    }
}

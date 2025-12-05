package dev.lockedfog.streamllm.core

import dev.lockedfog.streamllm.core.memory.InMemoryStorage
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class MemoryManagerMultimodalTest {

    private lateinit var memoryManager: MemoryManager
    private lateinit var storage: InMemoryStorage
    private val testScope = TestScope(StandardTestDispatcher())

    @BeforeEach
    fun setup() {
        storage = InMemoryStorage()
        memoryManager = MemoryManager(storage, maxMemoryCount = 5, persistenceScope = testScope)
    }

    @Test
    fun `test adding and retrieving multimodal content`() = testScope.runTest {
        val mixedContent = ChatContent.Parts(listOf(
            ContentPart.TextPart("Analyze this video"),
            ContentPart.VideoPart(
                videoUrl = VideoDetailUrl(url = "https://video.mp4", maxFrames = 50)
            )
        ))

        // 使用新增的重载方法
        memoryManager.addMessage(ChatRole.USER, mixedContent)

        val history = memoryManager.getCurrentHistory()
        val savedContent = history.first().content

        // 验证类型和数据完整性
        assertIs<ChatContent.Parts>(savedContent)
        assertEquals(2, savedContent.parts.size)

        val videoPart = savedContent.parts[1]
        assertIs<ContentPart.VideoPart>(videoPart)
        assertEquals("https://video.mp4", videoPart.videoUrl.url)
        assertEquals(50, videoPart.videoUrl.maxFrames)
    }
}
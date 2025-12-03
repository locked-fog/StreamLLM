package dev.lockedfog.streamllm.utils

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class JsonSanitizerTest {

    @Test
    fun `should remove think tags`() {
        val input = """
            <think>
            Here is my reasoning...
            multi-line reasoning
            </think>
            { "answer": 42 }
        """.trimIndent()

        val result = JsonSanitizer.sanitize(input)
        assertEquals("{ \"answer\": 42 }", result)
    }

    @Test
    fun `should extract json from markdown block`() {
        val input = """
            Here is the json:
            ```json
            {
                "name": "StreamLLM"
            }
            ```
        """.trimIndent()

        val result = JsonSanitizer.sanitize(input)
        // sanitize 会去除首尾空白，所以这里预期是纯 JSON
        assertEquals("{\n    \"name\": \"StreamLLM\"\n}", result.replace("\r\n", "\n"))
    }

    @Test
    fun `should handle plain json`() {
        val input = """{"key": "value"}"""
        assertEquals(input, JsonSanitizer.sanitize(input))
    }

    @Test
    fun `should find first brace if no markdown`() {
        val input = "Sure, here is the json: { \"a\": 1 } thanks."
        assertEquals("{ \"a\": 1 }", JsonSanitizer.sanitize(input))
    }
}
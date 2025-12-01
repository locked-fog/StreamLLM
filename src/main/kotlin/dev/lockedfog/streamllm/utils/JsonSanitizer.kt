package dev.lockedfog.streamllm.utils

/**
 * JSON 清洗与提取工具。
 *
 * 用于从 LLM 的自然语言输出中提取有效的 JSON 字符串，特别是处理包含 Markdown 代码块或推理过程（Think Tags）的情况。
 */
object JsonSanitizer {
    // 使用内嵌标志 (?s) 开启 DOTALL 模式 (让点号 . 匹配换行符)
    private val THINK_PATTERN = Regex("(?s)<think>.*?</think>")

    // 同样使用 (?s) 处理 Markdown 代码块的多行匹配
    private val MARKDOWN_JSON_PATTERN = Regex("(?s)```(?:json)?\\s*(\\{.*?})\\s*```")

    /**
     * 清洗字符串，尝试提取 JSON 内容。
     *
     * 处理逻辑：
     * 1. 移除 `<think>...</think>` 标签（针对 DeepSeek R1 等推理模型）。
     * 2. 尝试提取 Markdown 代码块 (```json ... ```) 中的内容。
     * 3. 如果未找到代码块，尝试寻找最外层的花括号 `{...}`。
     *
     * @param input 原始 LLM 输出字符串。
     * @return 提取出的 JSON 字符串。
     */
    fun sanitize(input: String): String {
        // 1. 移除 <think> 标签及其内容 (处理 DeepSeek R1 等推理模型)
        var clean = THINK_PATTERN.replace(input, "").trim()

        // 2. 尝试提取 Markdown 代码块中的 JSON
        val markdownMatch = MARKDOWN_JSON_PATTERN.find(clean)
        if (markdownMatch != null) {
            // groupValues[0] 是整个匹配，groupValues[1] 是第一个捕获组 (即 {} 内容)
            return markdownMatch.groupValues[1].trim()
        }

        // 3. 如果没有 Markdown 标记，尝试寻找最外层的 {}
        val firstBrace = clean.indexOf('{')
        val lastBrace = clean.lastIndexOf('}')

        if (firstBrace != -1 && lastBrace != -1 && lastBrace > firstBrace) {
            clean = clean.substring(firstBrace, lastBrace + 1)
        }

        return clean.trim()
    }
}
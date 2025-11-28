package dev.lockedfog.streamllm.core

import retrofit2.http.Query

class MemoryManager {
    private val messages = mutableListOf<MessageEntry>()

    data class MessageEntry (val role: String, val content: String)

    fun addMessage(role: String, content: String){
        messages.add(MessageEntry(role,content))
    }

    fun buildPrompt(newQuery: String):String {
        if (messages.isEmpty()) {
            return newQuery
        }

        val sb = StringBuilder()
        // 拼接历史
        messages.forEach { msg ->
            sb.append("${msg.role}: ${msg.content}\n")
        }
        // 拼接当前问题
        sb.append("User: $newQuery\n")
        // (可选) 可以在这里加上 "AI: " 引导模型开始回答

        return sb.toString()
    }

    fun clear() {
        messages.clear()
    }

    fun getHistory(): List<MessageEntry> = messages.toList()
}
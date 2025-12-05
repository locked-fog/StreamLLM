package dev.lockedfog.streamllm.core

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonEncoder
import kotlinx.serialization.json.JsonPrimitive

/**
 * 对话消息内容接口。
 *
 * 对应 API 中的 `content` 字段。为了兼容 OpenAI/Siliconflow 格式，
 * 该字段既可以是简单的字符串（纯文本模式），也可以是对象数组（多模态模式）。
 */
@Serializable(with = ChatContentSerializer::class)
sealed interface ChatContent {
    /**
     * 纯文本内容模式。
     * API 序列化结果: `"content": "some text"`
     */
    data class Text(val text: String) : ChatContent

    /**
     * 多模态片段模式。
     * API 序列化结果: `"content": [{"type": "text", ...}, {"type": "image_url", ...}]`
     */
    data class Parts(val parts: List<ContentPart>) : ChatContent
}

/**
 * 多模态内容的组成片段。
 *
 * [Fix] 移除了显式的 `type` 属性，交由 kotlinx.serialization 的多态机制自动处理。
 * JSON 输出中仍然会包含 "type": "text/image_url/..." 字段，值取决于 @SerialName。
 */
@Serializable
sealed interface ContentPart {
    // 移除 abstract val type: String，避免与 JSON discriminator 冲突

    /**
     * 文本片段。
     */
    @Serializable
    @SerialName("text")
    data class TextPart(
        val text: String
    ) : ContentPart

    /**
     * 图片片段。
     */
    @Serializable
    @SerialName("image_url")
    data class ImagePart(
        @SerialName("image_url") val imageUrl: ImageUrl
    ) : ContentPart

    /**
     * 音频片段 (Qwen-Omni 等模型支持)。
     */
    @Serializable
    @SerialName("audio_url")
    data class AudioPart(
        @SerialName("audio_url") val audioUrl: MediaUrl
    ) : ContentPart

    /**
     * 视频片段 (Qwen-VL/Omni 等模型支持)。
     */
    @Serializable
    @SerialName("video_url")
    data class VideoPart(
        @SerialName("video_url") val videoUrl: VideoDetailUrl
    ) : ContentPart
}

// --- 辅助数据结构 ---

@Serializable
data class ImageUrl(
    val url: String,
    val detail: String? = "auto"
)

@Serializable
data class MediaUrl(
    val url: String
)

@Serializable
data class VideoDetailUrl(
    val url: String,
    val detail: String? = "auto",
    @SerialName("max_frames") val maxFrames: Int? = null,
    val fps: Int? = null
)

/**
 * 自定义序列化器，用于处理 content 字段的动态类型（String 或 Array）。
 */
object ChatContentSerializer : KSerializer<ChatContent> {
    override val descriptor = buildClassSerialDescriptor("ChatContent")

    override fun deserialize(decoder: Decoder): ChatContent {
        val input = decoder as? JsonDecoder ?: throw SerializationException("Expected JsonDecoder")
        val element = input.decodeJsonElement()

        return if (element is JsonPrimitive && element.isString) {
            ChatContent.Text(element.content)
        } else if (element is JsonArray) {
            val parts = input.json.decodeFromJsonElement(
                ListSerializer(ContentPart.serializer()),
                element
            )
            ChatContent.Parts(parts)
        } else {
            // 容错：遇到 null 或其他类型时返回空文本
            ChatContent.Text("")
        }
    }

    override fun serialize(encoder: Encoder, value: ChatContent) {
        val output = encoder as? JsonEncoder ?: throw SerializationException("Expected JsonEncoder")
        when (value) {
            is ChatContent.Text -> output.encodeString(value.text)
            is ChatContent.Parts -> output.encodeSerializableValue(
                ListSerializer(ContentPart.serializer()),
                value.parts
            )
        }
    }
}

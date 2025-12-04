package dev.lockedfog.streamllm

import dev.lockedfog.streamllm.core.MemoryManager
import dev.lockedfog.streamllm.core.memory.InMemoryStorage
import dev.lockedfog.streamllm.core.memory.MemoryStorage
import dev.lockedfog.streamllm.provider.LlmProvider
import dev.lockedfog.streamllm.provider.openai.OpenAiClient
import io.ktor.client.HttpClient
import kotlinx.serialization.json.Json
import java.time.Duration

/**
 * StreamLLM 的全局入口单例。
 *
 * 负责库的初始化、全局配置存储（如 JSON 序列化器）以及全局记忆管理器的持有。
 * 在使用任何 DSL 功能之前，必须先调用 [init] 方法。
 */
object StreamLLM {
    private const val maxMemoryCount: Int = 10
    /**
     * 全局共享的 JSON 序列化实例。
     * 配置为宽松模式：忽略未知键、宽松解析、编码默认值。
     */
    val json: Json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        encodeDefaults = true
    }

    /**
     * 全局记忆管理器实例。
     * 只要应用进程存在，记忆数据就会保留（基于内存存储）。
     */
    var memory: MemoryManager = MemoryManager(InMemoryStorage(),maxMemoryCount)
        private set

    /**
     * 当前配置的默认 LLM 提供者。
     */
    @Volatile
    var defaultProvider: LlmProvider? = null
        private set

    /**
     * 使用自定义的 [LlmProvider] 初始化 StreamLLM。
     *
     * 如果之前已经初始化过，会先调用旧 Provider 的 [close] 方法释放资源。
     *
     * @param provider 实现 [LlmProvider] 接口的实例。
     * @param storage （可选）自定义的记忆持久化储存实现，默认为内存储存
     * @param maxMemoryCount （可选）最大缓存的会话数量（LRU），默认为10
     */
    fun init(
        provider: LlmProvider,
        storage: MemoryStorage = InMemoryStorage(),
        maxMemoryCount: Int = 10
    ) {
        close() // 如果之前有初始化，先释放资源
        this.defaultProvider = provider
        this.memory = MemoryManager(storage, maxMemoryCount)
    }

    /**
     * 使用 OpenAI 兼容配置初始化 StreamLLM。
     *
     * 内部会自动创建 [OpenAiClient]。
     *
     * @param baseUrl API 基础地址 (如 "https://api.openai.com/v1")。
     * @param apiKey API 密钥。
     * @param modelName 默认模型名称。
     * @param timeoutSeconds 请求超时时间 (秒)。
     * @param httpClient (可选) 外部注入的 Ktor HttpClient，用于资源复用。
     * @param storage （可选）自定义的记忆持久化储存实现[MemoryStorage]，默认为[InMemoryStorage]
     * @param maxMemoryCount （可选）最大缓存的会话数量（LRU），默认为10
     */
    fun init(
        baseUrl: String,
        apiKey: String,
        modelName: String,
        timeoutSeconds: Long = 60,
        httpClient: HttpClient? = null,
        storage: MemoryStorage = InMemoryStorage(),
        maxMemoryCount: Int
    ) {
        val adapter = OpenAiClient(
            baseUrl = baseUrl,
            apiKey = apiKey,
            defaultModel = modelName,
            timeout = Duration.ofSeconds(timeoutSeconds),
            httpClient = httpClient
        )
        this.init(adapter, storage, maxMemoryCount)
    }

    /**
     * 关闭当前的 Provider 并释放相关资源 (如 HTTP 连接)。
     *
     * 在应用退出或需要重置配置时调用。
     */
    fun close() {
        try {
            defaultProvider?.close()
        } catch (e: Exception) {
            // ignore or log
        } finally {
            defaultProvider = null
        }
    }
}
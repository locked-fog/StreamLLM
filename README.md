# StreamLLM

[![](https://jitpack.io/v/locked-fog/StreamLLM.svg)](https://jitpack.io/#locked-fog/StreamLLM)
[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](https://opensource.org/licenses/MIT)

**StreamLLM** 是一个专为 Kotlin (JVM/Android) 开发者设计的轻量级、**完全非阻塞**且**协程原生**的 LLM 工作流编排库。

它提供了一套极具表现力的 DSL，支持 **多模态交互 (Multimodal)**、**流式自适应批处理 (Adaptive Batching)** 和 **混合记忆管理**。

## ✨ 核心特性 (Key Features)

* ⚡ **Adaptive Batching (自适应批处理)**: 独创的“背压自适应”机制。当 UI 渲染变慢时，库会自动积攒网络 Token 并批量推送，彻底解决流式输出导致的 UI 卡顿问题。
* 🧠 **Hybrid Memory Architecture**: 内置 L1 LRU 内存缓存 + 异步持久化接口 (`MemoryStorage`)，支持读写分离，确保 I/O 不阻塞对话流。
* 👁️ **Native Multimodal**: 原生支持多模态输入（图片、音频、视频），完美适配 OpenAI / SiliconFlow 格式。
* 🧩 **Flexible Architecture**: 基于实例的 `StreamClient` 设计，支持多实例并行（如同时连接不同的模型服务），不再受限于单例模式。
* 🛠 **Tool & Reasoning Ready**: 内置 `ToolCall` 和 `ReasoningContent` (DeepSeek R1) 数据结构支持。
* 🔌 **Universal Provider**: 完美适配 OpenAI 标准接口 (DeepSeek, SiliconFlow, Moonshot 等)。

## 📦 安装 (Installation)

Step 1. 在根目录的 `settings.gradle.kts` 中添加 JitPack 仓库：

```kotlin
dependencyResolutionManagement {
    repositories {
        mavenCentral()
        maven(url = "[https://jitpack.io](https://jitpack.io)")
    }
}
````

Step 2. 在模块级 `build.gradle.kts` 中添加依赖：

```kotlin
dependencies {
    implementation("com.github.locked-fog:StreamLLM:v0.3.5") // 请使用最新版本
}
```

## 🚀 快速开始 (Quick Start)

### 1\. 初始化客户端 (Initialize Client)

v0.3.5+ 推荐使用 `StreamClient` 实例，支持依赖注入和多实例管理。

```kotlin
import dev.lockedfog.streamllm.StreamClient
import dev.lockedfog.streamllm.provider.openai.OpenAiClient
import dev.lockedfog.streamllm.core.memory.InMemoryStorage

// 1. 创建 Provider (网络层)
val provider = OpenAiClient(
    baseUrl = "[https://api.siliconflow.cn/v1](https://api.siliconflow.cn/v1)",
    apiKey = "sk-your-key",
    defaultModel = "Qwen/Qwen2.5-7B-Instruct"
)

// 2. 创建 Client (编排层)
val client = StreamClient(
    provider = provider,
    storage = InMemoryStorage(), // 或自定义的 Room/SQLDelight 实现
    maxMemoryCount = 10
)
```

### 2\. 基础流式对话 (Streaming Chat)

```kotlin
import dev.lockedfog.streamllm.dsl.stream
import kotlinx.coroutines.*

launch {
    // 使用 client.stream 开启会话作用域
    client.stream {
        // 自动管理历史记录
        "你好，请介绍一下你自己".stream { token ->
            print(token) // 实时输出 Token
        }
    }
}
```

### 3\. 多模态交互 (Multimodal Request)

StreamLLM 原生支持发送图片、音频等富媒体内容。

```kotlin
import dev.lockedfog.streamllm.core.ChatContent
import dev.lockedfog.streamllm.core.ContentPart
import dev.lockedfog.streamllm.core.ImageUrl

launch {
    client.stream {
        // 构造多模态消息内容
        val visionContent = ChatContent.Parts(listOf(
            ContentPart.TextPart("这张图片里有什么？"),
            ContentPart.ImagePart(
                imageUrl = ImageUrl("[https://example.com/image.jpg](https://example.com/image.jpg)")
            )
        ))

        // 发送请求
        visionContent.stream { token ->
            print(token)
        }
    }
}
```

### 4\. 结构化输出 (Structured Output)

```kotlin
@Serializable
data class WeatherIntent(val city: String, val date: String)

launch {
    client.stream {
        // 请求 JSON 并自动反序列化，内置自动纠错重试机制
        val intent = "查询明天北京的天气".ask<WeatherIntent>(
            promptTemplate = "提取意图，返回严格的 JSON 格式。"
        )
        
        println("City: ${intent.city}, Date: ${intent.date}")
    }
}
```

### 5\. 记忆管理 (Memory Management)

```kotlin
launch {
    // 异步预加载记忆体 (建议在进入页面前调用)
    client.memory.preLoad("session_101")
    
    client.stream {
        // 切换到指定会话上下文
        switchMemory("session_101")
        
        // 设置人设 (System Prompt)
        setSystemPrompt("session_101", "你是一个资深的 Kotlin 工程师")
        
        "如何使用 Flow?".ask()
    }
}
```

## ⚙️ 核心原理 (Architecture)

### Concurrency (并发模型)

采用 **Buffer + Mutex + Skipping** 策略。当 UI 消费协程被阻塞（如渲染耗时）时，StreamLLM 会自动在缓冲区积压网络包并合并推送，实现“背压自适应”，既不阻塞网络 I/O，也不卡顿 UI。

### Data Persistence (数据持久化)

采用 **Write-Through + LRU** 策略：

* **Read**: 优先读取内存缓存 (L1)。
* **Write**: 内存写入后立即异步触发 `Storage` 写入。
* **Eviction**: 缓存超限时自动驱逐最久未使用的会话，并在驱逐前强制同步到磁盘。

## License

[MIT](https://opensource.org/licenses/MIT) © 2025 Locked\_Fog
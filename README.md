# StreamLLM

[![](https://jitpack.io/v/locked-fog/StreamLLM.svg)](https://jitpack.io/#locked-fog/StreamLLM)
[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](https://opensource.org/licenses/MIT)

**StreamLLM** 是一个专为 Kotlin (JVM/Android) 开发者打造的轻量级、**完全非阻塞**且**协程原生**的 LLM 工作流编排库。

它不仅仅是一个 HTTP 客户端，更是一套完整的对话编排引擎。从**自适应流式消抖**到**Re-Act 工具调用循环**，StreamLLM 旨在帮助开发者用最少的代码构建强大的 AI 应用。

## ✨ 核心特性 (Key Features)

* 🛠️ **真正的工具调用 (True Tool Calling)**: 内置完整的 Re-Act (Reasoning-Action) 循环。你只需注册 Kotlin 函数，StreamLLM 会自动处理“模型请求 -\> 执行工具 -\> 提交结果 -\> 再次推理”的全过程，支持多轮递归调用。
* 👁️ **原生多模态支持 (Native Multimodal)**: 不再局限于文本。通过统一的 `ChatContent` 接口，原生支持发送图片、音频和视频。完美适配 OpenAI Vision 及 SiliconFlow (Qwen-VL, DeepSeek-VL) 等多模态协议。
* ⚡ **自适应批处理 (Adaptive Batching)**: 独创的“背压自适应”机制。当 UI 渲染变慢时，库会自动在缓冲区积攒网络 Token 并批量推送 (Chunk Aggregation)，彻底解决高频 SSE 流式输出导致的 UI 卡顿和丢帧问题。
* 🧠 **混合记忆架构 (Hybrid Memory)**: 内置 L1 LRU 内存缓存 + L2 异步持久化接口。支持 Write-Through 策略，确保 I/O 操作绝不阻塞对话主线程，同时保证数据不丢失。
* 🔌 **通用协议适配**: 完美适配 OpenAI 标准接口，开箱支持 **OpenAI**、**DeepSeek**、**SiliconFlow (硅基流动)**、**Moonshot (Kimi)** 等主流厂商。
* 🧩 **结构化输出与纠错**: 支持 `ask<T>` 泛型请求，自动解析 JSON。内置 Self-Correction 机制，当模型输出的 JSON 格式错误时，自动回传错误信息要求模型修正。

## 📦 安装 (Installation)

Step 1. 在根目录的 `settings.gradle.kts` 中添加 JitPack 仓库：

```kotlin
dependencyResolutionManagement {
    repositories {
        mavenCentral()
        maven(url = "https://jitpack.io")
    }
}
```

Step 2. 在模块级 `build.gradle.kts` 中添加依赖：

```kotlin
dependencies {
    implementation("com.github.locked-fog:StreamLLM:master-SNAPSHOT")
}
```

## 🚀 快速开始 (Quick Start)

### 1\. 初始化 (Initialization)

在应用启动时（如 `Application.onCreate`）初始化全局单例。

```kotlin
import dev.lockedfog.streamllm.StreamLLM
import dev.lockedfog.streamllm.core.memory.InMemoryStorage

// 初始化 SiliconFlow (或 OpenAI/DeepSeek)
StreamLLM.init(
    baseUrl = "https://api.siliconflow.cn/v1",
    apiKey = "sk-your-api-key",
    modelName = "Qwen/Qwen2.5-72B-Instruct", // 默认模型
    storage = InMemoryStorage(), // 建议实现 MemoryStorage 接口对接 Room/SQLDelight
    maxMemoryCount = 10
)
```

### 2\. 基础流式对话 (Streaming Chat)

使用 `stream` DSL 开启对话作用域，自动管理上下文。

```kotlin
import dev.lockedfog.streamllm.dsl.stream

// 在协程中调用
launch {
    stream {
        // 纯文本请求
        "你好，请介绍一下 Kotlin 协程".stream { token ->
            print(token) // 实时输出，已自动处理背压
        }
    }
}
```

### 3\. 工具调用 (Tool Calling) 🆕

StreamLLM 让工具调用变得异常简单。你只需**注册**工具，剩下的递归执行逻辑由库自动完成。

```kotlin
stream {
    // 1. 注册工具 (支持 JSON Schema 定义)
    registerTool(
        name = "get_weather",
        description = "查询指定城市的天气",
        parametersJson = """
            {
                "type": "object",
                "properties": {
                    "city": { "type": "string", "description": "城市名称" }
                },
                "required": ["city"]
            }
        """
    ) { args ->
        // 2. 编写工具执行逻辑 (Kotlin 代码)
        val city = parseCityFromJson(args)
        val weather = weatherService.query(city) 
        "The weather in $city is $weather" // 返回结果给模型
    }

    // 3. 发起提问
    // 模型会自动判定调用工具 -> 执行 Kotlin 代码 -> 获取结果 -> 生成最终回答
    "北京今天天气怎么样？".stream { token ->
        print(token)
    }
}
```

### 4\. 多模态交互 (Multimodal) 🆕

发送图片、视频或音频给模型。

```kotlin
import dev.lockedfog.streamllm.core.ChatContent
import dev.lockedfog.streamllm.core.ContentPart
import dev.lockedfog.streamllm.core.ImageUrl

stream {
    // 构造多模态内容
    val visionContent = ChatContent.Parts(listOf(
        ContentPart.TextPart("这张图片里有什么？"),
        ContentPart.ImagePart(
            imageUrl = ImageUrl("https://example.com/cat.jpg")
        ),
        // 支持视频 (Qwen-VL 等)
        // ContentPart.VideoPart(...) 
    ))

    // 发送请求
    visionContent.stream { token ->
        print(token)
    }
}
```

### 5\. 结构化输出 (Structured Output)

请求模型返回强类型的对象，内置自动重试纠错。

```kotlin
@Serializable
data class UserIntent(val intent: String, val confidence: Double)

stream {
    // 自动将结果反序列化为 UserIntent 对象
    // 如果 JSON 解析失败，会自动将错误反馈给模型进行重试 (Max Retries = 3)
    val result = "我想订一张去上海的机票".ask<UserIntent>()
    
    println("Intent: ${result.intent}")
}
```

## 🧠 记忆管理 (Memory Management)

StreamLLM 默认自动管理对话历史 (Context Window)。

```kotlin
stream {
    // 切换会话 (支持多用户/多会话并行)
    switchMemory("session_1001")
    
    // 设置人设 (System Prompt)
    setSystemPrompt("session_1001", "你是一个资深的 Android 架构师")
    
    // 本次对话会自动携带 session_1001 的历史记录
    "如何设计 MVVM?".ask()
    
    // 清空记忆
    clearMemory()
}
```

## ⚙️ 架构原理

### 并发与背压 (Concurrency & Backpressure)

采用 **Buffer + Mutex + Skipping** 策略。
在流式传输中，如果 UI 渲染速度慢于网络 IO 速度，StreamLLM 不会阻塞网络线程，而是将 Token 积压在 `StringBuffer` 中。当 UI 协程准备好处理下一帧时，它会一次性获取积压的所有 Token。这实现了**零丢包**且**零卡顿**的丝滑体验。

### Re-Act 引擎 (The Execution Loop)

对于工具调用，`StreamScope` 内部维护了一个 `while` 循环。
当 LLM 返回 `tool_calls` 时，引擎会暂停返回给用户，在后台执行注册的 Kotlin 函数，并将结果封装为 `ToolMessage` 插入历史，然后立即发起下一轮请求。这个过程对上层调用者是透明的，支持流式和非流式两种模式。

## License

[MIT](https://opensource.org/licenses/MIT) © 2025 Locked\_Fog
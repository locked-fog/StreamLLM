# StreamLLM

[![](https://jitpack.io/v/locked-fog/StreamLLM.svg)](https://jitpack.io/#locked-fog/StreamLLM)
[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](https://opensource.org/licenses/MIT)

**StreamLLM** 是一个专为 Kotlin (JVM/Android) 开发者设计的轻量级、**完全非阻塞**且**协程原生**的 LLM 工作流编排库。它基于 Kotlin Flow 和 DSL，提供了一套极具表现力的接口，让你像写脚本一样管理 AI 对话、记忆、流式响应和错误处理。

🚀 **v0.4.0 (Enhanced) 核心特性：**

* ⚡ **Adaptive Batching (自适应批处理)**: 独创的“背压自适应”机制。当 UI 渲染变慢时，库会自动积攒网络 Token 并批量推送，**彻底解决流式输出导致的 UI 卡顿问题**，同时保持网络层全速接收。
* 🧵 **Suspend-Ready**: 所有回调均升级为 `suspend` 类型。你可以在流式回调中安全地执行 `delay`、数据库写入或复杂的业务逻辑，而不会阻塞底层网络 I/O。
* 🧠 **Advanced Memory**: 内置全局记忆管理，支持多记忆体切换、窗口控制 (Context Window) 和多种读写策略 (ReadOnly/WriteOnly)。
* 🛠 **Type-Safe Extraction**: 强类型结构化输出 (`ask<T>`)，内置自动纠错重试机制 (Self-Correction)，自动处理 JSON 解析失败。
* 🔌 **Universal Provider**: 完美适配 OpenAI 标准接口 (DeepSeek, SiliconFlow, Moonshot 等)，支持 `<think>` 标签自动清洗。

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
    implementation("com.github.locked-fog:StreamLLM:v0.4.0") // 请使用最新版本
}
```

## 🚀 快速开始 (Quick Start)

### 1\. 初始化 (Initialization)

```kotlin
import dev.lockedfog.streamllm.StreamLLM

// 在 Application onCreate 或 main 函数中初始化
StreamLLM.init(
    apiKey = "sk-your-api-key",
    baseUrl = "[https://api.deepseek.com/v1](https://api.deepseek.com/v1)", 
    modelName = "deepseek-chat",
    timeoutSeconds = 60
)
```

### 2\. 流式对话 (Streaming with Suspend Support)

StreamLLM 的 `stream` DSL 是协程作用域，回调函数天然支持挂起。

```kotlin
import dev.lockedfog.streamllm.dsl.stream
import kotlinx.coroutines.*

// 在协程中启动
launch {
    stream {
        println("User: 请讲一个关于时间的笑话。")
        
        // 使用 .stream { token -> ... } 接收流式输出
        val fullResponse = "请讲一个关于时间的笑话。".stream { token ->
            // ✨ 这里的 token 可能是单个字符，也可能是积攒的一段文本（如果 UI 慢的话）
            print(token)
            
            // ✨ 支持挂起函数！模拟耗时的 UI 渲染，网络层不会被阻塞
            delay(50) 
        }
        
        println("\nToken Usage: ${lastUsage?.totalTokens}")
    }
}
```

### 3\. 结构化输出与纠错 (Structured Output)

```kotlin
@Serializable
data class UserIntent(val action: String, val params: String)

launch {
    stream {
        // 请求结构化数据，如果 LLM 返回了错误的 JSON，库会自动重试并附带错误信息
        val intent = "帮我把空调调到24度".ask<UserIntent>(
            promptTemplate = "提取意图，返回严格的 JSON 格式。"
        )
        
        println("Action: ${intent.action}, Params: ${intent.params}")
    }
}
```

### 4\. 记忆管理 (Memory Control)

```kotlin
launch {
    stream {
        // 1. 创建并切换到专用记忆体
        newMemory("translator", system = "你是一个翻译官，只输出翻译结果")
        
        // 2. 无状态调用 (不记录历史)
        "Hello World".ask(strategy = MemoryStrategy.Stateless)
        
        // 3. 切换回默认记忆体
        switchMemory("default")
    }
}
```

## ⚙️ 核心原理 (Performance)

StreamLLM 引入了 **Buffer + Mutex** 的并发模型来处理流式响应：

1.  **Network Producer**: 网络层在一个独立的协程中全速接收 SSE 数据，无锁写入内部缓冲区 (Buffer)。
2.  **UI Consumer**: 您的回调函数 (`onToken`) 在另一个协程中运行。每次回调前会尝试获取锁。
3.  **Adaptive Logic**:
    * 如果回调处理得很快，它能实时取到每一个 Token。
    * 如果回调处理得很慢 (如复杂的 Markdown 渲染)，网络数据会积压在 Buffer 中。当回调结束释放锁时，下一次调用会一次性获取所有积压的文本。

**结果**：无论 UI 多卡，网络请求永不超时，且最终显示内容零丢失。

## License

[MIT](https://www.google.com/search?q=LICENSE) © 2025 Locked\_Fog
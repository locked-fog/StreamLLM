# StreamLLM

[![](https://jitpack.io/v/locked-fog/StreamLLM.svg)](https://jitpack.io/#locked-fog/StreamLLM)
[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](https://opensource.org/licenses/MIT)

**StreamLLM** 是一个专为 Kotlin (JVM/Android) 开发者设计的轻量级、**完全非阻塞**且**协程原生**的 LLM 工作流编排库。它基于 Kotlin Flow 和 DSL，提供了一套极具表现力的接口，让开发者像写脚本一样管理 AI 对话、记忆、流式响应和错误处理。

## ✨ 核心特性 (Key Features)

* ⚡ **Adaptive Batching (自适应批处理)**: 独创的“背压自适应”机制。当 UI 渲染变慢时，库会自动积攒网络 Token 并批量推送，彻底解决流式输出导致的 UI 卡顿问题，同时保持网络层全速接收。
* 🧠 **Hybrid Memory Architecture (混合记忆架构)**: 
    * **L1 LRU Cache**: 基于内存的高速缓存，支持 LRU 淘汰策略，自动管理内存占用。
    * **Async Persistence**: 提供标准 `MemoryStorage` 接口，支持异步持久化到数据库（如 Room, SQLDelight），读写分离设计确保 I/O 不阻塞对话流。
    * **Request Coalescing**: 内置 `preLoad` 预热与防击穿机制，避免重复加载。
* 🧵 **Suspend-Ready**: 全链路协程支持。所有回调均为 `suspend` 类型，允许在流式响应中安全地执行数据库写入、网络请求或复杂业务逻辑。
* 🛠 **Type-Safe Extraction**: 强类型结构化输出 (`ask<T>`)，内置自动纠错重试机制 (Self-Correction)，自动清洗 Markdown 与 `<think>` 标签。
* 🔌 **Universal Provider**: 完美适配 OpenAI 标准接口 (DeepSeek, SiliconFlow, Moonshot 等)，并支持扩展自定义 Provider。

更多特性参见>>[API参考手册](https://github.com/locked-fog/StreamLLM/blob/main/API_REFERENCE.md)

## 📦 安装 (Installation)

Step 1. 在根目录的 `settings.gradle.kts` 中添加 JitPack 仓库：

```kotlin
dependencyResolutionManagement {
    repositories {
        mavenCentral()
        maven(url = "https://jitpack.io")
    }
}
````

Step 2. 在模块级 `build.gradle.kts` 中添加依赖：

```kotlin
dependencies {
    implementation("com.github.locked-fog:StreamLLM:v0.3.3") // 请使用最新版本
}
```

## 🚀 快速开始 (Quick Start)

### 1\. 初始化 (Initialization)

支持配置持久化存储层和缓存大小。

```kotlin
import dev.lockedfog.streamllm.StreamLLM
import dev.lockedfog.streamllm.core.memory.InMemoryStorage

// 在 Application onCreate 或 main 函数中初始化
StreamLLM.init(
    apiKey = "sk-your-api-key",
    baseUrl = "[https://api.deepseek.com/v1](https://api.deepseek.com/v1)", 
    modelName = "deepseek-chat",
    // [可选] 配置持久化存储 (默认使用 InMemoryStorage)
    storage = MyDatabaseStorage(), 
    // [可选] 最大内存会话缓存数 (默认 10)，超限自动 LRU 淘汰并落库
    maxMemoryCount = 20 
)
```

### 2\. 流式对话 (Streaming with Suspend Support)

StreamLLM 的 `stream` DSL 是协程作用域，回调函数天然支持挂起。

```kotlin
import dev.lockedfog.streamllm.dsl.stream
import kotlinx.coroutines.*

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

### 4\. 记忆管理与预加载 (Memory & Preload)

```kotlin
launch {
    // [推荐] 在进入聊天页面前预加载记忆（异步非阻塞）
    StreamLLM.memory.preLoad("translator")
    
    stream {
        // 1. 切换到记忆体 (如果已预加载则零延迟，否则自动挂起等待加载)
        switchMemory("translator")
        
        // 2. 设置 System Prompt (如果新建)
        if (StreamLLM.memory.getSystemPrompt("translator") == null) {
             setSystemPrompt("translator", "你是一个翻译官")
        }
        
        // 3. 对话 (自动读写历史)
        "Hello World".ask()
    }
}
```

## ⚙️ 核心原理 (Architecture)

### 1\. Concurrency (并发模型)

StreamLLM 引入了 **Buffer + Mutex** 模型：

* **Network Producer**: 独立协程全速接收 SSE 数据。
* **UI Consumer**: 消费协程按需获取锁。
* **Adaptive Logic**: 当 UI 消费慢时，Buffer 自动积压并合并 Token，实现“背压自适应”，杜绝 UI 卡顿。

### 2\. Memory System (记忆体架构)

为了平衡 I/O 性能与内存占用，StreamLLM 采用了 **Write-Through + LRU** 策略：

* **Read**: 优先读取 L1 内存缓存，零 I/O 延迟。
* **Write**: 采用 Write-Through 策略，更新内存的同时启动后台协程异步写入 `Storage`。
* **Eviction**: 当缓存超过 `maxMemoryCount` 时，LRU 算法自动淘汰最久未使用的会话，并在淘汰前触发兜底持久化。
* **Hydration**: `switchMemory` 或 `preLoad` 时自动通过 Singleflight 模式从 Storage 加载数据，防止缓存击穿。

## License

[MIT](https://opensource.org/licenses/MIT) © 2025 Locked\_Fog
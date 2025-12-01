# StreamLLM

[![](https://jitpack.io/v/locked-fog/StreamLLM.svg)](https://jitpack.io/#locked-fog/StreamLLM)
[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](https://opensource.org/licenses/MIT)

**StreamLLM** 是一个专为 Kotlin (JVM/Android) 开发者设计的轻量级、**完全非阻塞**的 LLM 工作流编排库。它基于 Kotlin 协程和 DSL，提供了一套极具表现力的接口，让你像写脚本一样管理 AI 对话、记忆、流式响应和错误处理。

✨ **v0.3.0 核心特性：**
* ⚡ **Non-blocking I/O**: 全链路 `suspend` 设计，不再阻塞主线程，完美适配 Android UI 和高并发服务端 (Ktor/Spring WebFlux)。
* 🧠 **Advanced Memory**: 内置全局记忆管理，支持多记忆体切换、窗口控制和读写策略。
* 📊 **Observability**: 暴露 Token 用量 (Usage) 元数据，支持精确计费统计。
* 🛡️ **Robustness**: 统一的结构化异常处理体系 (Authentication, RateLimit, ServerError 等) 和资源生命周期管理。
* 🛠 **Type-Safe Extraction**: 自动将非结构化文本转换为 Kotlin 强类型对象（支持自动纠错重试）。
* 🔌 **Universal Provider**: 完美适配 SiliconFlow、DeepSeek (自动过滤 `<think>` 标签)、OpenAI 及任何兼容接口。

## 📦 安装 (Installation)

Step 1. 在根目录的 `settings.gradle.kts` 中添加 JitPack 仓库：

```kotlin
dependencyResolutionManagement {
    repositories {
        mavenCentral()
        maven(url = url("https://jitpack.io"))
    }
}
````

Step 2. 在模块级 `build.gradle.kts` 中添加依赖：

```kotlin
dependencies {
    implementation("com.github.locked-fog:StreamLLM:v0.3.0") // 请使用最新版本
}
```

## 🚀 快速开始 (Quick Start)

### 1\. 初始化 (Initialization)

推荐在 Application 启动时配置。

```kotlin
import dev.lockedfog.streamllm.StreamLLM

// 基础初始化
StreamLLM.init(
    apiKey = "sk-your-api-key",
    baseUrl = "[https://api.siliconflow.cn/v1](https://api.siliconflow.cn/v1)", 
    modelName = "deepseek-ai/DeepSeek-V3",
    timeoutSeconds = 60
)

// 高级初始化：共享 HttpClient (推荐)
val myClient = HttpClient(OkHttp) { /* 自定义配置 */ }
StreamLLM.init(..., httpClient = myClient)
```

### 2\. 基础对话 (Basic Chat)

注意：由于 v0.3.0 采用了非阻塞设计，`stream` 现在是挂起函数，必须在协程作用域内调用。

```kotlin
import dev.lockedfog.streamllm.dsl.stream
import kotlinx.coroutines.*

fun main() = runBlocking {
    stream {
        // 简单的同步问答 (自动管理记忆)
        val answer = "你好，我是个程序员".ask() 
        println(answer)

        // 获取 Token 用量
        println("Token Usage: ${lastUsage?.totalTokens}")
    }
}
```

### 3\. 记忆管理 (Memory Management)

```kotlin
import dev.lockedfog.streamllm.core.MemoryStrategy

launch {
    stream {
        // 1. 切换/创建新的记忆体
        newMemory("coding_assistant", system = "你是一个严谨的代码专家")
        
        // 2. 使用策略控制记忆
        "总结一下之前的对话".ask(
            strategy = MemoryStrategy.ReadOnly, // 不让"总结"这个请求污染历史
            historyWindow = 10 // 只读取最近 10 条历史
        )

        // 3. 临时覆盖 System Prompt
        "把这句话翻译成英文".ask(
            system = "你是一个翻译官，只输出翻译结果", // 临时覆盖，不影响记忆体
            strategy = MemoryStrategy.Stateless
        )
    }
}
```

### 4\. 结构化提取与自动纠错

```kotlin
@Serializable
data class UserIntent(val action: String, val target: String)

launch {
    stream {
        try {
            // 自动提取 + 类型转换 + JSON 错误自动重试
            val intent = "把空调调到24度".ask<UserIntent>(
                promptTemplate = "提取意图，返回 JSON。"
            )
            println("Action: ${intent.action}")
        } catch (e: LlmException) {
            // 处理业务异常 (如鉴权失败、余额不足)
            println("API Error: ${e.message}")
        }
    }
}
```

### 5\. 资源释放

当不再需要使用库时（例如应用关闭），可以释放资源：

```kotlin
StreamLLM.close()
```

## 🛠 参数详解

| 参数 | 说明 | 默认值 |
| --- | --- | --- |
| `strategy` | 记忆策略 (ReadWrite, ReadOnly, WriteOnly, Stateless) | ReadWrite |
| `historyWindow` | 历史窗口大小 (-1=全部, 0=无, N=最近N条) | -1 |
| `system` | 临时 System Prompt (覆盖记忆体设定) | null |
| `formatter` | 历史序列化格式字符串 (如 "user=Q:{{content}};...") | null |
| `temperature` | 随机性 (0.0 - 2.0) | 全局默认 |

## License

[MIT](https://www.google.com/search?q=LICENSE) © 2025 Locked\_Fog
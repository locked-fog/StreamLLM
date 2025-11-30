# StreamLLM

[![](https://jitpack.io/v/locked-fog/StreamLLM.svg)](https://jitpack.io/#locked-fog/StreamLLM)
[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](https://opensource.org/licenses/MIT)

**StreamLLM** 是一个专为 Kotlin (JVM/Android) 开发者设计的轻量级 LLM 工作流编排库。它基于协程和 DSL，提供了一套极具表现力的接口，让你像写脚本一样管理 AI 对话、记忆和流式响应。

✨ **核心特性：**
* 🌊 **Native Kotlin DSL**: 像写普通代码一样编排 Prompt。
* 🧠 **Advanced Memory Management**: 内置全局记忆管理，支持多记忆体切换、窗口控制和读写策略。
* 🛠 **Type-Safe Extraction**: 自动将非结构化文本转换为 Kotlin 强类型对象（支持自动纠错重试）。
* 🚀 **Streaming First**: 协程驱动的流式输出，告别回调地狱。
* 🔌 **Universal Provider**: 完美适配 SiliconFlow、DeepSeek、OpenAI 及任何兼容 OpenAI 接口的模型。
* 📝 **Custom Formatting**: 支持自定义历史记录的序列化格式，灵活适配各种 Prompt Engineering 需求。

## 📦 安装 (Installation)

Step 1. 在根目录的 `settings.gradle.kts` 中添加 JitPack 仓库：

```kotlin
dependencyResolutionManagement {
    repositories {
        mavenCentral()
        maven("[https://jitpack.io](https://jitpack.io)")
    }
}
````

Step 2. 在模块级 `build.gradle.kts` 中添加依赖：

```kotlin
dependencies {
    implementation("com.github.LockedFog:StreamLLM:v0.2.0") // 请使用最新版本
}
```

## 🚀 快速开始 (Quick Start)

### 1\. 初始化 (Initialization)

推荐在 Application 启动时配置。

```kotlin
import dev.lockedfog.streamllm.StreamLLM

StreamLLM.init(
    apiKey = "sk-your-api-key",
    baseUrl = "[https://api.siliconflow.cn/v1](https://api.siliconflow.cn/v1)", // 支持所有 OpenAI 兼容接口
    modelName = "deepseek-ai/DeepSeek-V3",
    timeoutSeconds = 60
)
```

### 2\. 基础对话 (Basic Chat)

```kotlin
import dev.lockedfog.streamllm.dsl.stream

stream {
    // 简单的同步问答 (自动管理记忆)
    val answer = "你好，我是个程序员".ask() 
    println(answer)

    // 基于上下文追问
    "我刚才说了什么职业？".ask().also { println(it) }
}
```

### 3\. 记忆管理 (Memory Management) 🔥

StreamLLM 提供了强大的记忆控制能力。

```kotlin
import dev.lockedfog.streamllm.core.MemoryStrategy

stream {
    // 1. 切换/创建新的记忆体 (例如：为不同用户或不同任务)
    newMemory("coding_assistant", system = "你是一个严谨的代码专家")
    
    // 2. 使用策略控制记忆 (MemoryStrategy)
    // ReadWrite (默认): 读历史 + 写历史
    // ReadOnly: 读历史 + 不写本次 (适合基于历史的总结任务)
    // WriteOnly: 不读历史 + 写本次 (适合开启新话题)
    // Stateless: 不读 + 不写
    
    "总结一下之前的对话".ask(
        strategy = MemoryStrategy.ReadOnly, // 不让"总结"这个请求污染历史
        historyWindow = 10 // 只读取最近 10 条历史
    )

    // 3. 临时覆盖 System Prompt
    "把这句话翻译成英文".ask(
        system = "你是一个翻译官，只输出翻译结果，不要废话", // 临时覆盖人设，不影响记忆体
        strategy = MemoryStrategy.Stateless
    )
}
```

### 4\. 自定义格式化 (Custom Formatting)

当你想手动控制历史记录在 Prompt 中的位置和格式时：

```kotlin
stream {
    val template = """
        这里是相关背景资料...
        
        === 对话历史 ===
        {{history}}
        ===============
        
        请根据以上历史回答：{{it}}
    """.trimIndent()

    // 自定义历史格式: role=template; sep=separator
    // 支持 user, assistant, system 角色
    val myFormat = "user=Q: {{content}}; assistant=A: {{content}}; sep=\n\n"

    "我的问题".ask(
        promptTemplate = template,
        formatter = myFormat // 将会把历史渲染为 Q: ... A: ... 的格式并填入 {{history}}
    )
}
```

### 5\. 结构化提取 (Structured Output)

将自然语言转化为强类型对象，包含自动 JSON 修复机制。

```kotlin
@Serializable
data class UserIntent(val action: String, val target: String)

stream {
    // 自动提取 + 类型转换 + 错误重试
    val intent = "把空调调到24度".ask<UserIntent>(
        promptTemplate = "提取意图，返回 JSON。"
    )
    println("Action: ${intent.action}, Target: ${intent.target}")
}
```

## 🛠 参数详解

| 参数 | 说明 | 默认值 |
| --- | --- | --- |
| `strategy` | 记忆策略 (ReadWrite, ReadOnly, WriteOnly, Stateless) | ReadWrite |
| `historyWindow` | 历史窗口大小 (-1=全部, 0=无, N=最近N条) | -1 |
| `system` | 临时 System Prompt (覆盖记忆体设定) | null |
| `formatter` | 历史序列化格式字符串 | null |
| `temperature` | 随机性 (0.0 - 2.0) | 全局默认 |
| `model` | 临时覆盖模型名称 | 全局默认 |

## License

[MIT](https://www.google.com/search?q=LICENSE) © 2025 Locked\_Fog
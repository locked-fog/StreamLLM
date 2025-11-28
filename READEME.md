# StreamLLM

[![](https://jitpack.io/v/LockedFog/StreamLLM.svg)](https://jitpack.io/#LockedFog/StreamLLM)
[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](https://opensource.org/licenses/MIT)

**StreamLLM** 是一个专为 Kotlin (JVM/Android) 开发者设计的轻量级 LLM 工作流编排库。它基于 LangChain4j，提供了一套极具表现力的 DSL，让你像写脚本一样编写 AI 逻辑。

✨ **核心特性：**
* 🌊 **Native Kotlin DSL**: 像写普通代码一样编排 Prompt。
* 🛠 **Type-Safe Extraction**: 自动将非结构化文本转换为 Kotlin 强类型对象（支持自动纠错重试）。
* 🚀 **Streaming First**: 内置流式输出支持（打字机效果），且不通过 Callback 地狱。
* 🔌 **SiliconCloud / DeepSeek Ready**: 完美适配 SiliconFlow、DeepSeek、OpenAI 及兼容接口。
* 📱 **Android Friendly**: 纯 Kotlin 实现，协程驱动，天然适配 Android 开发。

## 📦 安装 (Installation)

Step 1. 在根目录的 `settings.gradle.kts` (或项目级 build.gradle) 中添加 JitPack 仓库：

```kotlin
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        mavenCentral()
        maven("[https://jitpack.io](https://jitpack.io)") // <--- 添加这行
    }
}
````

Step 2. 在模块级 `build.gradle.kts` 中添加依赖：

```kotlin
dependencies {
    implementation("com.github.LockedFog:StreamLLM:Tag") // 将 Tag 替换为最新版本号 (例如 v0.1.0)
}
```

## 🚀 快速开始 (Quick Start)

### 1\. 初始化 (Initialization)

推荐在 Application 启动时进行配置。

```kotlin
import dev.lockedfog.streamllm.StreamLLM

StreamLLM.init(
    apiKey = "sk-your-api-key",
    baseUrl = "[https://api.siliconflow.cn/v1](https://api.siliconflow.cn/v1)", // 支持 SiliconCloud / DeepSeek / OpenAI
    modelName = "deepseek-ai/DeepSeek-V3"
)
```

### 2\. 基础对话 (Basic Chat)

```kotlin
import dev.lockedfog.streamllm.dsl.stream

stream {
    // 简单的同步问答
    val answer = "用一句话解释量子纠缠".ask()
    println(answer)

    // 带参数的控制 (动态调整温度)
    "写一个极其疯狂的科幻故事开头".ask(
        temperature = 1.2,
        model = "deepseek-ai/DeepSeek-R1" // 临时切换模型
    ).also { println(it) }
}
```

### 3\. 流式输出 (Streaming)

在 Android 或桌面应用中实现“打字机”效果：

```kotlin
stream {
    "你好，请做个自我介绍".stream { token ->
        // 这里的代码会在 IO 线程执行，更新 UI 时请注意切换线程 (Android)
        print(token) 
    }
}
```

### 4\. 结构化提取 (Structured Output)

最强大的功能：将自然语言转化为强类型对象。包含自动 JSON 修复机制。

```kotlin
@Serializable
data class UserIntent(
    val action: String,
    val target: String,
    val confidence: Double
)

stream {
    val text = "帮我把客厅的空调温度调到24度"
    
    // 自动提取 + 类型转换 + 错误重试
    val intent = text.ask<UserIntent>(
        promptTemplate = "Extract user intent to JSON."
    )
    
    if (intent.confidence > 0.8) {
        println("执行操作: ${intent.action} -> ${intent.target}")
    }
}
```

## 🛠 配置项 (Configuration)

你可以针对每一句话独立调整参数：

| 参数 | 说明 | 默认值 |
| --- | --- | --- |
| `temperature` | 随机性 (0.0 - 2.0) | 全局默认 |
| `topP` | 核采样概率 | 全局默认 |
| `maxTokens` | 最大输出长度 | 无限制 |
| `model` | 临时覆盖模型名称 | 全局默认 |
| `stop` | 停止词列表 | null |

```kotlin
"100个草莓吃掉20个".ask(
    temperature = 0.1, // 严谨模式
    stop = listOf("解释：") // 遇到 "解释：" 就停止生成
)
```

## License

[MIT](https://www.google.com/search?q=LICENSE) © 2025 Locked_Fog

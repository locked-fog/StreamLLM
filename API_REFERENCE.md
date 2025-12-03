# StreamLLM API 参考手册

本文档详细描述了 StreamLLM 库的公共 API 接口、配置选项及 DSL 方法。

---

## 1. 全局入口 (Global Entry Points)

### `object StreamLLM`
库的单例入口，负责全局配置和资源管理。

| 方法 | 描述 |
| --- | --- |
| `init(baseUrl, apiKey, modelName, ...)` | 初始化库，使用内置的 OpenAI 客户端。 |
| `init(provider)` | 使用自定义的 `LlmProvider` 实例初始化。 |
| `close()` | 释放资源（关闭 HTTP 连接池等）。 |
| `memory` | 访问全局的 `MemoryManager` 实例。 |
| `json` | 全局共享的 `Json` 实例（配置为宽松模式）。 |

### `suspend fun stream(block: suspend StreamScope.() -> Unit)`
**核心 DSL 入口**。创建一个 `StreamScope` 作用域并执行挂起代码块。所有对话逻辑必须在此块内进行。

---

## 2. 核心 DSL (`StreamScope`)

在 `stream { ... }` 内部可用的方法。

### 基础请求
#### `suspend fun String.ask(...)`
发送请求并等待完整响应（同步返回）。

**参数**:
* `promptTemplate`: 提示词模版 (支持 `{{it}}`, `{{history}}`)。
* `strategy`: 记忆策略 (`MemoryStrategy`)。
* `historyWindow`: 历史上下文窗口大小 (-1=全部, 0=无, N=最近N条)。
* `system`: 临时 System Prompt。
* `options`: 生成选项 (`GenerationOptions`)。
* `onToken`: **[Suspend]** 流式回调。如果提供此参数，请求将变为流式模式。

#### `suspend fun String.stream(...)`
`ask` 的流式快捷别名。

**用法**:
```kotlin
"Question".stream { token -> 
    // token 可能是单个字符或文本块
    updateUI(token)
}
````

### 结构化数据

#### `suspend fun <T> String.ask(...)`

请求 LLM 返回 JSON 并自动反序列化为对象 `T`。

**特性**:

* 自动清洗 Markdown 代码块。
* **自动纠错**: 如果 JSON 解析失败，会自动将错误信息反馈给 LLM 重试（默认最多 3 次）。

### 手动转换

#### `suspend fun <T> String.to()`

将当前字符串视为 JSON 并尝试反序列化为 `T`。

### 记忆辅助函数

| 方法 | 描述 |
| --- | --- |
| `newMemory(name, system)` | 创建并切换到新的记忆体。 |
| `switchMemory(name)` | 切换到已存在的记忆体。 |
| `deleteMemory(name)` | 删除指定记忆体。 |
| `clearMemory()` | 清空**当前**记忆体的对话历史（保留 System Prompt）。 |
| `setSystemPrompt(name, prompt)` | 更新指定记忆体的 System Prompt。 |

-----

## 3\. 配置与模型 (Models)

### `enum class MemoryStrategy`

控制单次请求如何与记忆系统交互。

| 策略 | 描述 | 适用场景 |
| --- | --- | --- |
| `ReadWrite` | 读取历史，写入新对话。 | 正常聊天 (默认) |
| `ReadOnly` | 读取历史，**不**写入新对话。 | 基于历史的总结、分析 |
| `WriteOnly` | **不**读取历史，但写入新对话。 | 开启新话题但需存档 |
| `Stateless` | 不读也不写。 | 一次性翻译、润色 |

### `data class GenerationOptions`

生成控制参数。

* `temperature`: 随机性 (0.0 - 2.0)。
* `topP`: 核采样概率。
* `maxTokens`: 最大生成长度。
* `stopSequences`: 停止词列表。
* `modelNameOverride`: 单次请求覆盖默认模型。

### `data class Usage`

Token 用量统计。

* `promptTokens`: 提问消耗。
* `completionTokens`: 回答消耗。
* `totalTokens`: 总计。

-----

## 4\. 扩展接口 (Provider)

如果您需要适配非 OpenAI 标准的模型（如 Gemini 原生 API, Claude SDK），可实现此接口。

### `interface LlmProvider : AutoCloseable`

#### `suspend fun chat(messages, options): LlmResponse`

发送一次性请求。

#### `fun stream(messages, options): Flow<LlmResponse>`

发送流式请求，返回 Kotlin冷流。

* 实现者应确保 `Flow` 的发射不被阻塞。
* 推荐使用 Ktor SSE 或类似机制。

-----

## 5\. 工具类 (Utils)

### `object JsonSanitizer`

* `sanitize(input: String): String`
    * 移除 `<think>...</think>` 标签。
    * 提取 Markdown code block (` json ...  `) 中的内容。
    * 寻找首尾 `{}`。

### `interface HistoryFormatter`

自定义将 `List<ChatMessage>` 转换为 String 的格式。

* 默认实现：`User: ... \n Assistant: ...`
* 支持通过字符串 DSL 创建：`"user=Q:{{content}};assistant=A:{{content}};sep=\n"`

<!-- end list -->
# StreamLLM API 参考手册

本文档详细描述了 StreamLLM 库的公共 API 接口、配置选项及 DSL 方法。

---

## 1. 全局入口 (Global Entry Points)

### `object StreamLLM`
库的单例入口，负责全局配置和资源管理。

| 方法 | 描述 |
| --- | --- |
| `init(baseUrl, apiKey, modelName, ...)` | 初始化库，使用内置的 OpenAI 客户端。 |
| `init(provider, storage, maxMemoryCount)` | **[Enhanced]** 使用自定义 Provider、持久化存储及缓存大小初始化。 |
| `close()` | 释放资源（关闭 HTTP 连接池、等待持久化任务完成等）。 |
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

### 结构化数据
#### `suspend fun <T> String.ask(...)`
请求 LLM 返回 JSON 并自动反序列化为对象 `T`。支持自动纠错重试。

### 记忆辅助函数 (Memory Helpers)

所有记忆操作均为 **suspend** 函数，以支持异步加载和持久化。

| 方法 | 描述 |
| --- | --- |
| `preLoad(name)` | **[New]** 异步预加载记忆体到缓存。建议在会话切换前调用。 |
| `newMemory(name, system)` | 创建并切换到新的记忆体。 |
| `switchMemory(name)` | 切换到已存在的记忆体（若不在缓存中会自动加载）。 |
| `deleteMemory(name)` | 删除指定记忆体（同时删除缓存和存储）。 |
| `clearMemory()` | 清空**当前**记忆体的对话历史（保留 System Prompt）。 |
| `setSystemPrompt(name, prompt)` | 更新指定记忆体的 System Prompt。 |

-----

## 3. 配置与模型 (Models)

### `enum class MemoryStrategy`
控制单次请求如何与记忆系统交互（`ReadWrite`, `ReadOnly`, `WriteOnly`, `Stateless`）。

### `data class GenerationOptions`
生成控制参数（Temperature, TopP, MaxTokens 等）。

-----

## 4. 扩展接口 (Provider)

### `interface LlmProvider : AutoCloseable`
* `suspend fun chat(...)`: 一次性请求。
* `fun stream(...)`: 流式请求，返回 `Flow<LlmResponse>`。

-----

## 5. 工具类 (Utils)

* `object JsonSanitizer`: 清洗 `<think>` 标签和 Markdown 代码块。
* `interface HistoryFormatter`: 自定义历史记录格式化。

-----

## 6. 持久化 (Persistence)

**StreamLLM** 支持通过实现 `MemoryStorage` 接口接入任意持久化层（如 Room, SQLDelight, DataStore）。

### `interface MemoryStorage`

所有方法均为 **suspend**。

| 方法 | 描述 |
| --- | --- |
| `getSystemPrompt(id): String?` | 获取 System Prompt。 |
| `setSystemPrompt(id, prompt)` | 更新 System Prompt。 |
| `getMessages(id, limit): List<ChatMessage>` | 获取消息历史。 |
| `addMessage(id, message)` | 追加单条消息（增量写入）。 |
| `saveFullContext(id, sys, msgs)` | 保存完整上下文（用于缓存驱逐时的全量同步）。 |
| `clearMessages(id)` | 清空消息。 |
| `deleteMemory(id)` | 删除记忆体数据。 |

**默认实现**: `InMemoryStorage` (仅内存，无持久化)。
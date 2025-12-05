# StreamLLM API 参考手册 (v0.3.5+)

本文档提供 StreamLLM 库的详细 API 规范。文档基于 **v0.3.5** 及以上版本编写，涵盖了多模态交互、工具调用及新的实例模式架构。

> **写给 Java/C++ 开发者的提示**：
> * **Suspend**: 标记为 `suspend` 的方法是**挂起函数**（类似 C++ 的 `co_await` 或 Java 的 `CompletableFuture` 链），必须在协程作用域（Coroutine Scope）中调用。它们是非阻塞的。
> * **Sealed Interface**: 密封接口，类似于 C++ 的 `std::variant` 或 Java 17+ 的 `sealed` 接口。表示一组受限的子类型，通常用于表示多态数据结构（如：消息内容既不仅是文本，也可以是图片列表）。
> * **DSL**: 领域特定语言，这里指通过 Lambda 表达式（`{ ... }`）构建的上下文代码块，类似于一种声明式的 Builder 模式。

## 目录

- [1. 客户端入口 (StreamClient)](#1-客户端入口-streamclient)
  - [构造函数](#构造函数)
  - [核心方法](#核心方法)
    - [`stream`](#stream-dsl-入口)
  - [属性](#属性)
- [2. 流式 DSL (StreamScope)](#2-流式-dsl-streamscope)
  - [核心方法 (Request)](#核心方法-request)
    - [`ask` / `stream` (纯文本)](#ask--stream-纯文本)
    - [`ask` / `stream` (多模态)](#ask--stream-多模态)
    - [`ask<T>` (结构化输出)](#askt-structured-output)
- [3. 数据模型 (Data Models)](#3-数据模型-data-models)
  - [`ChatMessage` (消息体)](#chatmessage-消息体)
  - [`ChatContent` (多模态内容)](#chatcontent-多模态内容)
  - [`LlmResponse` (响应体)](#llmresponse-响应体)
  - [`ToolCall` (工具调用)](#toolcall-工具调用)
- [4. 记忆管理器 (MemoryManager)](#4-记忆管理器-memorymanager)
  - [核心方法](#核心方法-1)
- [5. 持久化接口 (MemoryStorage)](#5-持久化接口-memorystorage)
- [6. 提供者接口 (LlmProvider)](#6-提供者接口-llmprovider)

---

## 1. 客户端入口 (StreamClient)

`class dev.lockedfog.streamllm.StreamClient`

**[v0.3.5 New]** 取代了旧版本的 `StreamLLM` 单例。
`StreamClient` 是一个**实例对象**，负责持有网络连接 (`Provider`) 和记忆状态 (`Memory`)。你可以在应用中创建多个 `StreamClient` 实例来连接不同的模型服务。

### 构造函数

```kotlin
fun StreamClient(
    provider: LlmProvider,
    storage: MemoryStorage = InMemoryStorage(),
    maxMemoryCount: Int = 10
)
````

* **参数**:
    * `provider` ([LlmProvider](#6-提供者接口-llmprovider)): LLM 服务提供者实例（如 `OpenAiClient`）。
    * `storage` ([MemoryStorage](#5-持久化接口-memorystorage)): 持久化存储实现。默认为纯内存存储 (`InMemoryStorage`)。
    * `maxMemoryCount` (Int): LRU 缓存的最大会话数。默认 `10`。超过此数量的会话将被从内存清除（但保留在 Storage 中）。

### 核心方法

#### `stream` (DSL 入口)

* **声明**: `suspend fun stream(block: suspend StreamScope.() -> Unit)`
* **描述**: 创建一个对话上下文 (`StreamScope`) 并执行业务逻辑。这是与 LLM 交互的唯一入口。
* **参数**:
    * `block`: 在 `StreamScope` 上下文中执行的逻辑代码块。

### 属性

#### `memory`

* **声明**: `val memory: MemoryManager`
* **描述**: 该客户端绑定的记忆管理器实例。
* **参见**: [MemoryManager](#4-记忆管理器-memorymanager)

-----

## 2\. 流式 DSL (StreamScope)

`class dev.lockedfog.streamllm.core.StreamScope`

`stream { ... }` 代码块内部的上下文对象 (`this`)。

### 核心方法 (Request)

#### `ask` / `stream` (纯文本)

发送**纯文本**请求。

* **声明**:
  ```kotlin
  // 同步等待完整响应
  suspend fun String.ask(
      promptTemplate: String = "",
      strategy: MemoryStrategy = MemoryStrategy.ReadWrite,
      historyWindow: Int = -1,
      system: String? = null,
      formatter: String? = null,
      options: GenerationOptions? = null
  ): String

  // 流式接收响应
  suspend fun String.stream(
      ..., // 参数同上
      onToken: suspend (String) -> Unit
  ): String
  ```
* **参数**:
    * `this` (String): 用户输入的 Prompt。
    * `strategy`: 记忆读写策略（默认 `ReadWrite`）。
    * `historyWindow`: 携带历史消息数量（`-1`=全部）。
    * `system`: 临时 System Prompt（本次覆盖）。
    * `onToken`: **[Callback]** 接收流式 Token 的回调函数。

#### `ask` / `stream` (多模态)

**[v0.3.5 New]** 发送**多模态**请求（图片、音频、视频）。

* **声明**:
  ```kotlin
  suspend fun ChatContent.ask(...): String
  suspend fun ChatContent.stream(..., onToken: suspend (String) -> Unit): String
  ```
* **参数**:
    * `this` ([ChatContent](#chatcontent-多模态内容)): 构造好的多模态内容对象。
    * 其他参数与纯文本版本一致。

#### `ask<T>` (Structured Output)

请求 LLM 返回 JSON 并自动反序列化为对象 `T`。

* **声明**:
  ```kotlin
  suspend inline fun <reified T> String.ask(..., maxRetries: Int = 3): T
  ```
* **描述**: 内置自动纠错机制。如果 LLM 返回的 JSON 格式错误，库会将错误信息回传给 LLM 进行自我修正 (Self-Correction)，最多重试 `maxRetries` 次。

-----

## 3\. 数据模型 (Data Models)

### `ChatMessage` (消息体)

表示对话历史中的一条记录。

| 字段 | 类型 | 描述 |
| :--- | :--- | :--- |
| `role` | `ChatRole` | 角色: `USER`, `ASSISTANT`, `SYSTEM`, `TOOL` (New) |
| `content` | `ChatContent` | **[New]** 消息内容，支持多模态。 |
| `name` | `String?` | 发送者名称（通常用于工具调用的函数名）。 |
| `toolCalls` | `List<ToolCall>?` | **[New]** Assistant 请求调用的工具列表。 |
| `toolCallId` | `String?` | **[New]** Tool 角色回复时，关联的调用 ID。 |

### `ChatContent` (多模态内容)

**[v0.3.5 New]** 这是一个 **Sealed Interface** (多态接口)，用于适配 OpenAI/SiliconFlow 的 `content` 字段。

它有两个实现类：

1.  **`ChatContent.Text`**

    * **描述**: 纯文本消息。
    * **字段**: `val text: String`
    * **JSON**: `"content": "hello"`

2.  **`ChatContent.Parts`**

    * **描述**: 多模态片段列表。
    * **字段**: `val parts: List<ContentPart>`
    * **JSON**: `"content": [{"type": "text", ...}, {"type": "image_url", ...}]`

#### `ContentPart` (内容片段)

多模态列表中的具体元素，也是一个 Sealed Interface：

* **`ContentPart.TextPart(text: String)`**: 文本片段。
* **`ContentPart.ImagePart(imageUrl: ImageUrl)`**: 图片片段。
* **`ContentPart.AudioPart(audioUrl: MediaUrl)`**: 音频片段 (Qwen-Omni)。
* **`ContentPart.VideoPart(videoUrl: VideoDetailUrl)`**: 视频片段 (Qwen-VL)。

-----

### `LlmResponse` (响应体)

LLM 返回的完整响应数据。

| 字段 | 类型 | 描述 |
| :--- | :--- | :--- |
| `content` | `String` | 生成的文本内容。 |
| `reasoningContent` | `String?` | **[New]** 思维链内容 (DeepSeek R1 / SiliconFlow)。 |
| `toolCalls` | `List<ToolCall>?` | **[New]** 模型发起的工具调用请求。 |
| `usage` | `Usage?` | Token 消耗统计。 |

### `ToolCall` (工具调用)

**[v0.3.5 New]** 描述模型对外部工具的调用请求。

| 字段 | 类型 | 描述 |
| :--- | :--- | :--- |
| `id` | `String` | 调用 ID (如 `call_abc123`)。 |
| `type` | `String` | 类型 (通常为 `function`)。 |
| `function` | `FunctionCall` | 包含 `name` (函数名) 和 `arguments` (JSON 参数字符串)。 |

-----

## 4\. 记忆管理器 (MemoryManager)

`class dev.lockedfog.streamllm.core.MemoryManager`

通过 `StreamClient.memory` 访问。管理会话生命周期。

### 核心方法

#### `preLoad`

* **声明**: `suspend fun preLoad(memoryId: String)`
* **描述**: 异步预加载指定 ID 的会话历史到内存缓存。建议在 UI 跳转前调用以消除加载延迟。

#### `switchMemory`

* **声明**: `suspend fun switchMemory(name: String)`
* **描述**: 切换当前活跃的会话上下文。如果缓存未命中，会自动从存储层加载。

#### `addMessage`

* **声明**: `suspend fun addMessage(role: ChatRole, content: String)`
* **描述**: 手动向当前会话添加一条消息。支持 Write-Through (写内存同时异步写库)。

#### `getCurrentHistory`

* **声明**: `suspend fun getCurrentHistory(windowSize: Int = -1): List<ChatMessage>`
* **描述**: 获取当前会话的历史记录列表。

-----

## 5\. 持久化接口 (MemoryStorage)

`interface dev.lockedfog.streamllm.core.memory.MemoryStorage`

如果你需要将对话保存到数据库（如 Room, SQLite），请实现此接口。

* `getSystemPrompt(id)` / `setSystemPrompt(id, prompt)`
* `getMessages(id, limit)` / `addMessage(id, message)`
* `saveFullContext(id, system, messages)`: 全量保存（缓存驱逐时触发）。
* `deleteMemory(id)`

**内置实现**: `InMemoryStorage` (仅保存在内存中，重启丢失)。

-----

## 6\. 提供者接口 (LlmProvider)

`interface dev.lockedfog.streamllm.provider.LlmProvider`

适配不同 LLM 厂商的统一接口。

* **`OpenAiClient`**: 适用于 OpenAI, DeepSeek, SiliconFlow, Moonshot 等兼容 OpenAI 协议的厂商。

### 方法

* `suspend fun chat(...)`: 发送非流式请求。
* `fun stream(...)`: 发送流式请求，返回 `Flow<LlmResponse>`。

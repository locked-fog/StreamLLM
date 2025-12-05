# StreamLLM API 参考手册 (v0.4.0+)

本文档提供 StreamLLM 库的详细 API 规范。文档基于 **v0.4.0** 版本编写，涵盖了**真正的工具调用 (Re-Act Loop)**、**原生多模态交互**、**自适应批处理**及**混合记忆管理**。

> **写给 Java/C++ 开发者的提示**：
> * **Suspend**: 标记为 `suspend` 的方法是**挂起函数**（类似 C++ 的 `co_await` 或 Java 的 `CompletableFuture` 链），必须在协程作用域（Coroutine Scope）中调用。它们是非阻塞的。
> * **Sealed Interface**: 密封接口，类似于 C++ 的 `std::variant` 或 Java 17+ 的 `sealed` 接口。表示一组受限的子类型，通常用于表示多态数据结构。
> * **DSL**: 领域特定语言，这里指通过 Lambda 表达式（`{ ... }`）构建的上下文代码块，类似于一种声明式的 Builder 模式。

## 目录

- [1. 客户端入口 (StreamClient)](#1-客户端入口-streamclient)
  - [构造函数](#构造函数)
  - [核心方法](#核心方法)
    - [`stream`](#stream-dsl-入口)
  - [属性](#属性)
- [2. 流式 DSL (StreamScope)](#2-流式-dsl-streamscope)
  - [核心方法 (Tool Calling)](#核心方法-tool-calling-v040-new)
  - [核心方法 (Request)](#核心方法-request)
    - [`ask` / `stream` (纯文本)](#ask--stream-纯文本)
    - [`ask` / `stream` (多模态)](#ask--stream-多模态)
    - [`ask<T>` (结构化输出)](#askt-structured-output)
- [3. 数据模型 (Data Models)](#3-数据模型-data-models)
  - [`ChatMessage` (消息体)](#chatmessage-消息体)
  - [`ChatContent` (多模态内容)](#chatcontent-多模态内容)
  - [`Tool` & `FunctionDefinition` (工具定义)](#tool--functiondefinition-)
  - [`GenerationOptions` (生成选项)](#generationoptions-生成选项)
  - [`LlmResponse` (响应体)](#llmresponse-响应体)
- [4. 记忆管理器 (MemoryManager)](#4-记忆管理器-memorymanager)
  - [核心方法](#核心方法-1)
- [5. 持久化接口 (MemoryStorage)](#5-持久化接口-memorystorage)
- [6. 提供者接口 (LlmProvider)](#6-提供者接口-llmprovider)

---

## 1. 客户端入口 (StreamClient)

`class dev.lockedfog.streamllm.StreamClient`

**[v0.3.5+]** 取代了旧版本的 `StreamLLM` 单例。
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
    * `maxMemoryCount` (Int): LRU 缓存的最大会话数。默认 `10`。

### 核心方法

#### `stream` (DSL 入口)

* **声明**: `suspend fun stream(maxToolRounds: Int = 5, block: suspend StreamScope.() -> Unit)`
* **描述**: 创建一个对话上下文 (`StreamScope`) 并执行业务逻辑。
* **参数**:
    * `maxToolRounds`: **[v0.4.0 New]** 最大工具调用递归轮数。默认为 5。防止模型陷入无限调用工具的死循环。
    * `block`: 在 `StreamScope` 上下文中执行的逻辑代码块。

### 属性

#### `memory`

* **声明**: `val memory: MemoryManager`
* **描述**: 该客户端绑定的记忆管理器实例。

-----

## 2\. 流式 DSL (StreamScope)

`class dev.lockedfog.streamllm.core.StreamScope`

`stream { ... }` 代码块内部的上下文对象 (`this`)。v0.4.0 引入了**自动执行工具**的能力。

### 核心方法 (Tool Calling) **[v0.4.0 New]**

#### `registerTool`

注册一个 Kotlin 函数作为工具，供模型在对话中调用。

* **声明**:
  ```kotlin
  fun registerTool(
      name: String,
      description: String? = null,
      parametersJson: String = "{}",
      executor: suspend (String) -> String
  )
  ```
* **参数**:
    * `name`: 工具名称 (只能包含字母、数字、下划线，如 `"get_weather"`)。
    * `description`: 工具描述，告诉模型何时使用该工具。
    * `parametersJson`: **JSON Schema** 字符串，描述参数结构。建议使用 Kotlinx.Serialization 生成或手动编写标准 Schema。
    * `executor`: **Suspend Lambda**，接收 JSON 参数字符串，返回执行结果字符串。
* **行为**: 注册后，该工具会自动注入到后续的 `ask`/`stream` 请求中。当模型调用工具时，库会自动执行 `executor` 并将结果回传给模型。

### 核心方法 (Request)

#### `ask` / `stream` (纯文本)

发送**纯文本**请求。支持**自动 Re-Act 循环**（Chat -\> Tool -\> Execution -\> Chat）。

* **声明**:
  ```kotlin
  // 同步等待完整响应 (自动处理工具调用循环)
  suspend fun String.ask(
      promptTemplate: String = "",
      strategy: MemoryStrategy = MemoryStrategy.ReadWrite,
      historyWindow: Int = -1,
      system: String? = null,
      formatter: String? = null,
      options: GenerationOptions? = null
  ): String

  // 流式接收响应 (自动处理工具调用循环与参数聚合)
  suspend fun String.stream(
      ..., // 参数同上
      onToken: suspend (String) -> Unit
  ): String
  ```
* **参数**:
    * `this` (String): 用户输入的 Prompt。
    * `strategy`: 记忆读写策略（默认 `ReadWrite`）。
    * `options`: 生成选项（包含工具配置）。
    * `onToken`: **[Callback]** 接收流式 Token 的回调。**注意**：在工具调用执行期间，此回调会暂停，直到模型生成最终的文本回复。

#### `ask` / `stream` (多模态)

发送**多模态**请求（图片、音频、视频）。

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
* **描述**: 内置自动纠错机制 (Self-Correction)。

-----

## 3\. 数据模型 (Data Models)

### `ChatMessage` (消息体)

表示对话历史中的一条记录。

| 字段 | 类型 | 描述 |
| :--- | :--- | :--- |
| `role` | `ChatRole` | 角色: `USER`, `ASSISTANT`, `SYSTEM`, `TOOL`。 |
| `content` | `ChatContent?` | **[Update]** 消息内容。当 `toolCalls` 存在时可能为 `null`。 |
| `name` | `String?` | 发送者名称。 |
| `toolCalls` | `List<ToolCall>?` | Assistant 请求调用的工具列表。 |
| `toolCallId` | `String?` | Tool 角色回复时，关联的调用 ID。 |

### `ChatContent` (多模态内容)

**Sealed Interface**，适配 OpenAI/SiliconFlow `content` 字段。

1.  **`ChatContent.Text`**: 纯文本 (`val text: String`)。
2.  **`ChatContent.Parts`**: 多模态片段列表 (`val parts: List<ContentPart>`)。

#### `ContentPart` (内容片段)

* **`TextPart(text)`**: 文本。
* **`ImagePart(imageUrl)`**: 图片。
* **`AudioPart(audioUrl)`**: 音频。
* **`VideoPart(videoUrl)`**: 视频。支持 `max_frams` (注意拼写适配 SiliconFlow) 和 `fps`。

### `Tool` & `FunctionDefinition` 

用于描述发送给模型的工具结构。

* **`Tool`**:
    * `type`: 固定为 `"function"`。
    * `function`: [FunctionDefinition](#tool--functiondefinition-)。
* **`FunctionDefinition`**:
    * `name`: 函数名。
    * `description`: 功能描述。
    * `parameters`: `JsonElement` (JSON Schema 对象)。

### `GenerationOptions` (生成选项)

控制生成行为的参数集。

| 字段 | 类型 | 描述 |
| :--- | :--- | :--- |
| `temperature` | `Double?` | 随机性 (0.0 - 2.0)。 |
| `maxTokens` | `Int?` | 最大生成长度。 |
| `tools` | `List<Tool>?` | **[New]** 本次请求可用的工具列表（通常由 `registerTool` 自动填充）。 |
| `toolChoice` | `JsonElement?` | **[New]** 工具选择策略 (`auto`, `none`, `required` 等)。 |

### `LlmResponse` (响应体)

LLM 返回的完整响应数据。

| 字段 | 类型 | 描述 |
| :--- | :--- | :--- |
| `content` | `String` | 生成的文本内容。 |
| `reasoningContent` | `String?` | 思维链内容 (DeepSeek R1)。 |
| `toolCalls` | `List<ToolCall>?` | 模型发起的工具调用请求。 |
| `usage` | `Usage?` | Token 消耗统计。 |

-----

## 4\. 记忆管理器 (MemoryManager)

`class dev.lockedfog.streamllm.core.MemoryManager`

通过 `StreamClient.memory` 访问。

### 核心方法

#### `addMessage` (重载) **[v0.4.0 Update]**

* **声明 1**: `suspend fun addMessage(role: ChatRole, content: String)`
* **声明 2**:
  ```kotlin
  suspend fun addMessage(
      role: ChatRole, 
      content: ChatContent, 
      toolCalls: List<ToolCall>? = null, 
      toolCallId: String? = null,
      name: String? = null
  )
  ```
* **描述**: 手动向当前会话添加一条消息。支持存储完整的多模态内容和工具调用信息。

#### `switchMemory` / `preLoad` / `getCurrentHistory`

(同旧版本，用于管理会话切换和历史获取)

-----

## 5\. 持久化接口 (MemoryStorage)

`interface dev.lockedfog.streamllm.core.memory.MemoryStorage`

如果你需要将对话保存到数据库，请实现此接口。v0.4.0 建议确保存储层能序列化 `ChatContent` 和 `ToolCall` 等复杂对象。

* `getMessages(id, limit)` / `addMessage(id, message)`
* `saveFullContext(id, system, messages)`
* ...

-----

## 6\. 提供者接口 (LlmProvider)

`interface dev.lockedfog.streamllm.provider.LlmProvider`

* **`OpenAiClient`**:
    * **[Update]**: 现已完整支持 `tools` 和 `tool_choice` 参数透传。
    * **[Update]**: 优化了 SSE 解析器，支持宽容模式 (`explicitNulls = false`) 以适配更多厂商。

### 方法

* `suspend fun chat(...)`: 发送非流式请求。
* `fun stream(...)`: 发送流式请求，返回 `Flow<LlmResponse>`。

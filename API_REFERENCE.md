# StreamLLM API 参考手册

本文档提供 StreamLLM 库的详细 API 规范。所有公共类、方法及接口均已列出。

## 目录

- [1\. 全局入口 (StreamLLM)](#1-全局入口-streamllm)
  - [属性](#属性)
  - [方法](#方法)
    - [`init` (OpenAI 兼容模式)](#init-openai-兼容模式)
    - [`init` (自定义 Provider 模式)](#init-自定义-provider-模式)
- [2\. 流式 DSL (StreamScope)](#2-流式-dsl-streamscope)
  - [入口函数](#入口函数)
  - [核心方法 (Request)](#核心方法-request)
    - [`ask` (String Extension)](#ask-string-extension)
    - [`stream` (String Extension)](#stream-string-extension)
    - [`ask<T>` (Structured Output)](#askt-structured-output)
    - [`to<T>` (String Extension)](#tot-string-extension)
  - [辅助方法 (Helpers)](#辅助方法-helpers)
- [3\. 记忆管理器 (MemoryManager)](#3-记忆管理器-memorymanager)
  - [核心方法](#核心方法)
    - [`preLoad`](#preload)
    - [`switchMemory`](#switchmemory)
    - [`addMessage`](#addmessage)
    - [`getCurrentHistory`](#getcurrenthistory)
- [4\. 持久化接口 (MemoryStorage)](#4-持久化接口-memorystorage)
  - [接口方法](#接口方法)
- [5\. 配置与模型 (Models & Enums)](#5-配置与模型-models--enums)
  - [`enum class MemoryStrategy`](#enum-class-memorystrategy)
  - [`data class GenerationOptions`](#data-class-generationoptions)
  - [`data class Usage`](#data-class-usage)
- [6\. 提供者接口 (LlmProvider)](#6-提供者接口-llmprovider)
  - [方法](#方法-1)
    - [`chat`](#chat)
    - [`stream`](#stream-1)
- [7\. 工具类 (Utils)](#7-工具类-utils)
  - [`object JsonSanitizer`](#object-jsonsanitizer)
    - [`sanitize`](#sanitize)
  - [`interface HistoryFormatter`](#interface-historyformatter)
-----

## 1\. 全局入口 (StreamLLM)

`object dev.lockedfog.streamllm.StreamLLM`

库的单例入口，负责全局配置、资源生命周期管理及全局组件的持有。

### 属性

#### `memory`

* **声明**: `val memory: MemoryManager`
* **描述**: 全局唯一的记忆管理器实例。
* **参见**: [MemoryManager](#3-记忆管理器-memorymanager)

#### `json`

* **声明**: `val json: Json`
* **描述**: 全局共享的 `kotlinx.serialization.json.Json` 实例，默认配置为宽松模式 (`ignoreUnknownKeys = true`, `isLenient = true`)。

-----

### 方法

#### `init` (OpenAI 兼容模式)

使用 OpenAI 标准参数（API Key, Base URL）初始化库。

* **声明**:
  ```kotlin
  fun init(
      baseUrl: String,
      apiKey: String,
      modelName: String,
      timeoutSeconds: Long = 60,
      httpClient: HttpClient? = null,
      storage: MemoryStorage = InMemoryStorage(),
      maxMemoryCount: Int = 10
  )
  ```
* **参数**:
    * `baseUrl` (String): API 基础地址（如 `https://api.deepseek.com/v1` ）。
    * `apiKey` (String): 鉴权密钥。
    * `modelName` (String): 默认使用的模型名称。
    * `timeoutSeconds` (Long): 请求超时时间（秒）。默认 `60`。
    * `httpClient` (HttpClient?): 可选。传入外部的 Ktor Client 以复用连接池。
    * `storage` ([MemoryStorage](#4-持久化接口-memorystorage): 持久化存储实现。默认为纯内存存储。
    * `maxMemoryCount` (Int): **[New]** LRU 缓存的最大会话数。默认 `10`。

#### `init` (自定义 Provider 模式)

使用自定义实现的 Provider 初始化库。

* **声明**:
  ```kotlin
  fun init(
      provider: LlmProvider,
      storage: MemoryStorage = InMemoryStorage(),
      maxMemoryCount: Int = 10
  )
  ```
* **参数**:
    * `provider` ([LlmProvider](#6-提供者接口-llmprovider): 已实例化的 LLM 提供者。
    * `storage`: 同上。
    * `maxMemoryCount`: 同上。

#### `close`

* **声明**: `fun close()`
* **描述**: 关闭当前的 `LlmProvider` 并释放资源（如 HTTP 连接池、后台持久化协程）。建议在应用退出时调用。

-----

## 2\. 流式 DSL (StreamScope)

`class dev.lockedfog.streamllm.core.StreamScope`

`stream { ... }` 代码块的接收者对象 (Receiver)，提供了对话编排的核心 DSL。

### 入口函数

#### `stream`

* **声明**: `suspend fun stream(block: suspend StreamScope.() -> Unit)`
* **描述**: 创建 `StreamScope` 并执行挂起代码块。
* **参数**:
    * `block`: 在 `StreamScope` 上下文中执行的业务逻辑。

-----

### 核心方法 (Request)

#### `ask` (String Extension)

发送请求并等待完整响应（同步返回）。

* **声明**:
  ```kotlin
  suspend fun String.ask(
      promptTemplate: String = "",
      strategy: MemoryStrategy = MemoryStrategy.ReadWrite,
      historyWindow: Int = -1,
      system: String? = null,
      formatter: String? = null,
      options: GenerationOptions? = null, // 或展开的参数 (temp, topP...)
      onToken: (suspend (String) -> Unit)? = null
  ): String
  ```
* **参数**:
    * `this` (String): 用户输入的 Prompt。
    * `promptTemplate` (String): 模版字符串。支持 `{{it}}` (当前输入) 和 `{{history}}` (历史记录)。
    * `strategy` ([MemoryStrategy](#enum-class-memorystrategy): 记忆策略。默认 `ReadWrite`。
    * `historyWindow` (Int): 携带的历史消息数量。`-1`=全部, `0`=无, `N`=最近N条。
    * `system` (String?): 临时 System Prompt（本次请求覆盖）。
    * `formatter` (String?): 自定义历史记录格式化字符串。
    * `options` ([GenerationOptions](#data-class-generationoptions): 生成参数。
    * `onToken`: **[Suspend]** 流式回调函数。如果提供此参数，请求内部将走流式通道。
* **返回**: 完整的响应文本。

#### `stream` (String Extension)

`ask` 的流式快捷别名。

* **声明**:
  ```kotlin
  suspend fun String.stream(
      ..., // 参数同 ask
      onToken: suspend (String) -> Unit
  ): String
  ```
* **描述**: 强制要求提供 `onToken` 回调。

#### `ask<T>` (Structured Output)

请求 LLM 返回 JSON 并自动反序列化为对象 `T`。

* **声明**:
  ```kotlin
  suspend inline fun <reified T> String.ask(
      ..., // 参数同 ask
      maxRetries: Int = 3
  ): T
  ```
* **参数**:
    * `maxRetries` (Int): JSON 解析失败时的最大自动重试次数。
* **描述**: 内置自动纠错机制。如果解析失败，会将错误信息回传给 LLM 进行修正。

#### `to<T>` (String Extension)

* **声明**: `suspend inline fun <reified T> String.to(): T`
* **描述**: 将当前字符串视为 JSON 并反序列化为 `T`。包含 Markdown 清洗逻辑。

-----

### 辅助方法 (Helpers)

以下方法均为 `StreamLLM.memory` 的封装，方便在 DSL 中直接调用。

| 方法声明 | 描述 |
| :--- | :--- |
| `suspend fun newMemory(name: String, system: String? = null)` | 创建并切换到新记忆体。 |
| `suspend fun switchMemory(name: String)` | 切换到指定记忆体（支持自动加载）。 |
| `suspend fun deleteMemory(name: String)` | 删除指定记忆体（缓存+持久化）。 |
| `suspend fun clearMemory()` | 清空**当前**记忆体的消息历史。 |
| `suspend fun setSystemPrompt(name: String, prompt: String)` | 更新指定记忆体的 System Prompt。 |

-----

## 3\. 记忆管理器 (MemoryManager)

`class dev.lockedfog.streamllm.core.MemoryManager`

通过 `StreamLLM.memory` 访问。管理所有会话的生命周期、缓存及持久化。所有方法均为 **Suspend** 函数。

### 核心方法

#### `preLoad`

* **声明**: `suspend fun preLoad(memoryId: String)`
* **描述**: **[New]** 异步预加载指定记忆体到 LRU 缓存。
* **作用**: 建议在 UI 列表点击事件或转场动画开始时调用。如果该记忆体正在加载中，此方法会等待其完成（Request Coalescing），避免重复 I/O。

#### `switchMemory`

* **声明**: `suspend fun switchMemory(name: String)`
* **描述**: 切换当前活动的记忆体指针。
* **逻辑**:
    1.  检查 L1 Cache。
    2.  若未命中，检查是否有正在进行的 `preLoad` 任务并等待。
    3.  若无任务，从 `MemoryStorage` 发起加载。

#### `addMessage`

* **声明**: `suspend fun addMessage(role: ChatRole, content: String)`
* **描述**: 向当前记忆体添加消息。
* **逻辑**: Write-Through 策略。先写内存缓存，随即启动后台协程异步写入存储层。

#### `getCurrentHistory`

* **声明**: `suspend fun getCurrentHistory(windowSize: Int = -1, ...): List<ChatMessage>`
* **描述**: 获取当前上下文的消息列表。读取操作会刷新 LRU 缓存的访问顺序。

-----

## 4\. 持久化接口 (MemoryStorage)

`interface dev.lockedfog.streamllm.core.memory.MemoryStorage`

定义数据持久化层的契约。实现此接口以接入 Room, SQLDelight 或文件存储。

### 接口方法

| 方法声明 | 描述 |
| :--- | :--- |
| `getSystemPrompt(memoryId: String): String?` | 读取 System Prompt。 |
| `setSystemPrompt(memoryId: String, prompt: String)` | 写入 System Prompt。 |
| `getMessages(memoryId: String, limit: Int = -1): List<ChatMessage>` | 读取消息历史。`limit` 为 -1 时读全部。 |
| `addMessage(memoryId: String, message: ChatMessage)` | **增量写入**单条消息。 |
| `saveFullContext(memoryId: String, systemPrompt: String?, messages: List<ChatMessage>)` | **全量写入**。通常在缓存驱逐 (Eviction) 时调用，确保最终状态落地。 |
| `clearMessages(memoryId: String)` | 清空消息表。 |
| `deleteMemory(memoryId: String)` | 级联删除记忆体的所有数据。 |

**内置实现**: `dev.lockedfog.streamllm.core.memory.InMemoryStorage` (默认使用，不持久化)。

-----

## 5\. 配置与模型 (Models & Enums)

### `enum class MemoryStrategy`

| 枚举值 | 读历史 | 写历史 | 适用场景 |
| :--- | :---: | :---: | :--- |
| `ReadWrite` | ✅ | ✅ | 标准对话 (默认) |
| `ReadOnly` | ✅ | ❌ | 总结、基于上下文的问答 |
| `WriteOnly` | ❌ | ✅ | 开启新话题并存档 |
| `Stateless` | ❌ | ❌ | 单次翻译、工具调用 |

### `data class GenerationOptions`

控制 LLM 生成行为的参数集。

* `temperature` (Double?): 0.0 \~ 2.0，随机性控制。
* `topP` (Double?): 核采样概率。
* `maxTokens` (Int?): 最大生成长度。
* `stopSequences` (List\<String\>?): 停止词。
* `modelNameOverride` (String?): 单次覆盖模型名称。

### `data class Usage`

* `promptTokens`: 输入消耗。
* `completionTokens`: 输出消耗。
* `totalTokens`: 总消耗。

-----

## 6\. 提供者接口 (LlmProvider)

`interface dev.lockedfog.streamllm.provider.LlmProvider : AutoCloseable`

实现此接口以适配任意 LLM 后端。

### 方法

#### `chat`

* **声明**: `suspend fun chat(messages: List<ChatMessage>, options: GenerationOptions?): LlmResponse`
* **描述**: 发送一次性请求。

#### `stream`

* **声明**: `fun stream(messages: List<ChatMessage>, options: GenerationOptions?): Flow<LlmResponse>`
* **描述**: 发送流式请求。
* **要求**:
    * 返回 `kotlinx.coroutines.flow.Flow`。
    * Flow 的发射 (emit) 过程不应阻塞。
    * `LlmResponse` 中的 `usage` 字段通常在流的最后一个包返回。

-----

## 7\. 工具类 (Utils)

### `object JsonSanitizer`

#### `sanitize`

* **声明**: `fun sanitize(input: String): String`
* **功能**:
    1.  移除 `<think>...</think>` 标签 (DeepSeek R1 支持)。
    2.  提取 Markdown 代码块 (` ```json ... ``` `)。
    3.  若无代码块，提取首尾 `{}` 间的内容。

### `interface HistoryFormatter`

将 `List<ChatMessage>` 格式化为 String。

* **默认实现**: `HistoryFormatter.DEFAULT` (格式: `User: ... \n Assistant: ...`)
* **工厂方法**: `HistoryFormatter.fromString("user=U:{{content}};sep=\n")`
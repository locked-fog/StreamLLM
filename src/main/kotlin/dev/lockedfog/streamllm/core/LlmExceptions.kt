package dev.lockedfog.streamllm.core

/**
 * StreamLLM 核心异常基类。
 *
 * 所有由 StreamLLM 抛出的与 LLM 交互相关的异常都继承自此类。
 * 调用者可以通过捕获此异常来处理所有类型的 API 错误。
 *
 * @param message 错误描述信息
 * @param cause 原始异常 (可选)
 */
sealed class LlmException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

/**
 * 鉴权失败异常 (对应 HTTP 401/403)。
 *
 * 可能原因：
 * - API Key 无效或为空。
 * - API Key 已过期。
 * - 账户权限不足。
 */
class AuthenticationException(message: String) : LlmException(message)

/**
 * 速率限制或余额不足异常 (对应 HTTP 429)。
 *
 * 可能原因：
 * - 请求频率超过了 API 提供商的限制 (TPM/RPM)。
 * - 账户余额不足 (Insufficient Quota)。
 */
class RateLimitException(message: String) : LlmException(message)

/**
 * 请求无效异常 (对应 HTTP 400)。
 *
 * 可能原因：
 * - 上下文 (Context) 长度超过了模型的最大 Token 限制。
 * - 参数格式错误。
 */
class InvalidRequestException(message: String) : LlmException(message)

/**
 * 服务端错误异常 (对应 HTTP 5xx)。
 *
 * 可能原因：
 * - 模型服务过载或崩溃。
 * - 网关超时。
 * - 上游服务提供商暂时不可用。
 */
class ServerException(message: String) : LlmException(message)

/**
 * 未知或通用的 LLM 错误。
 *
 * 当遇到无法识别的 HTTP 状态码或非预期的解析错误时抛出。
 */
class UnknownLlmException(message: String, cause: Throwable? = null) : LlmException(message, cause)
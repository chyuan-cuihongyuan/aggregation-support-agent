package cn.chyuan.ai.types.exception;

import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 嵌入服务异常 — 区分不同失败类型，支持降级决策
 * <p>
 * 失败类型：
 * <ul>
 *   <li>QUOTA_EXCEEDED — 余额不足/配额耗尽（429），应降级到备用提供商</li>
 *   <li>RATE_LIMITED — 限流（429 但非余额问题），可重试</li>
 *   <li>AUTH_FAILED — 认证失败（401/403），不可重试</li>
 *   <li>NETWORK_ERROR — 网络异常，可重试</li>
 *   <li>PROVIDER_ERROR — 提供商内部错误（5xx），可重试</li>
 * </ul>
 */
@EqualsAndHashCode(callSuper = true)
@Data
public class EmbeddingException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /** 失败类型 */
    private final FailureType failureType;

    /** 失败的提供商名称 */
    private final String providerName;

    /** 原始 HTTP 状态码（可选） */
    private final Integer httpStatus;

    /** 是否可重试 */
    private final boolean retryable;

    /**
     * 嵌入失败类型枚举
     */
    public enum FailureType {
        /** 余额不足/配额耗尽 — 不可重试，应降级 */
        QUOTA_EXCEEDED(false),
        /** 限流 — 可重试（指数退避） */
        RATE_LIMITED(true),
        /** 认证失败 — 不可重试 */
        AUTH_FAILED(false),
        /** 网络异常 — 可重试 */
        NETWORK_ERROR(true),
        /** 提供商内部错误 — 可重试 */
        PROVIDER_ERROR(true),
        /** 未知错误 — 不可重试 */
        UNKNOWN(false);

        private final boolean retryable;

        FailureType(boolean retryable) {
            this.retryable = retryable;
        }

        public boolean isRetryable() {
            return retryable;
        }

        /**
         * 根据 HTTP 状态码和响应体推断失败类型
         */
        public static FailureType fromHttpStatus(int statusCode, String responseBody) {
            if (statusCode == 429) {
                // 区分限流和余额不足
                if (responseBody != null && (responseBody.contains("余额不足") || responseBody.contains("无可用资源包"))) {
                    return QUOTA_EXCEEDED;
                }
                return RATE_LIMITED;
            }
            if (statusCode == 401 || statusCode == 403) {
                return AUTH_FAILED;
            }
            if (statusCode >= 500) {
                return PROVIDER_ERROR;
            }
            return UNKNOWN;
        }
    }

    public EmbeddingException(FailureType failureType, String providerName, String message) {
        super(String.format("[%s] %s: %s", providerName, failureType.name(), message));
        this.failureType = failureType;
        this.providerName = providerName;
        this.httpStatus = null;
        this.retryable = failureType.isRetryable();
    }

    public EmbeddingException(FailureType failureType, String providerName, String message, Throwable cause) {
        super(String.format("[%s] %s: %s", providerName, failureType.name(), message), cause);
        this.failureType = failureType;
        this.providerName = providerName;
        this.httpStatus = null;
        this.retryable = failureType.isRetryable();
    }

    public EmbeddingException(FailureType failureType, String providerName, int httpStatus, String message) {
        super(String.format("[%s] %s (HTTP %d): %s", providerName, failureType.name(), httpStatus, message));
        this.failureType = failureType;
        this.providerName = providerName;
        this.httpStatus = httpStatus;
        this.retryable = failureType.isRetryable();
    }

    public EmbeddingException(FailureType failureType, String providerName, int httpStatus, String message, Throwable cause) {
        super(String.format("[%s] %s (HTTP %d): %s", providerName, failureType.name(), httpStatus, message), cause);
        this.failureType = failureType;
        this.providerName = providerName;
        this.httpStatus = httpStatus;
        this.retryable = failureType.isRetryable();
    }

    /**
     * 是否应降级到备用提供商
     */
    public boolean shouldFallback() {
        return failureType == FailureType.QUOTA_EXCEEDED
                || failureType == FailureType.AUTH_FAILED
                || failureType == FailureType.UNKNOWN;
    }
}

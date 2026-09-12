package cn.chyuan.ai.trigger.exception;

import cn.chyuan.ai.api.response.Response;
import cn.chyuan.ai.types.enums.ResponseCode;
import cn.chyuan.ai.types.exception.AppException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.stream.Collectors;

/**
 * 全局异常处理器
 * 统一处理Controller层抛出的各类异常，返回标准化的响应格式
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(AppException.class)
    public Object handleAppException(AppException e, HttpServletRequest request, HttpServletResponse response) {
        log.warn("业务异常 [{} {}]: code={}, info={}",
            request.getMethod(), request.getRequestURI(),
            e.getCode(), e.getInfo());

        // SSE 响应已提交时，无法再写入 JSON（Content-Type 已是 text/event-stream）
        if (response.isCommitted()) {
            return null;
        }

        response.setStatus(HttpStatus.OK.value());
        return Response.builder()
                .code(e.getCode())
                .info(e.getInfo())
                .build();
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Response<?> handleValidationException(MethodArgumentNotValidException e, HttpServletRequest request) {
        String message = e.getBindingResult().getFieldErrors().stream()
                .map(FieldError::getDefaultMessage)
                .collect(Collectors.joining("; "));

        log.warn("参数校验失败 [{} {}]: {}",
            request.getMethod(), request.getRequestURI(), message);

        return Response.builder()
                .code(ResponseCode.E1001.getCode())
                .info("参数校验失败: " + message)
                .build();
    }

    @ExceptionHandler(Exception.class)
    public Object handleException(Exception e, HttpServletRequest request, HttpServletResponse response) {
        log.error("系统异常 [{} {}]: {}",
            request.getMethod(), request.getRequestURI(), e.getMessage(), e);

        // SSE 响应已提交时（已开始发送事件），无法再写入 JSON 响应体
        // Content-Type 已被设为 text/event-stream，强制写入 JSON 会触发
        // HttpMessageNotWritableException: No converter for Response with preset Content-Type
        if (response.isCommitted()) {
            log.warn("响应已提交，跳过异常响应写入: {} {}", request.getMethod(), request.getRequestURI());
            return null;
        }

        response.setStatus(HttpStatus.INTERNAL_SERVER_ERROR.value());
        return Response.builder()
                .code(ResponseCode.UN_ERROR.getCode())
                .info("系统繁忙，请稍后重试")
                .build();
    }

    /**
     * 处理静态资源未找到异常
     * 浏览器访问根路径 / 或请求 favicon.ico 时会触发，不应作为系统错误记录
     */
    @ExceptionHandler(NoResourceFoundException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public Response<?> handleNoResourceFound(NoResourceFoundException e, HttpServletRequest request) {
        log.debug("资源未找到 [{} {}]: {}",
            request.getMethod(), request.getRequestURI(), e.getMessage());

        return Response.builder()
                .code(ResponseCode.UN_ERROR.getCode())
                .info("资源不存在")
                .build();
    }

    @ExceptionHandler(ConstraintViolationException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Response<?> handleConstraintViolationException(ConstraintViolationException e, HttpServletRequest request) {
        String message = e.getConstraintViolations().stream()
                .map(ConstraintViolation::getMessage)
                .collect(Collectors.joining("; "));

        log.warn("参数约束校验失败 [{} {}]: {}",
            request.getMethod(), request.getRequestURI(), message);

        return Response.builder()
                .code(ResponseCode.E1001.getCode())
                .info("参数约束校验失败: " + message)
                .build();
    }

    // ===== SELFLOOP2 loop-220：客户端错误精确映射（审计路线 2，同 obs/mcp 模式） =====

    /** 请求体不可读（畸形 JSON）：400 */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Response<?> handleMessageNotReadable(HttpMessageNotReadableException e, HttpServletRequest request) {
        log.warn("请求体不可读 [{} {}]: {}", request.getMethod(), request.getRequestURI(), e.getMessage());
        return Response.builder()
                .code(ResponseCode.ILLEGAL_PARAMETER.getCode())
                .info("请求体格式错误或不可读")
                .build();
    }

    /** 参数类型不匹配：400，带参数名 */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Response<?> handleTypeMismatch(MethodArgumentTypeMismatchException e, HttpServletRequest request) {
        log.warn("参数类型不匹配 [{} {}]: {}", request.getMethod(), request.getRequestURI(), e.getName());
        return Response.builder()
                .code(ResponseCode.ILLEGAL_PARAMETER.getCode())
                .info("参数类型不匹配: " + e.getName())
                .build();
    }

    /** 缺少必填参数：400，带参数名 */
    @ExceptionHandler(MissingServletRequestParameterException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Response<?> handleMissingParam(MissingServletRequestParameterException e, HttpServletRequest request) {
        log.warn("缺少必填参数 [{} {}]: {}", request.getMethod(), request.getRequestURI(), e.getParameterName());
        return Response.builder()
                .code(ResponseCode.ILLEGAL_PARAMETER.getCode())
                .info("缺少必填参数: " + e.getParameterName())
                .build();
    }

    /** 方法不支持：405 */
    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    @ResponseStatus(HttpStatus.METHOD_NOT_ALLOWED)
    public Response<?> handleMethodNotSupported(HttpRequestMethodNotSupportedException e, HttpServletRequest request) {
        return Response.builder()
                .code(ResponseCode.METHOD_NOT_SUPPORTED.getCode())
                .info(ResponseCode.METHOD_NOT_SUPPORTED.getInfo())
                .build();
    }

    /** 媒体类型不支持：415 */
    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    @ResponseStatus(HttpStatus.UNSUPPORTED_MEDIA_TYPE)
    public Response<?> handleMediaTypeNotSupported(HttpMediaTypeNotSupportedException e, HttpServletRequest request) {
        return Response.builder()
                .code(ResponseCode.MEDIA_TYPE_NOT_SUPPORTED.getCode())
                .info(ResponseCode.MEDIA_TYPE_NOT_SUPPORTED.getInfo())
                .build();
    }
}

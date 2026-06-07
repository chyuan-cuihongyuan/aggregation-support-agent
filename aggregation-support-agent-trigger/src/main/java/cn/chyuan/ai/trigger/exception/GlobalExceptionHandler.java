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
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;
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
}

package cn.chyuan.ai.trigger.exception;

import cn.chyuan.ai.api.response.Response;
import cn.chyuan.ai.types.enums.ResponseCode;
import cn.chyuan.ai.types.exception.AppException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.stream.Collectors;

/**
 * 全局异常处理器
 * 统一处理Controller层抛出的各类异常，返回标准化的响应格式
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(AppException.class)
    @ResponseStatus(HttpStatus.OK)
    public Response<?> handleAppException(AppException e, HttpServletRequest request) {
        log.warn("业务异常 [{} {}]: code={}, info={}",
            request.getMethod(), request.getRequestURI(),
            e.getCode(), e.getInfo());
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

    @ExceptionHandler(Exception.class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    public Response<?> handleException(Exception e, HttpServletRequest request) {
        log.error("系统异常 [{} {}]: {}",
            request.getMethod(), request.getRequestURI(), e.getMessage(), e);

        return Response.builder()
                .code(ResponseCode.UN_ERROR.getCode())
                .info("系统繁忙，请稍后重试")
                .build();
    }
}
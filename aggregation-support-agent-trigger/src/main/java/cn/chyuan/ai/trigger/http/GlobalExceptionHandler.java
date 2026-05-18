package cn.chyuan.ai.trigger.http;

import cn.chyuan.ai.api.response.Response;
import cn.chyuan.ai.types.enums.ResponseCode;
import cn.chyuan.ai.types.exception.AppException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(AppException.class)
    public Response<Object> handleAppException(AppException e) {
        return Response.builder()
                .code(e.getCode())
                .info(e.getInfo())
                .data(null)
                .build();
    }

    @ExceptionHandler(Exception.class)
    public Response<Object> handleException(Exception e) {
        log.error("未处理异常", e);
        return Response.builder()
                .code(ResponseCode.UN_ERROR.getCode())
                .info(ResponseCode.UN_ERROR.getInfo())
                .data(null)
                .build();
    }
}

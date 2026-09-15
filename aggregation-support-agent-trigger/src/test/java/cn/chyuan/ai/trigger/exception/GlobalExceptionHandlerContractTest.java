package cn.chyuan.ai.trigger.exception;

import cn.chyuan.ai.api.response.Response;
import cn.chyuan.ai.types.enums.ResponseCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.converter.HttpMessageConversionException;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.lang.reflect.Method;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * agg 异常映射契约测试（SELFLOOP2 loop-220，同 obs/mcp 模式）。
 * 直接调用式：断言信封 code/info + @ResponseStatus 声明的 HTTP 状态。
 */
@DisplayName("GlobalExceptionHandler 异常映射契约")
class GlobalExceptionHandlerContractTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();
    private final MockHttpServletRequest request = new MockHttpServletRequest("GET", "/probe");

    @Test
    @DisplayName("畸形 JSON → 0002 + 400（新增）")
    void malformedBody() {
        Response<?> resp = handler.handleMessageNotReadable(
                new HttpMessageNotReadableException("bad json", new HttpMessageConversionException("x")), request);
        assertEquals("0002", resp.getCode());
        assertStatus(HttpMessageNotReadableException.class, HttpStatus.BAD_REQUEST);
    }

    @Test
    @DisplayName("类型不匹配 → 0002 + 参数名 + 400（新增）")
    void typeMismatch() {
        Response<?> resp = handler.handleTypeMismatch(new MethodArgumentTypeMismatchException(
                new IllegalArgumentException("x"), Integer.class, "id", null, null), request);
        assertEquals("0002", resp.getCode());
        assertTrue(String.valueOf(resp.getInfo()).contains("id"));
        assertStatus(MethodArgumentTypeMismatchException.class, HttpStatus.BAD_REQUEST);
    }

    @Test
    @DisplayName("缺少必填参数 → 0002 + 参数名 + 400（新增）")
    void missingParam() {
        Response<?> resp = handler.handleMissingParam(
                new MissingServletRequestParameterException("required", String.class.getName()), request);
        assertEquals("0002", resp.getCode());
        assertTrue(String.valueOf(resp.getInfo()).contains("required"));
        assertStatus(MissingServletRequestParameterException.class, HttpStatus.BAD_REQUEST);
    }

    @Test
    @DisplayName("方法不支持 → 0007 + 405（新增，与 mcp 对齐）")
    void methodNotSupported() {
        Response<?> resp = handler.handleMethodNotSupported(
                new HttpRequestMethodNotSupportedException("POST"), request);
        assertEquals("0007", resp.getCode());
        assertStatus(HttpRequestMethodNotSupportedException.class, HttpStatus.METHOD_NOT_ALLOWED);
    }

    @Test
    @DisplayName("媒体类型不支持 → 0008 + 415（新增，与 mcp 对齐）")
    void mediaTypeNotSupported() {
        Response<?> resp = handler.handleMediaTypeNotSupported(
                new HttpMediaTypeNotSupportedException("text/plain", java.util.List.of()), request);
        assertEquals("0008", resp.getCode());
        assertStatus(HttpMediaTypeNotSupportedException.class, HttpStatus.UNSUPPORTED_MEDIA_TYPE);
    }

    @Test
    @DisplayName("既有校验码族契约：E1001 校验失败码不变")
    void validationCodeFamilyUnchanged() {
        assertEquals("E1001", ResponseCode.E1001.getCode());
    }

    @Test
    @DisplayName("405/415 客户端错误必须 warn 留痕（loop-405 补静默分支）")
    void clientErrorHandlersMustLogWarn() {
        ch.qos.logback.classic.Logger logger = (ch.qos.logback.classic.Logger)
                org.slf4j.LoggerFactory.getLogger(GlobalExceptionHandler.class);
        ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent> appender =
                new ch.qos.logback.core.read.ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            handler.handleMethodNotSupported(new HttpRequestMethodNotSupportedException("POST"), request);
            handler.handleMediaTypeNotSupported(
                    new HttpMediaTypeNotSupportedException("text/plain", java.util.List.of()), request);
        } finally {
            logger.detachAppender(appender);
        }
        long warns = appender.list.stream()
                .filter(e -> e.getLevel() == ch.qos.logback.classic.Level.WARN)
                .filter(e -> String.valueOf(e.getFormattedMessage()).contains("/probe"))
                .count();
        assertTrue(warns >= 2, "405/415 各应产生一条含请求上下文的 WARN，实际 " + warns);
    }

    /** 断言「处理该异常类型的 handler 方法」上的 @ResponseStatus */
    private void assertStatus(Class<?> exType, HttpStatus expected) {
        Method m = Arrays.stream(GlobalExceptionHandler.class.getMethods())
                .filter(x -> x.getParameterCount() == 2 && x.getParameterTypes()[0] == exType)
                .findFirst().orElseThrow(() -> new AssertionError("handler not found for " + exType));
        ResponseStatus rs = m.getAnnotation(ResponseStatus.class);
        assertNotNull(rs, exType + " 的 handler 缺 @ResponseStatus");
        assertEquals(expected, rs.value());
    }
}

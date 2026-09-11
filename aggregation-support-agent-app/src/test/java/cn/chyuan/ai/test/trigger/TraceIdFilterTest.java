package cn.chyuan.ai.test.trigger;

import cn.chyuan.ai.trigger.filter.TraceIdFilter;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class TraceIdFilterTest {

    private final TraceIdFilter filter = new TraceIdFilter();

    @Test
    void propagatesUpstreamTraceIdIntoMdcAndResponse() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/alerts/active");
        request.addHeader(TraceIdFilter.HEADER_TRACE_ID, "upstream-trace-002");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<String> mdcInsideChain = new AtomicReference<>();

        filter.doFilter(request, response, (req, res) -> mdcInsideChain.set(MDC.get(TraceIdFilter.MDC_TRACE_ID)));

        // MDC key 与 logback pattern %X{trace-id} 对齐
        assertThat(mdcInsideChain.get()).isEqualTo("upstream-trace-002");
        assertThat(response.getHeader(TraceIdFilter.HEADER_TRACE_ID)).isEqualTo("upstream-trace-002");
        assertThat(MDC.get(TraceIdFilter.MDC_TRACE_ID)).isNull();
    }

    @Test
    void generatesTraceIdWhenHeaderMissing() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/chat");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<String> mdcInsideChain = new AtomicReference<>();

        filter.doFilter(request, response, (req, res) -> mdcInsideChain.set(MDC.get(TraceIdFilter.MDC_TRACE_ID)));

        assertThat(mdcInsideChain.get()).isNotBlank();
        assertThat(response.getHeader(TraceIdFilter.HEADER_TRACE_ID)).isEqualTo(mdcInsideChain.get());
        assertThat(MDC.get(TraceIdFilter.MDC_TRACE_ID)).isNull();
    }

    @Test
    void rejectsMalformedHeaderAndMdcClearedEvenWhenChainThrows() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/auth/me");
        request.addHeader(TraceIdFilter.HEADER_TRACE_ID, "evil\ninjection");
        MockHttpServletResponse response = new MockHttpServletResponse();

        try {
            filter.doFilter(request, response, (req, res) -> {
                throw new RuntimeException("boom");
            });
        } catch (Exception expected) {
            // 过滤器不吞业务异常，只保证清理
        }
        assertThat(MDC.get(TraceIdFilter.MDC_TRACE_ID)).isNull();
    }
}

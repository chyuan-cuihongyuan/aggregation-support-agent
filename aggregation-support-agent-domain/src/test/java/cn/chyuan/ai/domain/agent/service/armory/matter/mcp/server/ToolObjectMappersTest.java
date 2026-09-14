package cn.chyuan.ai.domain.agent.service.armory.matter.mcp.server;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * b-52 工具输出 mapper 契约：java.time 可序列化为可读文本、
 * 未知字段宽容（spring-boot 对外契约同款口径）。
 */
@DisplayName("ToolObjectMappers 共享 mapper 契约（b-52）")
class ToolObjectMappersTest {

    @Test
    void localDateTimeSerializesAsReadableText() throws Exception {
        ObjectMapper mapper = ToolObjectMappers.create();

        String json = mapper.writeValueAsString(Map.of("ts", LocalDateTime.of(2026, 9, 15, 10, 30, 0)));

        assertThat(json).contains("2026-09-15 10:30:00");
    }

    @Test
    void unknownFieldsTolerated() throws Exception {
        ObjectMapper mapper = ToolObjectMappers.create();

        assertThat(mapper.readValue("{\"unknownKey\":1}", Map.class)).containsKey("unknownKey");
        assertThat(mapper.isEnabled(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)).isFalse();
    }
}

package cn.chyuan.ai.domain.agent.service.armory.matter.mcp.server;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.fasterxml.jackson.datatype.jsr310.ser.LocalDateTimeSerializer;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * MCP 工具输出共享 ObjectMapper 工厂（b-52 / 工单 1284，spring-boot Jackson
 * 默认口径对齐——此前 5 个工具类各自裸 new ObjectMapper() 的三重风险收口）：
 * <ul>
 *   <li>JavaTimeModule：LocalDateTime 等 java.time 类型可序列化（裸 mapper 直接抛
 *       InvalidDefinitionException）</li>
 *   <li>日期 ISO 文本而非时间戳数组（LLM 上下文可读）</li>
 *   <li>FAIL_ON_UNKNOWN_PROPERTIES 关闭（spring-boot 对外契约同款宽容度）</li>
 * </ul>
 */
public final class ToolObjectMappers {

    private static final DateTimeFormatter ISO_MS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private ToolObjectMappers() {
    }

    public static ObjectMapper create() {
        ObjectMapper mapper = new ObjectMapper();
        JavaTimeModule javaTime = new JavaTimeModule();
        // LocalDateTime 统一 "yyyy-MM-dd HH:mm:ss" 文本（LLM/审计可读，非时间戳数组）
        javaTime.addSerializer(LocalDateTime.class, new LocalDateTimeSerializer(ISO_MS));
        mapper.registerModule(javaTime);
        mapper.disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
        return mapper;
    }
}

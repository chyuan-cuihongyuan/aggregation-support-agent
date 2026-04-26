package cn.chyuan.ai.domain.agent.service.armory.matter.mcp.server;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

/**
 * 日期时间工具 — 提供当前时间查询能力，AIOps 分析时用于时间戳判断
 * <p>
 * 在 AIOps 场景中，智能体经常需要获取当前时间来辅助判断：
 * <ul>
 *   <li>判断告警发生时间与当前时间的间隔</li>
 *   <li>评估是否在业务高峰期或低谷期</li>
 *   <li>辅助生成包含时间信息的分析报告</li>
 * </ul>
 * <p>
 * 迁移自 OnCall-Agent-java 项目，作为本地 MCP 工具注册为 Spring Bean。
 */
@Slf4j
@Service
public class DateTimeTools {

    /**
     * 获取当前日期和时间
     * <p>
     * 返回用户所在时区的 ISO 格式字符串，例如：2025-01-15T14:30:00+08:00[Asia/Shanghai]
     *
     * @return 带时区信息的 ISO 格式日期时间字符串
     */
    @Tool(description = "获取当前日期和时间，返回用户所在时区的 ISO 格式字符串")
    public String getCurrentDateTime() {
        log.info("工具调用: 获取当前日期时间");
        return LocalDateTime.now()
                .atZone(LocaleContextHolder.getTimeZone().toZoneId())
                .toString();
    }
}

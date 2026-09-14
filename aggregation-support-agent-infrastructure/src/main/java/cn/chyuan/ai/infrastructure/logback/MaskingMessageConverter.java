package cn.chyuan.ai.infrastructure.logback;

import ch.qos.logback.classic.pattern.MessageConverter;
import ch.qos.logback.classic.spi.ILoggingEvent;

import java.util.regex.Pattern;

/**
 * 日志敏感信息掩码 — 出口统一脱敏（工单 0414/0415，SELFLOOP3 loop-308）
 * <p>
 * 以 logback conversionRule 覆盖 %m，在 sink 层统一掩码（OWASP Logging Cheat Sheet
 * 惯例：mask at the sink, not at each call site）。词边界断言防止误伤长数字串。
 */
public class MaskingMessageConverter extends MessageConverter {

    /** 大陆手机号：1[3-9] 开头 11 位，前后非数字（防订单号/时间戳连续段误伤） */
    private static final Pattern MOBILE = Pattern.compile("(?<!\\d)(1[3-9]\\d{9})(?!\\d)");

    /** API key：sk- 前缀至少 8 位字母数字（DeepSeek/智谱等主流形态） */
    private static final Pattern API_KEY = Pattern.compile("sk-[A-Za-z0-9]{8,}");

    /** 掩码纯函数 — 可直接单测 */
    public static String mask(String message) {
        if (message == null || message.isEmpty()) {
            return message;
        }
        String masked = API_KEY.matcher(message).replaceAll("sk-****");
        return MOBILE.matcher(masked).replaceAll(mr -> mr.group(1).substring(0, 3) + "****"
                + mr.group(1).substring(9));
    }

    @Override
    public String convert(ILoggingEvent event) {
        return mask(super.convert(event));
    }
}

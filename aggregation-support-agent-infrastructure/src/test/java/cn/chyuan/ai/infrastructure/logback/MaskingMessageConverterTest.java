package cn.chyuan.ai.infrastructure.logback;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 日志敏感信息掩码规则（工单 0414/0415，SELFLOOP3 loop-308）
 */
class MaskingMessageConverterTest {

    @Test
    void mobileNumberIsMaskedKeepingHeadAndTail() {
        assertThat(MaskingMessageConverter.mask("用户手机号 13812345678 已注册"))
                .isEqualTo("用户手机号 138****78 已注册");
    }

    @Test
    void longDigitRunIsNotFalsePositive() {
        // 15 位订单号包含连续 11 位数字段，但整体为数字串——不应命中
        assertThat(MaskingMessageConverter.mask("orderId=202609151234567"))
                .isEqualTo("orderId=202609151234567");
    }

    @Test
    void apiKeyIsTruncated() {
        assertThat(MaskingMessageConverter.mask("init key sk-abcdef1234567890 done"))
                .isEqualTo("init key sk-**** done");
    }

    @Test
    void multipleHitsInMixedText() {
        assertThat(MaskingMessageConverter.mask("tel=13998887766 key=sk-xyz9876543210"))
                .isEqualTo("tel=139****66 key=sk-****");
    }

    @Test
    void plainTextPassesThroughAndNullSafe() {
        assertThat(MaskingMessageConverter.mask("普通日志消息 trace-id=abc")).isEqualTo("普通日志消息 trace-id=abc");
        assertThat(MaskingMessageConverter.mask(null)).isNull();
        assertThat(MaskingMessageConverter.mask("")).isEmpty();
    }
}

package cn.chyuan.ai.domain.browser.service;

import cn.chyuan.ai.domain.browser.model.valobj.ActionErrorVO;
import cn.chyuan.ai.domain.browser.model.valobj.BrowserActionVO;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 动作校验器单测（工单 0339 AQ1）：六类动作合法构造/必填/超限/scheme/未知类型。
 */
class ActionValidatorTest {

    private final ActionValidator validator = new ActionValidator();

    @Test
    void 六类动作合法构造() {
        List<BrowserActionVO> actions = List.of(
                BrowserActionVO.builder().type(BrowserActionVO.NAVIGATE).url("https://a.com").build(),
                BrowserActionVO.builder().type(BrowserActionVO.CLICK).selector("#btn").build(),
                BrowserActionVO.builder().type(BrowserActionVO.TYPE).selector("#q").text("关键词").build(),
                BrowserActionVO.builder().type(BrowserActionVO.SCROLL).selector("body").direction("down").amount(500).build(),
                BrowserActionVO.builder().type(BrowserActionVO.EXTRACT).fields(Map.of("标题", "h1")).build(),
                BrowserActionVO.builder().type(BrowserActionVO.WAIT).waitMs(500).build());
        assertTrue(validator.validateAll(actions).isEmpty(), "合法动作应零错误");
    }

    @Test
    void 必填缺失与超限与scheme拒绝() {
        // navigate 缺 url / 非 http scheme
        assertEquals(1, validator.validate(BrowserActionVO.builder().type(BrowserActionVO.NAVIGATE).build()).size());
        assertEquals("SCHEME_REJECTED", validator.validate(BrowserActionVO.builder()
                .type(BrowserActionVO.NAVIGATE).url("javascript:alert(1)").build()).get(0).getCode());
        // click 缺 selector
        assertEquals("REQUIRED_MISSING", validator.validate(BrowserActionVO.builder()
                .type(BrowserActionVO.CLICK).build()).get(0).getCode());
        // type 缺 text
        assertTrue(validator.validate(BrowserActionVO.builder()
                .type(BrowserActionVO.TYPE).selector("#q").build())
                .stream().anyMatch(e -> "text".equals(e.getField())
                        && "REQUIRED_MISSING".equals(e.getCode())));
        // scroll 方向非法 / amount 超限
        assertEquals("REQUIRED_MISSING", validator.validate(BrowserActionVO.builder()
                .type(BrowserActionVO.SCROLL).selector("body").amount(10).build())
                .stream().filter(e -> e.getField().equals("direction")).findFirst().orElseThrow().getCode());
        assertEquals("RANGE_VIOLATION", validator.validate(BrowserActionVO.builder()
                .type(BrowserActionVO.SCROLL).selector("body").direction("down").amount(99999).build())
                .stream().filter(e -> e.getField().equals("amount")).findFirst().orElseThrow().getCode());
        // wait 超上限
        assertEquals("RANGE_VIOLATION", validator.validate(BrowserActionVO.builder()
                .type(BrowserActionVO.WAIT).waitMs(30001).build()).get(0).getCode());
        // extract 缺 fields
        assertEquals("REQUIRED_MISSING", validator.validate(BrowserActionVO.builder()
                .type(BrowserActionVO.EXTRACT).build()).get(0).getCode());
    }

    @Test
    void 未知类型与批量错误路径() {
        assertEquals("TYPE_UNKNOWN", validator.validate(BrowserActionVO.builder()
                .type("drag").build()).get(0).getCode());
        assertEquals("TYPE_UNKNOWN", validator.validate(null).get(0).getField().equals("type")
                ? "TYPE_UNKNOWN" : "x");
        // 批量：第二动作非法 → 路径带序号
        List<ActionErrorVO> errors = validator.validateAll(List.of(
                BrowserActionVO.builder().type(BrowserActionVO.WAIT).waitMs(1).build(),
                BrowserActionVO.builder().type(BrowserActionVO.CLICK).build()));
        assertEquals("[1].selector", errors.get(0).getField());
    }
}

package cn.chyuan.ai.domain.browser.service;

import cn.chyuan.ai.domain.browser.model.valobj.BrowserActionVO;
import cn.chyuan.ai.domain.browser.model.valobj.BrowserTaskTemplateVO;
import cn.chyuan.ai.domain.browser.model.valobj.ElementVO;
import cn.chyuan.ai.domain.browser.model.valobj.ExtractionResultVO;
import cn.chyuan.ai.domain.browser.model.valobj.PageSnapshotVO;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 抽取器与模板实例化单测（工单 0343 AQ5 + 0344 AQ6）。
 */
class ExtractorTemplateTest {

    private final StructuredExtractor extractor = new StructuredExtractor();
    private final TemplateInstantiator instantiator = new TemplateInstantiator();

    private PageSnapshotVO snapshot() {
        return PageSnapshotVO.builder()
                .url("https://s.com").title("列表页").capturedAtMs(1L)
                .elements(List.of(
                        ElementVO.builder().elementId("t1").role("text").name("商品名称 手机X").build(),
                        ElementVO.builder().elementId("t2").role("text").name("价格 3999").build(),
                        ElementVO.builder().elementId("l1").role("link").name("详情链接").build()))
                .build();
    }

    @Test
    void 类型提取与多值收集() {
        ExtractionResultVO result = extractor.extract(snapshot(), List.of(
                new ExtractionResultVO.FieldSpec("商品名称", "text", false, true),
                new ExtractionResultVO.FieldSpec("价格", "number", false, true)));
        assertTrue(result.isSuccess());
        assertEquals("商品名称 手机X", result.getValues().get("商品名称"));
        assertEquals(3999L, result.getValues().get("价格"));
        // 多值
        PageSnapshotVO multi = PageSnapshotVO.builder()
                .url("u").title("t").capturedAtMs(0)
                .elements(List.of(
                        ElementVO.builder().elementId("a").role("link").name("链接甲").build(),
                        ElementVO.builder().elementId("b").role("link").name("链接乙").build()))
                .build();
        ExtractionResultVO links = extractor.extract(multi, List.of(
                new ExtractionResultVO.FieldSpec("链接", "link", true, false)));
        assertEquals(2, ((List<?>) links.getValues().get("链接")).size());
    }

    @Test
    void 必填缺失与类型不符与空快照() {
        ExtractionResultVO missing = extractor.extract(snapshot(), List.of(
                new ExtractionResultVO.FieldSpec("库存", "number", false, true)));
        assertFalse(missing.isSuccess());
        assertTrue(missing.getMissing().get(0).contains("无匹配元素"));
        // 类型不符：价格为非数字文本
        ExtractionResultVO badType = extractor.extract(snapshot(), List.of(
                new ExtractionResultVO.FieldSpec("商品名称", "number", false, true)));
        assertFalse(badType.isSuccess());
        assertTrue(badType.getMissing().get(0).contains("类型不符"));
        // 空快照
        ExtractionResultVO empty = extractor.extract(
                PageSnapshotVO.builder().elements(List.of()).build(),
                List.of(new ExtractionResultVO.FieldSpec("x", "text", false, false)));
        assertFalse(empty.isSuccess());
        assertThrows(IllegalArgumentException.class, () -> extractor.extract(snapshot(), List.of()));
    }

    @Test
    void 模板参数替换与回放校验() {
        BrowserTaskTemplateVO template = BrowserTaskTemplateVO.builder()
                .name("搜索模板")
                .goal("按关键词搜索")
                .parameters(Map.of("keyword", "搜索词", "target", "目标页"))
                .actions(List.of(
                        BrowserActionVO.builder().type(BrowserActionVO.NAVIGATE)
                                .url("https://s.com/search?q={{keyword}}").build(),
                        BrowserActionVO.builder().type(BrowserActionVO.CLICK)
                                .selector("{{target}}").build()))
                .build();
        TemplateInstantiator.Result ok = instantiator.instantiate(template,
                Map.of("keyword", "手机", "target", "#buy"));
        assertTrue(ok.errors().isEmpty());
        assertEquals("https://s.com/search?q=手机", ok.actions().get(0).getUrl());
        assertEquals("#buy", ok.actions().get(1).getSelector());
        // 缺参数拒绝
        TemplateInstantiator.Result missing = instantiator.instantiate(template, Map.of("keyword", "手机"));
        assertTrue(missing.errors().contains("缺参数: target"));
        // 未声明占位符拒绝
        BrowserTaskTemplateVO undeclared = BrowserTaskTemplateVO.builder()
                .name("坏模板").goal("g")
                .parameters(Map.of("a", "A"))
                .actions(List.of(BrowserActionVO.builder().type(BrowserActionVO.NAVIGATE)
                        .url("https://x.com/{{ghost}}").build()))
                .build();
        assertTrue(instantiator.instantiate(undeclared, Map.of("a", "1"))
                .errors().get(0).contains("未声明"));
        assertThrows(IllegalArgumentException.class, () -> instantiator.instantiate(
                BrowserTaskTemplateVO.builder().name("x").actions(List.of()).build(), Map.of()));
    }
}

package cn.chyuan.ai.domain.browser.service;

import cn.chyuan.ai.domain.browser.model.valobj.ElementVO;
import cn.chyuan.ai.domain.browser.model.valobj.LocateResult;
import cn.chyuan.ai.domain.browser.model.valobj.PageSnapshotVO;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 快照摘要器与定位器单测（工单 0340 AQ2 + 0341 AQ3）。
 */
class SnapshotLocatorTest {

    private final SnapshotSummarizer summarizer = new SnapshotSummarizer(3);
    private final ElementLocator locator = new ElementLocator();

    private PageSnapshotVO snapshot() {
        return PageSnapshotVO.builder()
                .url("https://example.com").title("示例页").capturedAtMs(1000L)
                .elements(List.of(
                        ElementVO.builder().elementId("e1").role("input").name("搜索框")
                                .selector("#search-input").enabled(true).editable(true).build(),
                        ElementVO.builder().elementId("e2").role("button").name("搜索")
                                .selector("button.primary").enabled(true).editable(false).build(),
                        ElementVO.builder().elementId("e3").role("link").name("搜索帮助")
                                .selector("a.help").enabled(false).editable(false).build()))
                .build();
    }

    @Test
    void 快照摘要分组计数与可编辑清单() {
        String summary = summarizer.summarize(snapshot());
        assertTrue(summary.contains("3 个可交互元素"));
        assertTrue(summary.contains("button × 1"));
        assertTrue(summary.contains("input × 1"));
        assertTrue(summary.contains("link × 1"));
        assertTrue(summary.contains("input#搜索框"), "可编辑元素应列出");
        assertTrue(summary.contains("页面 https://example.com（示例页）"));
    }

    @Test
    void 序列化往返一致() {
        PageSnapshotVO snapshot = snapshot();
        String text = summarizer.serialize(snapshot);
        PageSnapshotVO restored = summarizer.deserialize(text);
        assertEquals(snapshot.getUrl(), restored.getUrl());
        assertEquals(snapshot.getTitle(), restored.getTitle());
        assertEquals(snapshot.getCapturedAtMs(), restored.getCapturedAtMs());
        assertEquals(snapshot.getElements().size(), restored.getElements().size());
        assertEquals(snapshot.getElements().get(1).getSelector(), restored.getElements().get(1).getSelector());
        assertTrue(restored.getElements().get(0).isEditable());
        assertFalse(restored.getElements().get(2).isEnabled());
        // 重放一致
        assertEquals(text, summarizer.serialize(restored));
        assertThrows(IllegalArgumentException.class, () -> summarizer.deserialize("bad"));
    }

    @Test
    void 摘要截断与空快照() {
        PageSnapshotVO many = PageSnapshotVO.builder()
                .url("u").title("t").capturedAtMs(0)
                .elements(List.of(
                        ElementVO.builder().elementId("e" + 1).role("input").name("n" + 1)
                                .editable(true).build(),
                        ElementVO.builder().elementId("e" + 2).role("input").name("n" + 2)
                                .editable(true).build(),
                        ElementVO.builder().elementId("e" + 3).role("input").name("n" + 3)
                                .editable(true).build(),
                        ElementVO.builder().elementId("e" + 4).role("input").name("n" + 4)
                                .editable(true).build()))
                .build();
        String summary = summarizer.summarize(many);
        assertTrue(summary.contains("已截断"), "超上限应标注截断");
        assertTrue(new SnapshotSummarizer(3).summarize(null).contains("无可交互元素"));
    }

    @Test
    void 定位器多策略依序回退() {
        // selector 精确
        assertEquals("e1", hit(locator.locate(snapshot(), "#search-input", null, null, null, null)));
        // selector 片段包含唯一（button.primary）
        assertEquals("e2", hit(locator.locate(snapshot(), "button.primary", null, null, null, null)));
        // 文本包含唯一（大小写折叠）
        assertEquals("e3", hit(locator.locate(snapshot(), null, "搜索帮助", null, null, null)));
        // role+name
        assertEquals("e2", hit(locator.locate(snapshot(), null, null, "button", "搜索", null)));
        // index 兜底
        assertEquals("e1", hit(locator.locate(snapshot(), null, null, null, null, 0)));
        // index 越界
        assertEquals("MISS", locator.locate(snapshot(), null, null, null, null, 99).getStatus());
    }

    @Test
    void 歧义与未找到与空快照() {
        // 歧义："搜索" 文本同时命中 搜索框/搜索/搜索帮助 → 3 候选
        LocateResult ambiguous = locator.locate(snapshot(), null, "搜索", null, null, null);
        assertEquals("AMBIGUOUS", ambiguous.getStatus());
        assertEquals(3, ambiguous.getCandidates().size());
        // 零命中
        assertEquals("MISS", locator.locate(snapshot(), "#ghost", null, null, null, null).getStatus());
        // 空快照
        assertEquals("MISS", locator.locate(PageSnapshotVO.builder().elements(List.of()).build(),
                "#x", null, null, null, null).getStatus());
        assertNull(locator.locate(snapshot(), "#ghost", null, null, null, null).getElement());
    }

    private String hit(LocateResult result) {
        assertEquals("HIT", result.getStatus());
        return result.getElement().getElementId();
    }

    private void assertFalse(boolean condition) {
        org.junit.jupiter.api.Assertions.assertFalse(condition);
    }
}

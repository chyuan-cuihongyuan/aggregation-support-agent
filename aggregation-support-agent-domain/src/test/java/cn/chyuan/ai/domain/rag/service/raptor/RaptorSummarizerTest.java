package cn.chyuan.ai.domain.rag.service.raptor;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** RAPTOR 层级摘要单测（工单 0228 AE1）：建树/摘要器注入/树式上卷。 */
class RaptorSummarizerTest {

    @Test
    void 建树与层级收敛() {
        // 5 块、每组 2 → L1 两节点 → L2 一节点
        RaptorSummarizer summarizer = new RaptorSummarizer(2, String::new);
        RaptorSummarizer.RaptorTree tree = summarizer.build("doc",
                List.of("块一", "块二", "块三", "块四", "块五"));
        // 5 块每组 2：L1=3 节点 → L2=2 → L3=1，共 3 层 11 节点
        assertEquals(3, tree.maxLayer());
        assertEquals(5 + 3 + 2 + 1, tree.nodes().size());
        // 原始块有父引用
        assertEquals("doc.L1.0", tree.nodes().get("doc.L0.0").parentKey());
        // 根节点无父
        var root = tree.nodes().get("doc.L3.0");
        assertTrue(root.parentKey() == null);
    }

    @Test
    void 摘要器注入与兜底() {
        // 兜底截取 200 字
        RaptorSummarizer summarizer = new RaptorSummarizer();
        RaptorSummarizer.RaptorTree tree = summarizer.build("d", List.of("短".repeat(500), "块二"));
        assertTrue(tree.nodes().get("d.L1.0").text().length() <= 200);
        // LLM 挂点注入
        RaptorSummarizer withLlm = new RaptorSummarizer(2, text -> "摘要:" + text.length());
        RaptorSummarizer.RaptorTree tree2 = withLlm.build("d2", List.of("aa", "bb", "cc", "dd"));
        assertTrue(tree2.nodes().get("d2.L1.0").text().startsWith("摘要:"));
        // 空输入
        assertEquals(0, summarizer.build("e", List.of()).nodes().size());
    }

    @Test
    void 树式上卷去重() {
        RaptorSummarizer summarizer = new RaptorSummarizer(2, String::new);
        RaptorSummarizer.RaptorTree tree = summarizer.build("doc",
                List.of("a", "b", "c", "d"));
        // 4 块每组 2：L1 两父 + L2 根。命中 L0.0 与 L0.1（同父）→ 沿父链 [L1.0, L2.0] 去重后 2 条
        List<String> rolled = RaptorSummarizer.rollUp(tree, List.of("doc.L0.0", "doc.L0.1"));
        assertEquals(2, rolled.size());
        // 命中跨父 → [L1.0, L2.0, L1.1] 去重后 3 条
        List<String> rolled2 = RaptorSummarizer.rollUp(tree, List.of("doc.L0.0", "doc.L0.2"));
        assertEquals(3, rolled2.size());
        // 未知键安全
        assertTrue(RaptorSummarizer.rollUp(tree, List.of("ghost")).isEmpty());
    }
}

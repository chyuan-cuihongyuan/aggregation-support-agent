package cn.chyuan.ai.domain.rag.service.retrieval;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 句子窗口检索单测（工单 0229 AE2）：切分/窗口边界/去重。 */
class SentenceWindowAssemblerTest {

    @Test
    void 中英分句() {
        List<String> sentences = SentenceWindowAssembler.splitSentences(
                "第一句。第二句！第三句？English. Last one! 尾部无标点");
        assertEquals(6, sentences.size());
        assertEquals("第一句。", sentences.get(0));
        assertEquals("English.", sentences.get(3));
        assertEquals("尾部无标点", sentences.get(5));
        assertTrue(SentenceWindowAssembler.splitSentences("").isEmpty());
        // 小数不误切
        assertEquals(1, SentenceWindowAssembler.splitSentences("价格是 3.5 元").size());
    }

    @Test
    void 窗口装配与边界截断() {
        SentenceWindowAssembler assembler = new SentenceWindowAssembler(1);
        List<String> split = List.of("一", "二", "三", "四", "五");
        // 命中句 0（开头越界截断）：窗口 [0,1]
        List<String> windows = assembler.assemble(split, List.of(0));
        assertEquals("一 二", windows.get(0));
        // 命中句 4（结尾越界截断）：窗口 [3,4]
        assertEquals("四 五", assembler.assemble(split, List.of(4)).get(0));
        // 命中句 2：窗口 [1,3]
        assertEquals("二 三 四", assembler.assemble(split, List.of(2)).get(0));
        // 非法下标忽略
        assertTrue(assembler.assemble(split, List.of(99)).isEmpty());
    }

    @Test
    void 多命中窗口去重() {
        SentenceWindowAssembler assembler = new SentenceWindowAssembler(2);
        List<String> split = List.of("一", "二", "三", "四", "五");
        // 命中 1 与 2：窗口 [0-3] 与 [0-4]，非完全重复都保留；完全包含的重复窗口去重
        List<String> windows = assembler.assemble(split, List.of(2, 2));
        assertEquals(1, windows.size(), "相同窗口去重");
    }

    @Test
    void 一步式装配() {
        SentenceWindowAssembler assembler = new SentenceWindowAssembler(0);
        List<String> windows = assembler.assembleFromText("甲。乙。丙。", List.of(1));
        assertEquals(List.of("乙。"), windows);
        assertEquals(0, assembler.windowSize());
    }
}

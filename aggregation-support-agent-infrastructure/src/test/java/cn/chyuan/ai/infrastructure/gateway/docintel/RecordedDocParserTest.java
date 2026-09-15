package cn.chyuan.ai.infrastructure.gateway.docintel;

import cn.chyuan.ai.domain.docintel.adapter.port.IDocParsePort;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * AV9 假解析器单测（工单 0395）：录制回放一致 + 规则合成兜底。
 */
class RecordedDocParserTest {

    @Test
    void 录制样本回放一致与合成兜底() {
        RecordedDocParser parser = new RecordedDocParser();
        // 未录制引用：规则合成单标题占位
        var synthesized = parser.parse("oss://unknown.pdf", 2);
        assertTrue(synthesized.elements().get(0).text().startsWith("[合成版面]"));
        assertEquals(2, synthesized.page());
        // 登记录制样本后回放一致
        IDocParsePort.ParseResult recorded = new IDocParsePort.ParseResult("oss://known.pdf", 1,
                List.of(new IDocParsePort.PageElement("p1", 50, 50, 500, 40, "TITLE", "标题"),
                        new IDocParsePort.PageElement("p2", 50, 100, 500, 200, "PARAGRAPH", "正文")),
                8L);
        parser.record("oss://known.pdf", 1, recorded);
        assertEquals(recorded, parser.parse("oss://known.pdf", 1));
        assertEquals(parser.parse("oss://known.pdf", 1), parser.parse("oss://known.pdf", 1));
        // 非法入参
        assertThrows(IllegalArgumentException.class, () -> parser.parse(" ", 1));
        assertThrows(IllegalArgumentException.class, () -> parser.parse("a", 0));
    }
}

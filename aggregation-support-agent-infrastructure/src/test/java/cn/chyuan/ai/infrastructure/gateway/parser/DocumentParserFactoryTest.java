package cn.chyuan.ai.infrastructure.gateway.parser;

import cn.chyuan.ai.domain.rag.adapter.port.IDocumentParser;
import cn.chyuan.ai.domain.rag.model.valobj.ParsedDocumentVO;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * DocumentParserFactory 契约测试（工单 1151/1154）：
 * 类型路由命中/未命中、默认 TXT 解析器回退、isSupported 聚合、支持格式清单。
 */
class DocumentParserFactoryTest {

    private static IDocumentParser parserMatching(boolean supports) {
        IDocumentParser parser = mock(IDocumentParser.class);
        when(parser.supports(anyString(), anyString())).thenReturn(supports);
        return parser;
    }

    @Test
    @DisplayName("getParser 返回第一个 supports 命中的解析器")
    void getParserReturnsFirstMatchingParser() {
        IDocumentParser first = parserMatching(false);
        IDocumentParser second = parserMatching(true);
        DocumentParserFactory factory = new DocumentParserFactory(List.of(first, second));

        assertThat(factory.getParser("application/pdf", "a.pdf")).isSameAs(second);
    }

    @Test
    @DisplayName("getParser 无匹配时返回 null")
    void getParserReturnsNullWhenNothingMatches() {
        DocumentParserFactory factory = new DocumentParserFactory(List.of(parserMatching(false)));

        assertThat(factory.getParser("image/png", "a.png")).isNull();
    }

    @Test
    @DisplayName("parse 命中解析器时直接委派，不触发默认回退")
    void parseDelegatesToMatchingParser() {
        IDocumentParser matching = parserMatching(true);
        ParsedDocumentVO expected = mock(ParsedDocumentVO.class);
        when(matching.parse(any(), anyString(), anyString())).thenReturn(expected);
        DocumentParserFactory factory =
                new DocumentParserFactory(List.of(parserMatching(false), matching, new TxtDocumentParser()));

        ParsedDocumentVO result = factory.parse(new byte[]{1}, "a.pdf", "application/pdf");

        assertThat(result).isSameAs(expected);
        verify(matching).parse(new byte[]{1}, "a.pdf", "application/pdf");
    }

    @Test
    @DisplayName("parse 无匹配时回退默认 TXT 解析器")
    void parseFallsBackToTxtParserWhenNothingMatches() {
        DocumentParserFactory factory = new DocumentParserFactory(List.of(parserMatching(false), new TxtDocumentParser()));

        ParsedDocumentVO result = factory.parse("hello".getBytes(), "a.bin", "application/octet-stream");

        assertThat(result).isNotNull();
        assertThat(result.getTextContent()).contains("hello");
    }

    @Test
    @DisplayName("parse 无匹配且未注册 TXT 解析器时抛出异常")
    void parseThrowsWhenNoParserAndNoTxtFallback() {
        DocumentParserFactory factory = new DocumentParserFactory(List.of(parserMatching(false)));

        assertThatThrownBy(() -> factory.parse(new byte[]{1}, "a.bin", "application/octet-stream"))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("TXT");
    }

    @Test
    @DisplayName("isSupported 任一解析器支持即为 true")
    void isSupportedAggregatesOverParsers() {
        DocumentParserFactory factory =
                new DocumentParserFactory(List.of(parserMatching(false), parserMatching(true)));

        assertThat(factory.isSupported("text/html", "a.html")).isTrue();
    }

    @Test
    @DisplayName("isSupported 全部不支持时为 false")
    void isSupportedFalseWhenNothingMatches() {
        DocumentParserFactory factory = new DocumentParserFactory(List.of(parserMatching(false)));

        assertThat(factory.isSupported("text/html", "a.html")).isFalse();
    }

    @Test
    @DisplayName("getSupportedFormats 返回固定格式清单")
    void getSupportedFormatsReturnsFixedList() {
        DocumentParserFactory factory = new DocumentParserFactory(List.of(parserMatching(false)));

        assertThat(factory.getSupportedFormats())
                .containsExactly("txt", "md", "pdf", "doc", "docx", "html", "htm", "csv", "xls", "xlsx");
    }

    @Test
    @DisplayName("未命中解析器不会被 parse 调用")
    void nonMatchingParserIsNeverInvokedOnParse() {
        IDocumentParser nonMatching = parserMatching(false);
        DocumentParserFactory factory = new DocumentParserFactory(List.of(nonMatching, new TxtDocumentParser()));

        factory.parse("x".getBytes(), "a.bin", "application/octet-stream");

        verify(nonMatching, never()).parse(any(), anyString(), anyString());
    }
}

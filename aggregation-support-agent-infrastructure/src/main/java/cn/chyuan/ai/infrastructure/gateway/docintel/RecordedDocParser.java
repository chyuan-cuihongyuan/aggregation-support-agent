package cn.chyuan.ai.infrastructure.gateway.docintel;

import cn.chyuan.ai.domain.docintel.adapter.port.IDocParsePort;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 录制合成文档解析器（工单 0395 AV9 假实现）。
 * 录制样本库（docRef+page→解析结果）回放；未录制引用按规则合成单标题占位版面。
 * docintel.enabled 默认关，开启才装配（真实 PDF/图像解析挂雾）。
 */
@Component
@ConditionalOnProperty(name = "docintel.enabled", havingValue = "true")
public class RecordedDocParser implements IDocParsePort {

    /** 录制样本库 */
    private final Map<String, ParseResult> recorded = new ConcurrentHashMap<>();

    /** 登记录制样本 */
    public void record(String docRef, int page, ParseResult result) {
        recorded.put(docRef + "#" + page, result);
    }

    @Override
    public ParseResult parse(String docRef, int page) {
        if (docRef == null || docRef.isBlank() || page < 1) {
            throw new IllegalArgumentException("文档引用不可为空且页码从 1 起");
        }
        ParseResult hit = recorded.get(docRef + "#" + page);
        if (hit != null) {
            return hit;
        }
        // 规则合成：单标题占位版面
        List<PageElement> elements = new ArrayList<>();
        elements.add(new PageElement("synthetic-title", 50, 50, 500, 40, "TITLE",
                "[合成版面] " + docRef + " 第" + page + "页"));
        return new ParseResult(docRef, page, elements, 5L);
    }
}

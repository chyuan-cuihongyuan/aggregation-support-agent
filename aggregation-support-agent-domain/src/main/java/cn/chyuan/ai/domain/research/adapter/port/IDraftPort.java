package cn.chyuan.ai.domain.research.adapter.port;

import java.util.List;

/**
 * 章节起草端口（AR5：LLM 适配 + 证据要点模板兜底）与执行摘要端口。
 */
public interface IDraftPort {

    /** 起草章节：章节标题 + 证据要点 → 章节正文（null/异常走模板兜底） */
    String draftChapter(String title, List<String> evidencePoints);

    /** 执行摘要：主题 + 章节草稿 → 摘要（null/异常走模板兜底） */
    String executiveSummary(String topic, List<String> chapterSummaries);
}

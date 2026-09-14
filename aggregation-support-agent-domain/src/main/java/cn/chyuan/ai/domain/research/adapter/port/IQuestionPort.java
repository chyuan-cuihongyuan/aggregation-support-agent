package cn.chyuan.ai.domain.research.adapter.port;

import java.util.List;

/**
 * 视角问题生成端口（AR1：LLM 适配 + 模板兜底，storm 知识游说的端口化简化）。
 */
public interface IQuestionPort {

    /** 主题 × 视角 → 问题清单（返回 null/异常由调用方走模板兜底） */
    List<String> generate(String topic, String perspective);
}

package cn.chyuan.ai.domain.research.service;

import cn.chyuan.ai.domain.research.adapter.port.IQuestionPort;
import cn.chyuan.ai.domain.research.model.valobj.OutlineVO;

import java.util.ArrayList;
import java.util.List;

/**
 * 研究大纲生成器（工单 0347 AR1，storm outline 思想）。
 * 主题 × 视角模板（可配置扩展）→ 每视角问题（端口生成，异常/null 走模板
 * 兜底句式）→ 层级大纲（章节=视角、小节=问题）。装配确定性。domain 纯函数。
 */
public class OutlineGenerator {

    private final List<String> perspectives;
    private final IQuestionPort port;

    public OutlineGenerator(List<String> perspectives, IQuestionPort port) {
        if (perspectives == null || perspectives.isEmpty()) {
            throw new IllegalArgumentException("视角模板不能为空");
        }
        this.perspectives = List.copyOf(perspectives);
        this.port = port;
    }

    public OutlineVO generate(String topic) {
        if (topic == null || topic.isBlank()) {
            throw new IllegalArgumentException("主题不能为空");
        }
        List<OutlineVO.SectionVO> sections = new ArrayList<>();
        for (String perspective : perspectives) {
            sections.add(OutlineVO.SectionVO.builder()
                    .perspective(perspective)
                    .questions(generateQuestions(topic, perspective))
                    .build());
        }
        return OutlineVO.builder().topic(topic).sections(sections).build();
    }

    private List<String> generateQuestions(String topic, String perspective) {
        if (port != null) {
            try {
                List<String> questions = port.generate(topic, perspective);
                if (questions != null && !questions.isEmpty()) {
                    return List.copyOf(questions);
                }
            } catch (RuntimeException ignored) {
                // 走模板兜底
            }
        }
        return List.of(
                topic + "在" + perspective + "维度的现状是什么？",
                topic + "在" + perspective + "维度的关键风险与机会有哪些？");
    }
}

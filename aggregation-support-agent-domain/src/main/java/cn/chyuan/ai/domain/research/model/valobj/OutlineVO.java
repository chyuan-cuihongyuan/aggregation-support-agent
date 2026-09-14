package cn.chyuan.ai.domain.research.model.valobj;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 研究大纲值对象（AR1：章节=视角、小节=问题，storm outline 思想）
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class OutlineVO {

    /** 研究主题 */
    private String topic;

    /** 章节（每视角一章） */
    private List<SectionVO> sections;

    /** 章节（视角） */
    @Data
    @Builder
    @AllArgsConstructor
    @NoArgsConstructor
    public static class SectionVO {
        /** 视角（technical/business/risk/user 等） */
        private String perspective;
        /** 本章问题清单 */
        private List<String> questions;
    }
}

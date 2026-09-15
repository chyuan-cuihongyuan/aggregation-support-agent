package cn.chyuan.ai.domain.tmemory.model.valobj;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

/**
 * 实体归并计划值对象（工单 0363 AS2）：归并组 + 成员→规范名重定向映射。
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class MergePlanVO {

    /** 归并组：规范名 + 类型 + 成员清单 + 归并理由 */
    private List<Group> groups;

    /** 成员名→规范名重定向（规范名自身映射为自己，保证 apply 幂等） */
    private Map<String, String> redirect;

    @Data
    @Builder
    @AllArgsConstructor
    @NoArgsConstructor
    public static class Group {

        /** 规范名（组内首个出现名） */
        private String canonicalName;

        /** 实体类型（类型一致才归并） */
        private String type;

        /** 组成员名（含规范名自身） */
        private List<String> members;

        /** 归并理由（alias/norm-key） */
        private String reason;
    }
}

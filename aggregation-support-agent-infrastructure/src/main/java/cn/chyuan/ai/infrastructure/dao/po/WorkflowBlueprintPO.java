package cn.chyuan.ai.infrastructure.dao.po;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.util.Date;

/**
 * 工作流蓝图模板表 PO（工单 0268 AI1，聚合库第 16 表）
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class WorkflowBlueprintPO implements Serializable {

    private Long id;

    private String name;

    private String description;

    private String category;

    /** 标签（逗号拼接） */
    private String tags;

    /** 图定义 DSL JSON（节点 config 可含 ${param} 占位） */
    private String graphJson;

    /** 参数 schema JSON（可空） */
    private String paramSchemaJson;

    private String operator;

    private Date createTime;

    private Date updateTime;
}

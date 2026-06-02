package cn.chyuan.ai.infrastructure.dao.po;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.util.Date;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class KnowledgeBasePO implements Serializable {
    private Long id;
    private String knowledgeBaseId;
    private String tenantId;
    private String ownerUserId;
    private String name;
    private String description;
    private String icon;
    private String color;
    private Integer deletedFlag;
    private Long documentCount;
    private Date createTime;
    private Date updateTime;
}

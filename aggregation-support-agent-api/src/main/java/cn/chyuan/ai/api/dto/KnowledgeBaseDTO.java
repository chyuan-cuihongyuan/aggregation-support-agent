package cn.chyuan.ai.api.dto;

import lombok.Data;

import java.util.Date;

@Data
public class KnowledgeBaseDTO {
    private String knowledgeBaseId;
    private String tenantId;
    private String ownerUserId;
    private String name;
    private String description;
    private String icon;
    private String color;
    private Long documentCount;
    private Date createTime;
    private Date updateTime;
}

package cn.chyuan.ai.api.dto;

import lombok.Data;

@Data
public class KnowledgeBaseCreateRequestDTO {
    private String name;
    private String description;
    private String icon;
    private String color;
}

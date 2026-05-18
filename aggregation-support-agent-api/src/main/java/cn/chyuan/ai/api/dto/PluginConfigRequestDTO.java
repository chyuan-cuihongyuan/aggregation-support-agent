package cn.chyuan.ai.api.dto;

import lombok.Data;

import java.util.Map;

/**
 * 插件配置请求DTO
 */
@Data
public class PluginConfigRequestDTO {
    /**
     * 最大文件大小（字节）
     */
    private Long maxFileSize;

    /**
     * 允许的文件格式
     */
    private String[] allowedFormats;

    /**
     * 是否自动向量化
     */
    private Boolean autoVectorize;

    /**
     * 是否自动刷新
     */
    private Boolean autoRefresh;

    /**
     * 刷新间隔（秒）
     */
    private Integer refreshInterval;

    /**
     * 是否自动保存
     */
    private Boolean autoSave;

    /**
     * 最大历史天数
     */
    private Integer maxHistoryDays;
}

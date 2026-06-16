package cn.chyuan.ai.domain.memory.multimodal;

import cn.chyuan.ai.domain.memory.model.valobj.MemoryOptions;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 多模态记忆项
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MultimodalMemoryItem {
    
    /**
     * 记忆类型：IMAGE / AUDIO / VIDEO / TEXT
     */
    private String modalityType;
    
    /**
     * 内容（文本描述或 URL）
     */
    private String content;
    
    /**
     * 原始数据（Base64 编码或二进制）
     */
    private byte[] rawData;
    
    /**
     * 元数据
     */
    private MultimodalMetadata metadata;
    
    /**
     * 多模态元数据
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class MultimodalMetadata {
        /**
         * 图片宽度（像素）
         */
        private Integer width;
        
        /**
         * 图片高度（像素）
         */
        private Integer height;
        
        /**
         * 音频时长（秒）
         */
        private Float duration;
        
        /**
         * 文件格式（jpg, png, mp3 等）
         */
        private String format;
        
        /**
         * 文件大小（字节）
         */
        private Long size;
        
        /**
         * 描述文本（用于检索）
         */
        private String description;
    }
}

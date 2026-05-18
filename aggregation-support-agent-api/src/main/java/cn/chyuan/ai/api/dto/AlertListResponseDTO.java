package cn.chyuan.ai.api.dto;

import lombok.Data;

import java.util.List;

/**
 * 告警列表响应DTO
 */
@Data
public class AlertListResponseDTO {
    /**
     * 告警列表
     */
    private List<AlertDTO> alerts;

    /**
     * 总数
     */
    private int total;

    /**
     * 各状态告警数量
     */
    private AlertCountDTO counts;

    @Data
    public static class AlertCountDTO {
        private int critical;
        private int warning;
        private int info;
        private int total;
    }
}

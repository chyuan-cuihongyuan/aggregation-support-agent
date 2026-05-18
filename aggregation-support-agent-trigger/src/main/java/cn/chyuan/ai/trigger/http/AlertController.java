package cn.chyuan.ai.trigger.http;

import cn.chyuan.ai.api.dto.AlertDTO;
import cn.chyuan.ai.api.dto.AlertListResponseDTO;
import cn.chyuan.ai.api.response.Response;
import cn.chyuan.ai.domain.aiops.model.entity.AlertEntity;
import cn.chyuan.ai.domain.aiops.service.IAlertService;
import cn.chyuan.ai.types.enums.ResponseCode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import jakarta.annotation.Resource;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 告警管理控制器 — 提供告警查询、过滤和管理接口
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/alerts")
@CrossOrigin(origins = "http://localhost:3000", allowCredentials = "true", allowedHeaders = "*")
public class AlertController {

    @Resource
    private IAlertService alertService;

    /**
     * 获取告警列表
     *
     * @param severity 严重程度过滤（可选）
     * @param status   状态过滤（可选）
     */
    @RequestMapping(value = "list", method = RequestMethod.GET)
    public Response<AlertListResponseDTO> getAlerts(
            @RequestParam(value = "severity", required = false) String severity,
            @RequestParam(value = "status", required = false, defaultValue = "active") String status) {
        try {
            log.info("查询告警列表: severity={}, status={}", severity, status);

            List<AlertEntity> alerts;
            if (severity != null && !severity.isEmpty() && !"all".equals(severity)) {
                alerts = alertService.getAlertsBySeverity(severity);
            } else {
                alerts = alertService.getActiveAlerts();
            }

            // 转换为DTO
            List<AlertDTO> alertDTOs = alerts.stream()
                    .map(this::toAlertDTO)
                    .collect(Collectors.toList());

            // 获取统计信息
            IAlertService.AlertStatistics stats = alertService.getStatistics();

            AlertListResponseDTO.AlertCountDTO counts = new AlertListResponseDTO.AlertCountDTO();
            counts.setCritical(stats.critical());
            counts.setWarning(stats.warning());
            counts.setInfo(stats.info());
            counts.setTotal(stats.total());

            AlertListResponseDTO response = new AlertListResponseDTO();
            response.setAlerts(alertDTOs);
            response.setTotal(alertDTOs.size());
            response.setCounts(counts);

            return Response.<AlertListResponseDTO>builder()
                    .code(ResponseCode.SUCCESS.getCode())
                    .info(ResponseCode.SUCCESS.getInfo())
                    .data(response)
                    .build();
        } catch (Exception e) {
            log.error("查询告警列表失败", e);
            return Response.<AlertListResponseDTO>builder()
                    .code(ResponseCode.UN_ERROR.getCode())
                    .info("查询告警列表失败")
                    .build();
        }
    }

    /**
     * 获取告警详情
     */
    @RequestMapping(value = "{alertId}", method = RequestMethod.GET)
    public Response<AlertDTO> getAlertDetail(@PathVariable("alertId") String alertId) {
        try {
            log.info("查询告警详情: alertId={}", alertId);
            AlertEntity alert = alertService.getAlertById(alertId);
            if (alert == null) {
                return Response.<AlertDTO>builder()
                        .code(ResponseCode.ILLEGAL_PARAMETER.getCode())
                        .info("告警不存在")
                        .build();
            }
            return Response.<AlertDTO>builder()
                    .code(ResponseCode.SUCCESS.getCode())
                    .info(ResponseCode.SUCCESS.getInfo())
                    .data(toAlertDTO(alert))
                    .build();
        } catch (Exception e) {
            log.error("查询告警详情失败: alertId={}", alertId, e);
            return Response.<AlertDTO>builder()
                    .code(ResponseCode.UN_ERROR.getCode())
                    .info("查询告警详情失败")
                    .build();
        }
    }

    /**
     * 确认告警
     */
    @RequestMapping(value = "{alertId}/acknowledge", method = RequestMethod.POST)
    public Response<Boolean> acknowledgeAlert(@PathVariable("alertId") String alertId) {
        try {
            log.info("确认告警: alertId={}", alertId);
            alertService.acknowledgeAlert(alertId);
            return Response.<Boolean>builder()
                    .code(ResponseCode.SUCCESS.getCode())
                    .info(ResponseCode.SUCCESS.getInfo())
                    .data(true)
                    .build();
        } catch (Exception e) {
            log.error("确认告警失败: alertId={}", alertId, e);
            return Response.<Boolean>builder()
                    .code(ResponseCode.UN_ERROR.getCode())
                    .info("确认告警失败")
                    .build();
        }
    }

    /**
     * 解决告警
     */
    @RequestMapping(value = "{alertId}/resolve", method = RequestMethod.POST)
    public Response<Boolean> resolveAlert(@PathVariable("alertId") String alertId) {
        try {
            log.info("解决告警: alertId={}", alertId);
            alertService.resolveAlert(alertId);
            return Response.<Boolean>builder()
                    .code(ResponseCode.SUCCESS.getCode())
                    .info(ResponseCode.SUCCESS.getInfo())
                    .data(true)
                    .build();
        } catch (Exception e) {
            log.error("解决告警失败: alertId={}", alertId, e);
            return Response.<Boolean>builder()
                    .code(ResponseCode.UN_ERROR.getCode())
                    .info("解决告警失败")
                    .build();
        }
    }

    /**
     * 创建告警（供监控系统调用）
     */
    @RequestMapping(value = "create", method = RequestMethod.POST)
    public Response<AlertDTO> createAlert(@RequestBody AlertDTO alertDTO) {
        try {
            log.info("创建告警: name={}, severity={}", alertDTO.getName(), alertDTO.getSeverity());
            AlertEntity entity = toEntity(alertDTO);
            AlertEntity created = alertService.createAlert(entity);
            return Response.<AlertDTO>builder()
                    .code(ResponseCode.SUCCESS.getCode())
                    .info(ResponseCode.SUCCESS.getInfo())
                    .data(toAlertDTO(created))
                    .build();
        } catch (Exception e) {
            log.error("创建告警失败", e);
            return Response.<AlertDTO>builder()
                    .code(ResponseCode.UN_ERROR.getCode())
                    .info("创建告警失败")
                    .build();
        }
    }

    /**
     * 批量创建告警（供监控系统批量推送）
     */
    @RequestMapping(value = "batch-create", method = RequestMethod.POST)
    public Response<Boolean> batchCreateAlerts(@RequestBody List<AlertDTO> alertDTOs) {
        try {
            log.info("批量创建告警: count={}", alertDTOs.size());
            for (AlertDTO dto : alertDTOs) {
                AlertEntity entity = toEntity(dto);
                alertService.createAlert(entity);
            }
            return Response.<Boolean>builder()
                    .code(ResponseCode.SUCCESS.getCode())
                    .info(ResponseCode.SUCCESS.getInfo())
                    .data(true)
                    .build();
        } catch (Exception e) {
            log.error("批量创建告警失败", e);
            return Response.<Boolean>builder()
                    .code(ResponseCode.UN_ERROR.getCode())
                    .info("批量创建告警失败")
                    .build();
        }
    }

    /**
     * 获取告警统计信息
     */
    @RequestMapping(value = "statistics", method = RequestMethod.GET)
    public Response<AlertListResponseDTO.AlertCountDTO> getStatistics() {
        try {
            IAlertService.AlertStatistics stats = alertService.getStatistics();
            AlertListResponseDTO.AlertCountDTO counts = new AlertListResponseDTO.AlertCountDTO();
            counts.setCritical(stats.critical());
            counts.setWarning(stats.warning());
            counts.setInfo(stats.info());
            counts.setTotal(stats.total());
            return Response.<AlertListResponseDTO.AlertCountDTO>builder()
                    .code(ResponseCode.SUCCESS.getCode())
                    .info(ResponseCode.SUCCESS.getInfo())
                    .data(counts)
                    .build();
        } catch (Exception e) {
            log.error("获取告警统计失败", e);
            return Response.<AlertListResponseDTO.AlertCountDTO>builder()
                    .code(ResponseCode.UN_ERROR.getCode())
                    .info("获取告警统计失败")
                    .build();
        }
    }

    private AlertDTO toAlertDTO(AlertEntity entity) {
        AlertDTO dto = new AlertDTO();
        dto.setId(entity.getAlertId());
        dto.setSeverity(entity.getSeverity());
        dto.setName(entity.getName());
        dto.setSummary(entity.getSummary());
        dto.setHost(entity.getHost());
        dto.setStatus(entity.getStatus());
        dto.setSource(entity.getSource());
        dto.setDescription(entity.getDescription());

        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
        dto.setTime(entity.getAlertTime() != null ? sdf.format(entity.getAlertTime()) : "");

        // 解析metrics和labels JSON
        if (entity.getMetricsJson() != null && !entity.getMetricsJson().isEmpty()) {
            try {
                com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
                @SuppressWarnings("unchecked")
                Map<String, Object> metrics = mapper.readValue(entity.getMetricsJson(), Map.class);
                dto.setMetrics(metrics);
            } catch (Exception e) {
                log.warn("解析metrics JSON失败: {}", entity.getMetricsJson());
            }
        }

        if (entity.getLabelsJson() != null && !entity.getLabelsJson().isEmpty()) {
            try {
                com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
                @SuppressWarnings("unchecked")
                Map<String, String> labels = mapper.readValue(entity.getLabelsJson(), Map.class);
                dto.setLabels(labels);
            } catch (Exception e) {
                log.warn("解析labels JSON失败: {}", entity.getLabelsJson());
            }
        }

        return dto;
    }

    private AlertEntity toEntity(AlertDTO dto) {
        AlertEntity entity = AlertEntity.builder()
                .alertId(dto.getId())
                .severity(dto.getSeverity())
                .name(dto.getName())
                .summary(dto.getSummary())
                .host(dto.getHost())
                .status(dto.getStatus() != null ? dto.getStatus() : "active")
                .source(dto.getSource())
                .description(dto.getDescription())
                .alertTime(new Date())
                .build();

        // 转换metrics和labels为JSON
        if (dto.getMetrics() != null) {
            try {
                com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
                entity.setMetricsJson(mapper.writeValueAsString(dto.getMetrics()));
            } catch (Exception e) {
                log.warn("转换metrics为JSON失败");
            }
        }

        if (dto.getLabels() != null) {
            try {
                com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
                entity.setLabelsJson(mapper.writeValueAsString(dto.getLabels()));
            } catch (Exception e) {
                log.warn("转换labels为JSON失败");
            }
        }

        return entity;
    }
}

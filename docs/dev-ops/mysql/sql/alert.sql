-- ============================================================
-- 告警表 — 存储监控系统和预警平台的告警数据
-- 对应数据库：chyuan_frame_archetype
-- ============================================================

SET NAMES utf8mb4;

CREATE TABLE IF NOT EXISTS `alert` (
  `id`            bigint unsigned NOT NULL AUTO_INCREMENT,
  `alert_id`      varchar(64)     NOT NULL COMMENT '告警唯一标识',
  `severity`      varchar(20)     NOT NULL DEFAULT 'info' COMMENT '严重程度: critical, warning, info',
  `name`          varchar(256)    NOT NULL COMMENT '告警名称',
  `summary`       varchar(512)    DEFAULT '' COMMENT '告警摘要',
  `host`          varchar(128)    DEFAULT '' COMMENT '告警来源主机',
  `status`        varchar(20)     NOT NULL DEFAULT 'active' COMMENT '状态: active, acknowledged, resolved',
  `source`        varchar(64)     DEFAULT '' COMMENT '告警来源（监控系统）',
  `metrics_json`  text            COMMENT '告警指标数据（JSON格式）',
  `labels_json`   text            COMMENT '告警标签（JSON格式）',
  `description`   text            COMMENT '告警详细描述',
  `alert_time`    datetime        NOT NULL COMMENT '告警触发时间',
  `create_time`   datetime        NOT NULL COMMENT '创建时间',
  `update_time`   datetime        NOT NULL COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_alert_id` (`alert_id`),
  KEY `idx_severity` (`severity`),
  KEY `idx_status` (`status`),
  KEY `idx_alert_time` (`alert_time`),
  KEY `idx_severity_status` (`severity`, `status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='告警记录表';

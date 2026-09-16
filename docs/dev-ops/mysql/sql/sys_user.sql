CREATE TABLE IF NOT EXISTS `sys_user` (
  `id`            bigint unsigned NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  `username`      varchar(64)     NOT NULL COMMENT '用户名',
  `password_hash` varchar(255)    NOT NULL COMMENT '密码（BCrypt加密）',
  `nickname`      varchar(64)     DEFAULT '' COMMENT '昵称',
  `email`         varchar(128)    DEFAULT '' COMMENT '邮箱',
  `avatar`        varchar(512)    DEFAULT '' COMMENT '头像URL',
  `role`          varchar(32)     NOT NULL DEFAULT 'user' COMMENT '角色：admin/user',
  `status`        tinyint         NOT NULL DEFAULT 1 COMMENT '状态：0-禁用 1-启用',
  `create_time`   datetime        NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time`   datetime        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_username` (`username`),
  KEY `idx_role` (`role`),
  KEY `idx_status` (`status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='系统用户表';

-- 默认管理员账号（密码: admin123）
INSERT INTO `sys_user` (`username`, `password_hash`, `nickname`, `role`, `status`)
VALUES ('admin', '$2a$10$N.zmdr9k7uOCQb376NoUnuTJ8iAt6Z5EHsM8lE9lBOsl7iKTVKIUi', '系统管理员', 'admin', 1);

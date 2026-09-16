-- 用户表
CREATE TABLE IF NOT EXISTS `user` (
  `id`          bigint unsigned NOT NULL AUTO_INCREMENT,
  `username`    varchar(64)     NOT NULL COMMENT '用户名',
  `password`    varchar(255)    NOT NULL COMMENT '密码（BCrypt 加密）',
  `phone`       varchar(20)     NOT NULL DEFAULT '' COMMENT '手机号',
  `email`       varchar(128)    NOT NULL DEFAULT '' COMMENT '邮箱',
  `nickname`    varchar(64)     NOT NULL DEFAULT '' COMMENT '昵称',
  `avatar`      varchar(512)    NOT NULL DEFAULT '' COMMENT '头像 URL',
  `role`        varchar(32)     NOT NULL DEFAULT 'user' COMMENT '角色：admin/user',
  `status`      tinyint         NOT NULL DEFAULT 1 COMMENT '状态：1启用/0禁用',
  `create_time` datetime        NOT NULL COMMENT '创建时间',
  `update_time` datetime        NOT NULL COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_username` (`username`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='用户表';

-- 初始化管理员账号（用户名: admin, 密码: admin）
-- BCrypt hash for 'admin'
INSERT INTO `user` (`username`, `password`, `nickname`, `role`, `status`, `create_time`, `update_time`)
VALUES ('admin', '$2a$10$N.zmdr9k7uOCQb376NoUnuTJ8iAt6Z5EHsM8lE9lBOsl7iKTVKIUi', '管理员', 'admin', 1, NOW(), NOW());


UPDATE sys_user SET password_hash='\$2a\$10\$N.zmdr9k7uOCQb376NoUnuTJ8iAt6Z5EHsM8lE9lBOsl7iKTVKIUi', update_time=NOW() WHERE username='admin';
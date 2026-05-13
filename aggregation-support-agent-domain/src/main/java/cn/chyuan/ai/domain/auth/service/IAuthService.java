package cn.chyuan.ai.domain.auth.service;

import cn.chyuan.ai.domain.auth.model.entity.UserEntity;

/**
 * 认证服务接口
 */
public interface IAuthService {

    /** 用户注册 */
    UserEntity register(String username, String password, String phone, String email, String nickname);

    /** 用户登录（验证用户名密码） */
    UserEntity login(String username, String password);

    /** 根据 ID 查询用户 */
    UserEntity queryById(Long id);
}

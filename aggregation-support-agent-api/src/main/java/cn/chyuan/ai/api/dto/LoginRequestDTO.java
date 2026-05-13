package cn.chyuan.ai.api.dto;

import lombok.Data;

/**
 * 登录请求 DTO
 */
@Data
public class LoginRequestDTO {

    /** 用户名 */
    private String username;

    /** 密码 */
    private String password;
}

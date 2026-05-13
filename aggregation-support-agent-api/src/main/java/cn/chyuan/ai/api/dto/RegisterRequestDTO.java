package cn.chyuan.ai.api.dto;

import lombok.Data;

/**
 * 注册请求 DTO
 */
@Data
public class RegisterRequestDTO {

    /** 用户名 */
    private String username;

    /** 密码 */
    private String password;

    /** 手机号 */
    private String phone;

    /** 邮箱 */
    private String email;

    /** 昵称 */
    private String nickname;
}

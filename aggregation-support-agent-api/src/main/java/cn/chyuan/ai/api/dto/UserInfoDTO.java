package cn.chyuan.ai.api.dto;

import lombok.Data;

/**
 * 用户信息 DTO
 */
@Data
public class UserInfoDTO {

    /** 用户 ID */
    private Long id;

    /** 用户名 */
    private String username;

    /** 昵称 */
    private String nickname;

    /** 邮箱 */
    private String email;

    /** 头像 */
    private String avatar;

    /** 角色：admin/user */
    private String role;

    /** 状态：1启用/0禁用 */
    private Integer status;

    /** 创建时间 */
    private String createTime;
}

package cn.chyuan.ai.domain.auth.model.entity;
import lombok.Data;

@Data
public class UserEntity {
    private Long id;
    private String username;
    private String passwordHash;
    private String nickname;
    private String email;
    private String avatar;
    private String role;
    private Integer status;
    private String createTime;
    private String updateTime;
}

package cn.chyuan.ai.api.dto;
import lombok.Data;

@Data
public class UserInfoDTO {
    private Long id;
    private String username;
    private String nickname;
    private String email;
    private String avatar;
    private String role;
    private Integer status;
    private String createTime;
}

package cn.chyuan.ai.api.dto;
import lombok.Data;

@Data
public class RegisterRequestDTO {
    private String username;
    private String password;
    private String email;
    private String nickname;
}

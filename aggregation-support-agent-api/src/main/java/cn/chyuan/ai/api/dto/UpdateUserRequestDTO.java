package cn.chyuan.ai.api.dto;
import lombok.Data;

@Data
public class UpdateUserRequestDTO {
    private String nickname;
    private String email;
    private String avatar;
}

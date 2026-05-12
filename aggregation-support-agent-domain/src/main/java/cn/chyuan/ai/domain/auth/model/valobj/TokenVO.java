package cn.chyuan.ai.domain.auth.model.valobj;
import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class TokenVO {
    private String token;
    private long expireAt;
}

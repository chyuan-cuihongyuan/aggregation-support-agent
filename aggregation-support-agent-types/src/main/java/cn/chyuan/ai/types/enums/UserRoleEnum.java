package cn.chyuan.ai.types.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public enum UserRoleEnum {

    ADMIN("admin", "管理员"),
    USER("user", "普通用户");

    private final String code;
    private final String desc;
}

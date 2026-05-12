package cn.chyuan.ai.types.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public enum UserStatusEnum {

    DISABLED(0, "禁用"),
    ENABLED(1, "启用");

    private final Integer code;
    private final String desc;
}

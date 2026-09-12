package cn.chyuan.ai.types.enums;

import lombok.Getter;

@Getter
public enum ResponseCode {

    SUCCESS("0000", "成功"),
    UN_ERROR("0001", "未知失败"),
    ILLEGAL_PARAMETER("0002", "非法参数"),
    NOT_FOUND_METHOD("0003", "不存在的方法"),
    // SELFLOOP2 loop-220：HTTP 语义错误码（405/415 精确映射，与 mcp 仓对齐）
    METHOD_NOT_SUPPORTED("0007", "HTTP 方法不支持"),
    MEDIA_TYPE_NOT_SUPPORTED("0008", "媒体类型不支持"),

    E0001("E0001", "智能体ID不存在"),
    E0002("E0002", "智能体MCP配置不在可加载范围"),

    // 认证相关错误码
    AUTH_FAIL("A0001", "用户名或密码错误"),
    AUTH_USERNAME_EXISTS("A0002", "用户名已存在"),
    AUTH_USER_DISABLED("A0003", "用户已被禁用"),
    AUTH_TOKEN_INVALID("A0004", "Token无效或已过期"),
    AUTH_PERMISSION_DENIED("A0005", "权限不足"),
    AUTH_PARAM_INVALID("A0006", "参数校验失败"),

    E1001("E1001", "用户名已存在"),
    E1002("E1002", "用户名或密码错误"),
    E1003("E1003", "Token 无效或已过期"),
    E1004("E1004", "账号已被禁用"),

    ;

    private final String code;
    private final String info;

    ResponseCode(String code, String info) {
        this.code = code;
        this.info = info;
    }

}

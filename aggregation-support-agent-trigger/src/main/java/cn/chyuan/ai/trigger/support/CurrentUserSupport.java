package cn.chyuan.ai.trigger.support;

import cn.chyuan.ai.trigger.filter.JwtAuthFilter;
import cn.chyuan.ai.types.enums.ResponseCode;
import cn.chyuan.ai.types.exception.AppException;
import jakarta.servlet.http.HttpServletRequest;

public final class CurrentUserSupport {

    private CurrentUserSupport() {
    }

    public static Long requireUserId(HttpServletRequest request) {
        Object userId = request.getAttribute(JwtAuthFilter.ATTR_USER_ID);
        if (userId instanceof Long value) {
            return value;
        }
        throw new AppException(ResponseCode.AUTH_TOKEN_INVALID.getCode(), ResponseCode.AUTH_TOKEN_INVALID.getInfo());
    }

    public static String requireUserIdString(HttpServletRequest request) {
        return String.valueOf(requireUserId(request));
    }
}

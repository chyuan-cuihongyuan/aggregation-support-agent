package cn.chyuan.ai.trigger.aspect;

import cn.chyuan.ai.trigger.annotation.RequireRole;
import cn.chyuan.ai.trigger.filter.JwtAuthFilter;
import cn.chyuan.ai.types.exception.AppException;
import jakarta.servlet.http.HttpServletRequest;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

@Aspect
@Component
public class RoleCheckAspect {

    @Around("@annotation(requireRole)")
    public Object checkRole(ProceedingJoinPoint joinPoint, RequireRole requireRole) throws Throwable {
        ServletRequestAttributes attributes =
                (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
        if (attributes == null) {
            throw new AppException("A0005", "权限不足");
        }

        HttpServletRequest request = attributes.getRequest();
        String userRole = (String) request.getAttribute(JwtAuthFilter.ATTR_ROLE);

        if (userRole == null || !userRole.equals(requireRole.value())) {
            throw new AppException("A0005", "权限不足");
        }

        return joinPoint.proceed();
    }
}

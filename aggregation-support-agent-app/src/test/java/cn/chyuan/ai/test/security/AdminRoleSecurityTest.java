package cn.chyuan.ai.test.security;

import cn.chyuan.ai.trigger.annotation.RequireRole;
import cn.chyuan.ai.trigger.aspect.RoleCheckAspect;
import cn.chyuan.ai.trigger.filter.JwtAuthFilter;
import cn.chyuan.ai.types.enums.ResponseCode;
import cn.chyuan.ai.types.exception.AppException;
import org.aspectj.lang.ProceedingJoinPoint;
import org.junit.After;
import org.junit.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.fail;

public class AdminRoleSecurityTest {

    private final RoleCheckAspect aspect = new RoleCheckAspect();

    @After
    public void tearDown() {
        RequestContextHolder.resetRequestAttributes();
    }

    @Test
    public void checkRole_rejectsNormalUserForAdminEndpoint() throws Throwable {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/user/list");
        request.setAttribute(JwtAuthFilter.ATTR_ROLE, "user");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
        RecordingProceedingJoinPoint joinPoint = new RecordingProceedingJoinPoint(new Object());

        try {
            aspect.checkRole(joinPoint.proxy(), adminOnlyAnnotation());
            fail("普通用户访问管理员接口应被拒绝");
        } catch (AppException e) {
            assertEquals(ResponseCode.AUTH_PERMISSION_DENIED.getCode(), e.getCode());
            assertEquals(ResponseCode.AUTH_PERMISSION_DENIED.getInfo(), e.getInfo());
        }
        assertEquals(0, joinPoint.proceedCalls);
    }

    @Test
    public void checkRole_allowsAdminEndpointForAdminUser() throws Throwable {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/user/list");
        request.setAttribute(JwtAuthFilter.ATTR_ROLE, "admin");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
        Object expected = new Object();
        RecordingProceedingJoinPoint joinPoint = new RecordingProceedingJoinPoint(expected);

        Object actual = aspect.checkRole(joinPoint.proxy(), adminOnlyAnnotation());

        assertSame(expected, actual);
        assertEquals(1, joinPoint.proceedCalls);
    }

    private RequireRole adminOnlyAnnotation() throws NoSuchMethodException {
        Method method = AnnotatedEndpoints.class.getDeclaredMethod("adminOnly");
        return method.getAnnotation(RequireRole.class);
    }

    private static class AnnotatedEndpoints {
        @RequireRole("admin")
        void adminOnly() {
        }
    }

    private static class RecordingProceedingJoinPoint implements InvocationHandler {
        private final Object result;
        private int proceedCalls;

        private RecordingProceedingJoinPoint(Object result) {
            this.result = result;
        }

        private ProceedingJoinPoint proxy() {
            return (ProceedingJoinPoint) Proxy.newProxyInstance(
                    ProceedingJoinPoint.class.getClassLoader(),
                    new Class[]{ProceedingJoinPoint.class},
                    this);
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) {
            if ("proceed".equals(method.getName())) {
                proceedCalls++;
                return result;
            }
            if ("toString".equals(method.getName())) {
                return "RecordingProceedingJoinPoint";
            }
            if ("getArgs".equals(method.getName())) {
                return new Object[0];
            }
            return null;
        }
    }
}

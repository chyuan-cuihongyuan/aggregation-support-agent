package cn.chyuan.ai.trigger.aspect;

import cn.chyuan.ai.trigger.annotation.RequireRole;
import cn.chyuan.ai.trigger.filter.JwtAuthFilter;
import cn.chyuan.ai.types.exception.AppException;
import org.aspectj.lang.ProceedingJoinPoint;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * RoleCheckAspect 权限切面契约测试（工单 1138）：
 * @RequireRole 注解方法在角色匹配时放行、不匹配/缺失/无请求上下文时抛 A0005。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = org.mockito.quality.Strictness.LENIENT)
@DisplayName("RoleCheckAspect 权限切面契约")
class RoleCheckAspectTest {

    @Mock
    private ProceedingJoinPoint joinPoint;

    private RoleCheckAspect aspect;

    @BeforeEach
    void setUp() {
        aspect = new RoleCheckAspect();
    }

    @AfterEach
    void tearDown() {
        RequestContextHolder.resetRequestAttributes();
    }

    private RequireRole requireRoleOf(String value) {
        RequireRole annotation = mock(RequireRole.class);
        when(annotation.value()).thenReturn(value);
        return annotation;
    }

    private void bindRequestWithRole(String role) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        if (role != null) {
            request.setAttribute(JwtAuthFilter.ATTR_ROLE, role);
        }
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
    }

    @Test
    @DisplayName("角色匹配时放行并返回原方法结果")
    void matchingRoleProceeds() throws Throwable {
        bindRequestWithRole("admin");
        when(joinPoint.proceed()).thenReturn("result");

        Object out = aspect.checkRole(joinPoint, requireRoleOf("admin"));

        assertThat(out).isEqualTo("result");
        verify(joinPoint).proceed();
    }

    @Test
    @DisplayName("角色不匹配时抛 A0005 且不执行原方法")
    void mismatchedRoleThrows() throws Throwable {
        bindRequestWithRole("user");

        assertThatThrownBy(() -> aspect.checkRole(joinPoint, requireRoleOf("admin")))
                .isInstanceOfSatisfying(AppException.class,
                        e -> assertThat(e.getCode()).isEqualTo("A0005"));
        verify(joinPoint, never()).proceed();
    }

    @Test
    @DisplayName("请求缺少角色属性时抛 A0005")
    void missingRoleAttributeThrows() throws Throwable {
        bindRequestWithRole(null);

        assertThatThrownBy(() -> aspect.checkRole(joinPoint, requireRoleOf("admin")))
                .isInstanceOf(AppException.class);
        verify(joinPoint, never()).proceed();
    }

    @Test
    @DisplayName("无请求上下文（非 Web 线程）时抛 A0005")
    void noRequestContextThrows() throws Throwable {
        RequestContextHolder.resetRequestAttributes();

        assertThatThrownBy(() -> aspect.checkRole(joinPoint, requireRoleOf("admin")))
                .isInstanceOf(AppException.class);
        verify(joinPoint, never()).proceed();
    }
}

package cn.chyuan.ai.test.trigger.http;

import cn.chyuan.ai.api.dto.LoginRequestDTO;
import cn.chyuan.ai.api.dto.RegisterRequestDTO;
import cn.chyuan.ai.api.dto.UserInfoDTO;
import cn.chyuan.ai.api.response.Response;
import cn.chyuan.ai.domain.audit.service.IAuditLogService;
import cn.chyuan.ai.domain.auth.model.entity.UserEntity;
import cn.chyuan.ai.domain.auth.service.IAuthService;
import cn.chyuan.ai.domain.auth.service.ITokenService;
import cn.chyuan.ai.trigger.filter.JwtAuthFilter;
import cn.chyuan.ai.trigger.http.AuthController;
import cn.chyuan.ai.types.enums.ResponseCode;
import cn.chyuan.ai.types.exception.AppException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockCookie;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.Date;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 认证控制器单元测试
 * <p>
 * 测试场景：
 * 1. 登录成功
 * 2. 登录失败（用户名或密码错误）
 * 3. 登录异常（系统错误）
 * 4. 注册成功
 * 5. 注册失败（用户名已存在）
 * 6. 登出成功
 * 7. Token 无效场景
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("认证控制器测试")
public class AuthControllerTest {

    @Mock
    private IAuthService authService;

    @Mock
    private ITokenService tokenService;

    @Mock
    private IAuditLogService auditLogService;

    @InjectMocks
    private AuthController authController;

    private MockHttpServletRequest request;
    private MockHttpServletResponse response;

    @BeforeEach
    void setUp() {
        request = new MockHttpServletRequest();
        response = new MockHttpServletResponse();
    }

    // ==================== 登录测试 ====================

    @Test
    @DisplayName("登录成功 — 返回用户信息和成功响应码")
    public void testLogin_Success() {
        // 准备
        LoginRequestDTO loginDTO = new LoginRequestDTO();
        loginDTO.setUsername("testuser");
        loginDTO.setPassword("password123");

        UserEntity userEntity = buildMockUserEntity(1L, "testuser", "admin");
        when(authService.login("testuser", "password123")).thenReturn(userEntity);
        when(tokenService.generateToken(1L, "testuser", "admin")).thenReturn("mock-jwt-token");

        // 执行
        Response<UserInfoDTO> result = authController.login(loginDTO, request, response);

        // 验证
        assertEquals(ResponseCode.SUCCESS.getCode(), result.getCode(), "响应码应为 0000");
        assertNotNull(result.getData(), "用户信息不应为 null");
        assertEquals("testuser", result.getData().getUsername(), "用户名应一致");
        assertEquals("admin", result.getData().getRole(), "角色应一致");
        verify(authService, times(1)).login("testuser", "password123");
        verify(tokenService, times(1)).generateToken(1L, "testuser", "admin");
    }

    @Test
    @DisplayName("登录失败 — 用户名或密码错误时返回认证失败响应码")
    public void testLogin_WrongPassword() {
        // 准备
        LoginRequestDTO loginDTO = new LoginRequestDTO();
        loginDTO.setUsername("testuser");
        loginDTO.setPassword("wrongpassword");

        when(authService.login("testuser", "wrongpassword"))
                .thenThrow(new AppException(ResponseCode.E1002.getCode(), ResponseCode.E1002.getInfo()));

        // 执行
        Response<UserInfoDTO> result = authController.login(loginDTO, request, response);

        // 验证
        assertEquals(ResponseCode.E1002.getCode(), result.getCode(), "响应码应为 E1002");
        assertEquals(ResponseCode.E1002.getInfo(), result.getInfo(), "响应信息应为用户名或密码错误");
        assertNull(result.getData(), "失败时数据应为 null");
    }

    @Test
    @DisplayName("登录异常 — 系统异常时返回未知失败响应码")
    public void testLogin_SystemException() {
        // 准备
        LoginRequestDTO loginDTO = new LoginRequestDTO();
        loginDTO.setUsername("testuser");
        loginDTO.setPassword("password123");

        when(authService.login("testuser", "password123"))
                .thenThrow(new RuntimeException("数据库连接异常"));

        // 执行
        Response<UserInfoDTO> result = authController.login(loginDTO, request, response);

        // 验证
        assertEquals(ResponseCode.UN_ERROR.getCode(), result.getCode(), "响应码应为 0001");
        assertEquals("登录失败", result.getInfo(), "响应信息应为登录失败");
    }

    @Test
    @DisplayName("登录成功 — 验证 Cookie 被正确设置")
    public void testLogin_Success_SetsAuthCookie() {
        // 准备
        LoginRequestDTO loginDTO = new LoginRequestDTO();
        loginDTO.setUsername("testuser");
        loginDTO.setPassword("password123");

        UserEntity userEntity = buildMockUserEntity(1L, "testuser", "user");
        when(authService.login("testuser", "password123")).thenReturn(userEntity);
        when(tokenService.generateToken(1L, "testuser", "user")).thenReturn("mock-jwt-token");

        // 执行
        authController.login(loginDTO, request, response);

        // 验证 — Cookie 通过 Set-Cookie Header 设置
        String setCookieHeader = response.getHeader("Set-Cookie");
        assertNotNull(setCookieHeader, "Set-Cookie Header 不应为 null");
        assertTrue(setCookieHeader.contains("auth_token=mock-jwt-token"), "Cookie 应包含 auth_token");
        assertTrue(setCookieHeader.contains("HttpOnly"), "Cookie 应设置 HttpOnly");
        assertTrue(setCookieHeader.contains("Secure"), "Cookie 应设置 Secure");
        assertTrue(setCookieHeader.contains("SameSite=Strict"), "Cookie 应设置 SameSite=Strict");
    }

    // ==================== 注册测试 ====================

    @Test
    @DisplayName("注册成功 — 返回用户信息和成功响应码")
    public void testRegister_Success() {
        // 准备
        RegisterRequestDTO registerDTO = new RegisterRequestDTO();
        registerDTO.setUsername("newuser");
        registerDTO.setPassword("password123");
        registerDTO.setPhone("13800138000");
        registerDTO.setEmail("newuser@test.com");
        registerDTO.setNickname("新用户");

        UserEntity userEntity = buildMockUserEntity(2L, "newuser", "user");
        when(authService.register("newuser", "password123", "13800138000",
                "newuser@test.com", "新用户")).thenReturn(userEntity);
        when(tokenService.generateToken(2L, "newuser", "user")).thenReturn("mock-jwt-token-new");

        // 执行
        Response<UserInfoDTO> result = authController.register(registerDTO, request, response);

        // 验证
        assertEquals(ResponseCode.SUCCESS.getCode(), result.getCode(), "响应码应为 0000");
        assertNotNull(result.getData(), "用户信息不应为 null");
        assertEquals("newuser", result.getData().getUsername(), "用户名应一致");
        verify(authService, times(1)).register("newuser", "password123", "13800138000",
                "newuser@test.com", "新用户");
    }

    @Test
    @DisplayName("注册失败 — 用户名已存在时返回错误响应码")
    public void testRegister_UsernameExists() {
        // 准备
        RegisterRequestDTO registerDTO = new RegisterRequestDTO();
        registerDTO.setUsername("existinguser");
        registerDTO.setPassword("password123");

        when(authService.register(eq("existinguser"), eq("password123"), any(), any(), any()))
                .thenThrow(new AppException(ResponseCode.AUTH_USERNAME_EXISTS.getCode(),
                        ResponseCode.AUTH_USERNAME_EXISTS.getInfo()));

        // 执行
        Response<UserInfoDTO> result = authController.register(registerDTO, request, response);

        // 验证
        assertEquals(ResponseCode.AUTH_USERNAME_EXISTS.getCode(), result.getCode(), "响应码应为 A0002");
        assertEquals(ResponseCode.AUTH_USERNAME_EXISTS.getInfo(), result.getInfo(), "响应信息应为用户名已存在");
        assertNull(result.getData(), "失败时数据应为 null");
    }

    @Test
    @DisplayName("注册异常 — 系统异常时返回未知失败响应码")
    public void testRegister_SystemException() {
        // 准备
        RegisterRequestDTO registerDTO = new RegisterRequestDTO();
        registerDTO.setUsername("newuser");
        registerDTO.setPassword("password123");

        when(authService.register(any(), any(), any(), any(), any()))
                .thenThrow(new RuntimeException("数据库连接异常"));

        // 执行
        Response<UserInfoDTO> result = authController.register(registerDTO, request, response);

        // 验证
        assertEquals(ResponseCode.UN_ERROR.getCode(), result.getCode(), "响应码应为 0001");
        assertEquals("注册失败", result.getInfo(), "响应信息应为注册失败");
    }

    // ==================== 登出测试 ====================

    @Test
    @DisplayName("登出成功 — 返回成功响应码并清除 Cookie")
    public void testLogout_Success() {
        // 准备 — 模拟已认证用户
        request.setAttribute(JwtAuthFilter.ATTR_AUTH_TOKEN, "valid-jwt-token");
        request.setAttribute(JwtAuthFilter.ATTR_USER_ID, 1L);
        request.setAttribute(JwtAuthFilter.ATTR_USERNAME, "testuser");

        // 执行
        Response<Boolean> result = authController.logout(request, response);

        // 验证
        assertEquals(ResponseCode.SUCCESS.getCode(), result.getCode(), "响应码应为 0000");
        assertTrue(result.getData(), "登出结果应为 true");
        verify(tokenService, times(1)).removeToken("valid-jwt-token");
    }

    @Test
    @DisplayName("登出 — 无 Token 时仍然返回成功")
    public void testLogout_NoToken() {
        // 准备 — 不设置任何 token
        request.setAttribute(JwtAuthFilter.ATTR_AUTH_TOKEN, null);

        // 执行
        Response<Boolean> result = authController.logout(request, response);

        // 验证
        assertEquals(ResponseCode.SUCCESS.getCode(), result.getCode(), "无 Token 登出仍应返回成功");
        assertTrue(result.getData(), "登出结果应为 true");
        verify(tokenService, never()).removeToken(anyString());
    }

    @Test
    @DisplayName("登出 — 空 Token 时不清除 Token")
    public void testLogout_EmptyToken() {
        // 准备
        request.setAttribute(JwtAuthFilter.ATTR_AUTH_TOKEN, "");

        // 执行
        Response<Boolean> result = authController.logout(request, response);

        // 验证
        assertEquals(ResponseCode.SUCCESS.getCode(), result.getCode(), "空 Token 登出应返回成功");
        verify(tokenService, never()).removeToken(anyString());
    }

    @Test
    @DisplayName("登录成功 — 用户信息 DTO 转换正确")
    public void testLogin_UserInfoDTOConversion() {
        // 准备
        LoginRequestDTO loginDTO = new LoginRequestDTO();
        loginDTO.setUsername("testuser");
        loginDTO.setPassword("password123");

        UserEntity userEntity = UserEntity.builder()
                .id(100L)
                .username("testuser")
                .nickname("测试用户")
                .email("test@example.com")
                .avatar("https://avatar.example.com/test.png")
                .role("admin")
                .status(1)
                .createTime(new Date())
                .build();

        when(authService.login("testuser", "password123")).thenReturn(userEntity);
        when(tokenService.generateToken(100L, "testuser", "admin")).thenReturn("token");

        // 执行
        Response<UserInfoDTO> result = authController.login(loginDTO, request, response);

        // 验证
        UserInfoDTO userInfo = result.getData();
        assertEquals(100L, userInfo.getId(), "用户 ID 应正确转换");
        assertEquals("testuser", userInfo.getUsername(), "用户名应正确转换");
        assertEquals("测试用户", userInfo.getNickname(), "昵称应正确转换");
        assertEquals("test@example.com", userInfo.getEmail(), "邮箱应正确转换");
        assertEquals("admin", userInfo.getRole(), "角色应正确转换");
        assertEquals(1, userInfo.getStatus(), "状态应正确转换");
        assertNotNull(userInfo.getCreateTime(), "创建时间应正确格式化");
    }

    // ==================== 辅助方法 ====================

    private UserEntity buildMockUserEntity(Long id, String username, String role) {
        return UserEntity.builder()
                .id(id)
                .username(username)
                .role(role)
                .status(1)
                .createTime(new Date())
                .build();
    }
}

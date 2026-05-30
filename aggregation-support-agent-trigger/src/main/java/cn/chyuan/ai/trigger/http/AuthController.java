package cn.chyuan.ai.trigger.http;

import cn.chyuan.ai.api.dto.LoginRequestDTO;
import cn.chyuan.ai.api.dto.RegisterRequestDTO;
import cn.chyuan.ai.api.dto.UserInfoDTO;
import cn.chyuan.ai.api.response.Response;
import cn.chyuan.ai.domain.audit.service.IAuditLogService;
import cn.chyuan.ai.domain.auth.model.entity.UserEntity;
import cn.chyuan.ai.domain.auth.service.IAuthService;
import cn.chyuan.ai.domain.auth.service.ITokenService;
import cn.chyuan.ai.trigger.filter.JwtAuthFilter;
import cn.chyuan.ai.trigger.support.AuditContextSupport;
import cn.chyuan.ai.types.enums.AuditAction;
import cn.chyuan.ai.types.enums.AuditResult;
import cn.chyuan.ai.types.enums.ResponseCode;
import cn.chyuan.ai.types.exception.AppException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.text.SimpleDateFormat;

/**
 * 认证控制器 — 登录、注册、登出
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    @Resource
    private IAuthService authService;

    @Resource
    private ITokenService tokenService;

    @Resource
    private IAuditLogService auditLogService;

    private static final String COOKIE_NAME = "auth_token";
    private static final int COOKIE_MAX_AGE = 24 * 60 * 60;

    /**
     * 用户登录
     */
    @RequestMapping(value = "login", method = RequestMethod.POST)
    public Response<UserInfoDTO> login(@RequestBody LoginRequestDTO requestDTO,
                                       HttpServletRequest request,
                                       HttpServletResponse response) {
        String ip = AuditContextSupport.extractIp(request);
        String ua = AuditContextSupport.extractUserAgent(request);
        try {
            UserEntity user = authService.login(requestDTO.getUsername(), requestDTO.getPassword());
            String token = tokenService.generateToken(user.getId(), user.getUsername(), user.getRole());
            setAuthCookie(response, token);

            // 登录成功审计
            safeAudit(user.getId(), user.getUsername(), AuditAction.LOGIN,
                    "USER", String.valueOf(user.getId()), AuditResult.SUCCESS, "", ip, ua);

            return Response.<UserInfoDTO>builder()
                    .code(ResponseCode.SUCCESS.getCode())
                    .info(ResponseCode.SUCCESS.getInfo())
                    .data(toUserInfoDTO(user))
                    .build();
        } catch (AppException e) {
            log.warn("登录失败: {} - {}", requestDTO.getUsername(), e.getInfo());
            // 登录失败审计
            safeAudit(0L, requestDTO.getUsername(), AuditAction.LOGIN,
                    "USER", "", AuditResult.FAILURE, e.getInfo(), ip, ua);
            return Response.<UserInfoDTO>builder()
                    .code(e.getCode())
                    .info(e.getInfo())
                    .build();
        } catch (Exception e) {
            log.error("登录异常: {}", requestDTO.getUsername(), e);
            // 登录异常审计
            safeAudit(0L, requestDTO.getUsername(), AuditAction.LOGIN,
                    "USER", "", AuditResult.FAILURE, "登录异常: " + e.getMessage(), ip, ua);
            return Response.<UserInfoDTO>builder()
                    .code(ResponseCode.UN_ERROR.getCode())
                    .info("登录失败")
                    .build();
        }
    }

    /**
     * 用户注册
     */
    @RequestMapping(value = "register", method = RequestMethod.POST)
    public Response<UserInfoDTO> register(@RequestBody RegisterRequestDTO requestDTO,
                                          HttpServletRequest request,
                                          HttpServletResponse response) {
        String ip = AuditContextSupport.extractIp(request);
        String ua = AuditContextSupport.extractUserAgent(request);
        try {
            UserEntity user = authService.register(
                    requestDTO.getUsername(),
                    requestDTO.getPassword(),
                    requestDTO.getPhone(),
                    requestDTO.getEmail(),
                    requestDTO.getNickname()
            );

            String token = tokenService.generateToken(user.getId(), user.getUsername(), user.getRole());
            setAuthCookie(response, token);

            // 注册成功审计
            safeAudit(user.getId(), user.getUsername(), AuditAction.REGISTER,
                    "USER", String.valueOf(user.getId()), AuditResult.SUCCESS, "", ip, ua);

            return Response.<UserInfoDTO>builder()
                    .code(ResponseCode.SUCCESS.getCode())
                    .info(ResponseCode.SUCCESS.getInfo())
                    .data(toUserInfoDTO(user))
                    .build();
        } catch (AppException e) {
            log.warn("注册失败: {} - {}", requestDTO.getUsername(), e.getInfo());
            // 注册失败审计
            safeAudit(0L, requestDTO.getUsername(), AuditAction.REGISTER,
                    "USER", "", AuditResult.FAILURE, e.getInfo(), ip, ua);
            return Response.<UserInfoDTO>builder()
                    .code(e.getCode())
                    .info(e.getInfo())
                    .build();
        } catch (Exception e) {
            log.error("注册异常: {}", requestDTO.getUsername(), e);
            // 注册异常审计
            safeAudit(0L, requestDTO.getUsername(), AuditAction.REGISTER,
                    "USER", "", AuditResult.FAILURE, "注册异常: " + e.getMessage(), ip, ua);
            return Response.<UserInfoDTO>builder()
                    .code(ResponseCode.UN_ERROR.getCode())
                    .info("注册失败")
                    .build();
        }
    }

    /**
     * 用户登出
     */
    @RequestMapping(value = "logout", method = RequestMethod.POST)
    public Response<Boolean> logout(HttpServletRequest request, HttpServletResponse response) {
        String token = (String) request.getAttribute(JwtAuthFilter.ATTR_AUTH_TOKEN);
        if (token != null && !token.isEmpty()) {
            tokenService.removeToken(token);
        }

        response.setHeader("Set-Cookie", String.format("%s=; Path=/; Max-Age=0; HttpOnly; Secure; SameSite=Strict", COOKIE_NAME));

        // 登出审计 — userId/username 从 request attr 读取，失败兜底
        Object uidAttr = request.getAttribute(JwtAuthFilter.ATTR_USER_ID);
        Object unameAttr = request.getAttribute(JwtAuthFilter.ATTR_USERNAME);
        Long uid = (uidAttr instanceof Long) ? (Long) uidAttr : 0L;
        String uname = (unameAttr instanceof String) ? (String) unameAttr : "";
        safeAudit(uid, uname, AuditAction.LOGOUT,
                "USER", String.valueOf(uid), AuditResult.SUCCESS, "",
                AuditContextSupport.extractIp(request),
                AuditContextSupport.extractUserAgent(request));

        return Response.<Boolean>builder()
                .code(ResponseCode.SUCCESS.getCode())
                .info(ResponseCode.SUCCESS.getInfo())
                .data(true)
                .build();
    }

    /**
     * 审计写入兜底 — service 异步实现内部已 try-catch，这里再兜一层防 NPE
     */
    private void safeAudit(Long userId, String username, AuditAction action,
                           String resourceType, String resourceId, AuditResult result,
                           String detail, String ip, String ua) {
        try {
            if (auditLogService != null) {
                auditLogService.recordAsync(userId, username, action,
                        resourceType, resourceId, result, "", detail, ip, ua);
            }
        } catch (Exception ex) {
            log.warn("审计调用失败：action={}, err={}", action, ex.getMessage());
        }
    }

    private void setAuthCookie(HttpServletResponse response, String token) {
        response.setHeader("Set-Cookie", String.format("%s=%s; Path=/; Max-Age=%d; HttpOnly; Secure; SameSite=Strict",
                COOKIE_NAME, token, COOKIE_MAX_AGE));
    }

    private UserInfoDTO toUserInfoDTO(UserEntity entity) {
        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
        UserInfoDTO dto = new UserInfoDTO();
        dto.setId(entity.getId());
        dto.setUsername(entity.getUsername());
        dto.setNickname(entity.getNickname());
        dto.setEmail(entity.getEmail());
        dto.setAvatar(entity.getAvatar());
        dto.setRole(entity.getRole());
        dto.setStatus(entity.getStatus());
        dto.setCreateTime(entity.getCreateTime() != null ? sdf.format(entity.getCreateTime()) : "");
        return dto;
    }
}

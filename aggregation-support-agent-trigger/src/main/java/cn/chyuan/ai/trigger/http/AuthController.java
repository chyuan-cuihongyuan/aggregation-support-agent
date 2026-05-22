package cn.chyuan.ai.trigger.http;

import cn.chyuan.ai.api.dto.LoginRequestDTO;
import cn.chyuan.ai.api.dto.RegisterRequestDTO;
import cn.chyuan.ai.api.dto.UserInfoDTO;
import cn.chyuan.ai.api.response.Response;
import cn.chyuan.ai.domain.auth.model.entity.UserEntity;
import cn.chyuan.ai.domain.auth.service.IAuthService;
import cn.chyuan.ai.domain.auth.service.ITokenService;
import cn.chyuan.ai.types.enums.ResponseCode;
import cn.chyuan.ai.types.exception.AppException;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import jakarta.annotation.Resource;
import jakarta.servlet.http.Cookie;
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

    private static final String COOKIE_NAME = "auth_token";
    private static final int COOKIE_MAX_AGE = 24 * 60 * 60;

    /**
     * 用户登录
     */
    @RequestMapping(value = "login", method = RequestMethod.POST)
    public Response<UserInfoDTO> login(@Valid @RequestBody LoginRequestDTO requestDTO, HttpServletResponse response) {
        try {
            UserEntity user = authService.login(requestDTO.getUsername(), requestDTO.getPassword());
            String token = tokenService.generateToken(user.getId(), user.getUsername());
            setAuthCookie(response, token);

            return Response.<UserInfoDTO>builder()
                    .code(ResponseCode.SUCCESS.getCode())
                    .info(ResponseCode.SUCCESS.getInfo())
                    .data(toUserInfoDTO(user))
                    .build();
        } catch (AppException e) {
            log.warn("登录失败: {} - {}", requestDTO.getUsername(), e.getInfo());
            return Response.<UserInfoDTO>builder()
                    .code(e.getCode())
                    .info(e.getInfo())
                    .build();
        } catch (Exception e) {
            log.error("登录异常: {}", requestDTO.getUsername(), e);
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
    public Response<UserInfoDTO> register(@Valid @RequestBody RegisterRequestDTO requestDTO, HttpServletResponse response) {
        try {
            UserEntity user = authService.register(
                    requestDTO.getUsername(),
                    requestDTO.getPassword(),
                    requestDTO.getPhone(),
                    requestDTO.getEmail(),
                    requestDTO.getNickname()
            );

            String token = tokenService.generateToken(user.getId(), user.getUsername());
            setAuthCookie(response, token);

            return Response.<UserInfoDTO>builder()
                    .code(ResponseCode.SUCCESS.getCode())
                    .info(ResponseCode.SUCCESS.getInfo())
                    .data(toUserInfoDTO(user))
                    .build();
        } catch (AppException e) {
            log.warn("注册失败: {} - {}", requestDTO.getUsername(), e.getInfo());
            return Response.<UserInfoDTO>builder()
                    .code(e.getCode())
                    .info(e.getInfo())
                    .build();
        } catch (Exception e) {
            log.error("注册异常: {}", requestDTO.getUsername(), e);
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
    public Response<Boolean> logout(HttpServletResponse response) {
        Cookie cookie = new Cookie(COOKIE_NAME, "");
        cookie.setPath("/");
        cookie.setMaxAge(0);
        cookie.setHttpOnly(true);
        response.addCookie(cookie);

        return Response.<Boolean>builder()
                .code(ResponseCode.SUCCESS.getCode())
                .info(ResponseCode.SUCCESS.getInfo())
                .data(true)
                .build();
    }

    private void setAuthCookie(HttpServletResponse response, String token) {
        Cookie cookie = new Cookie(COOKIE_NAME, token);
        cookie.setPath("/");
        cookie.setMaxAge(COOKIE_MAX_AGE);
        cookie.setHttpOnly(true);
        cookie.setSecure(true);
        // 使用 Response Header 设置 SameSite 属性（Servlet API 不直接支持）
        response.addCookie(cookie);
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

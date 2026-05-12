package cn.chyuan.ai.trigger.http;

import cn.chyuan.ai.api.dto.LoginRequestDTO;
import cn.chyuan.ai.api.dto.RegisterRequestDTO;
import cn.chyuan.ai.api.dto.UserInfoDTO;
import cn.chyuan.ai.api.response.Response;
import cn.chyuan.ai.domain.auth.model.entity.UserEntity;
import cn.chyuan.ai.domain.auth.model.valobj.TokenVO;
import cn.chyuan.ai.domain.auth.service.TokenService;
import cn.chyuan.ai.domain.auth.service.UserService;
import cn.chyuan.ai.types.enums.ResponseCode;
import cn.chyuan.ai.types.exception.AppException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.*;

@Slf4j
@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    @Value("${auth.cookie.name:auth_token}")
    private String cookieName;

    @Value("${auth.cookie.max-age:604800}")
    private int cookieMaxAge;

    private final UserService userService;
    private final TokenService tokenService;

    public AuthController(UserService userService, TokenService tokenService) {
        this.userService = userService;
        this.tokenService = tokenService;
    }

    @PostMapping("/register")
    public Response<UserInfoDTO> register(@RequestBody RegisterRequestDTO request) {
        try {
            UserEntity user = userService.register(
                    request.getUsername(), request.getPassword(),
                    request.getEmail(), request.getNickname());
            return Response.<UserInfoDTO>builder()
                    .code(ResponseCode.SUCCESS.getCode())
                    .info(ResponseCode.SUCCESS.getInfo())
                    .data(toUserInfoDTO(user))
                    .build();
        } catch (AppException e) {
            return Response.<UserInfoDTO>builder()
                    .code(e.getCode())
                    .info(e.getMessage())
                    .build();
        }
    }

    @PostMapping("/login")
    public Response<UserInfoDTO> login(@RequestBody LoginRequestDTO request, HttpServletResponse response) {
        try {
            UserEntity user = userService.login(request.getUsername(), request.getPassword());
            TokenVO tokenVO = tokenService.generateToken(user.getId(), user.getUsername(), user.getRole());
            setCookie(response, tokenVO.getToken());
            return Response.<UserInfoDTO>builder()
                    .code(ResponseCode.SUCCESS.getCode())
                    .info(ResponseCode.SUCCESS.getInfo())
                    .data(toUserInfoDTO(user))
                    .build();
        } catch (AppException e) {
            return Response.<UserInfoDTO>builder()
                    .code(e.getCode())
                    .info(e.getMessage())
                    .build();
        }
    }

    @PostMapping("/logout")
    public Response<Boolean> logout(HttpServletRequest request, HttpServletResponse response) {
        try {
            String token = extractToken(request);
            if (token != null) {
                tokenService.removeToken(token);
            }
            clearCookie(response);
            return Response.<Boolean>builder()
                    .code(ResponseCode.SUCCESS.getCode())
                    .info(ResponseCode.SUCCESS.getInfo())
                    .data(true)
                    .build();
        } catch (Exception e) {
            log.error("登出失败", e);
            return Response.<Boolean>builder()
                    .code(ResponseCode.UN_ERROR.getCode())
                    .info(ResponseCode.UN_ERROR.getInfo())
                    .build();
        }
    }

    @PostMapping("/refresh")
    public Response<Boolean> refresh(HttpServletRequest request, HttpServletResponse response) {
        try {
            String token = extractToken(request);
            if (token == null) {
                return Response.<Boolean>builder()
                        .code("A0004").info("Token无效").build();
            }
            TokenVO newToken = tokenService.refreshToken(token);
            if (newToken == null) {
                return Response.<Boolean>builder()
                        .code("A0004").info("Token刷新失败").build();
            }
            setCookie(response, newToken.getToken());
            return Response.<Boolean>builder()
                    .code(ResponseCode.SUCCESS.getCode())
                    .info(ResponseCode.SUCCESS.getInfo())
                    .data(true)
                    .build();
        } catch (Exception e) {
            log.error("Token刷新失败", e);
            return Response.<Boolean>builder()
                    .code(ResponseCode.UN_ERROR.getCode())
                    .info(ResponseCode.UN_ERROR.getInfo())
                    .build();
        }
    }

    private void setCookie(HttpServletResponse response, String token) {
        Cookie cookie = new Cookie(cookieName, token);
        cookie.setPath("/");
        cookie.setHttpOnly(true);
        cookie.setMaxAge(cookieMaxAge);
        response.addCookie(cookie);
    }

    private void clearCookie(HttpServletResponse response) {
        Cookie cookie = new Cookie(cookieName, "");
        cookie.setPath("/");
        cookie.setMaxAge(0);
        response.addCookie(cookie);
    }

    private String extractToken(HttpServletRequest request) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) return null;
        for (Cookie cookie : cookies) {
            if (cookieName.equals(cookie.getName())) {
                return cookie.getValue();
            }
        }
        return null;
    }

    private UserInfoDTO toUserInfoDTO(UserEntity user) {
        UserInfoDTO dto = new UserInfoDTO();
        dto.setId(user.getId());
        dto.setUsername(user.getUsername());
        dto.setNickname(user.getNickname());
        dto.setEmail(user.getEmail());
        dto.setAvatar(user.getAvatar());
        dto.setRole(user.getRole());
        dto.setStatus(user.getStatus());
        dto.setCreateTime(user.getCreateTime());
        return dto;
    }
}

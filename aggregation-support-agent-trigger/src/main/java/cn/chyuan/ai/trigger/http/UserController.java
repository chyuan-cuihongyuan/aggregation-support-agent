package cn.chyuan.ai.trigger.http;

import cn.chyuan.ai.api.dto.UserInfoDTO;
import cn.chyuan.ai.api.response.Response;
import cn.chyuan.ai.domain.auth.adapter.repository.IUserRepository;
import cn.chyuan.ai.domain.auth.model.entity.UserEntity;
import cn.chyuan.ai.domain.auth.service.ITokenService;
import cn.chyuan.ai.types.enums.ResponseCode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import jakarta.annotation.Resource;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import java.text.SimpleDateFormat;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 用户管理控制器 — 用户信息查询、用户列表、状态和角色管理
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/user")
@CrossOrigin(origins = "*")
public class UserController {

    @Resource
    private IUserRepository userRepository;

    @Resource
    private ITokenService tokenService;

    private static final String COOKIE_NAME = "auth_token";

    /**
     * 获取当前登录用户信息（从 Cookie 中的 Token 解析）
     */
    @RequestMapping(value = "info", method = RequestMethod.GET)
    public Response<UserInfoDTO> getUserInfo(HttpServletRequest request) {
        try {
            String token = getCookieValue(request, COOKIE_NAME);
            if (token == null || token.isEmpty()) {
                return Response.<UserInfoDTO>builder()
                        .code(ResponseCode.E1003.getCode())
                        .info("未登录")
                        .build();
            }

            if (!tokenService.validateToken(token)) {
                return Response.<UserInfoDTO>builder()
                        .code(ResponseCode.E1003.getCode())
                        .info("登录已过期")
                        .build();
            }

            Long userId = tokenService.getUserIdFromToken(token);
            UserEntity user = userRepository.queryById(userId);
            if (user == null) {
                return Response.<UserInfoDTO>builder()
                        .code(ResponseCode.E1003.getCode())
                        .info("用户不存在")
                        .build();
            }

            return Response.<UserInfoDTO>builder()
                    .code(ResponseCode.SUCCESS.getCode())
                    .info(ResponseCode.SUCCESS.getInfo())
                    .data(toUserInfoDTO(user))
                    .build();
        } catch (Exception e) {
            log.error("获取用户信息失败", e);
            return Response.<UserInfoDTO>builder()
                    .code(ResponseCode.UN_ERROR.getCode())
                    .info("获取用户信息失败")
                    .build();
        }
    }

    /**
     * 查询用户列表（分页）
     */
    @RequestMapping(value = "list", method = RequestMethod.GET)
    public Response<Map<String, Object>> listUsers(
            @RequestParam(value = "page", defaultValue = "1") int page,
            @RequestParam(value = "pageSize", defaultValue = "20") int pageSize) {
        try {
            List<UserEntity> entities = userRepository.queryList(page, pageSize);
            int total = userRepository.countAll();

            List<UserInfoDTO> dtoList = entities.stream().map(this::toUserInfoDTO).collect(Collectors.toList());

            Map<String, Object> data = new HashMap<>();
            data.put("list", dtoList);
            data.put("total", total);
            data.put("page", page);
            data.put("pageSize", pageSize);

            return Response.<Map<String, Object>>builder()
                    .code(ResponseCode.SUCCESS.getCode())
                    .info(ResponseCode.SUCCESS.getInfo())
                    .data(data)
                    .build();
        } catch (Exception e) {
            log.error("查询用户列表失败", e);
            return Response.<Map<String, Object>>builder()
                    .code(ResponseCode.UN_ERROR.getCode())
                    .info("查询失败")
                    .build();
        }
    }

    /**
     * 更新用户状态
     */
    @RequestMapping(value = "{userId}/status", method = RequestMethod.PUT)
    public Response<Boolean> updateStatus(
            @PathVariable("userId") Long userId,
            @RequestBody Map<String, Integer> body) {
        try {
            Integer status = body.get("status");
            userRepository.updateStatus(userId, status);
            log.info("更新用户状态: userId={}, status={}", userId, status);
            return Response.<Boolean>builder()
                    .code(ResponseCode.SUCCESS.getCode())
                    .info(ResponseCode.SUCCESS.getInfo())
                    .data(true)
                    .build();
        } catch (Exception e) {
            log.error("更新用户状态失败: userId={}", userId, e);
            return Response.<Boolean>builder()
                    .code(ResponseCode.UN_ERROR.getCode())
                    .info("操作失败")
                    .build();
        }
    }

    /**
     * 更新用户角色
     */
    @RequestMapping(value = "{userId}/role", method = RequestMethod.PUT)
    public Response<Boolean> updateRole(
            @PathVariable("userId") Long userId,
            @RequestBody Map<String, String> body) {
        try {
            String role = body.get("role");
            userRepository.updateRole(userId, role);
            log.info("更新用户角色: userId={}, role={}", userId, role);
            return Response.<Boolean>builder()
                    .code(ResponseCode.SUCCESS.getCode())
                    .info(ResponseCode.SUCCESS.getInfo())
                    .data(true)
                    .build();
        } catch (Exception e) {
            log.error("更新用户角色失败: userId={}", userId, e);
            return Response.<Boolean>builder()
                    .code(ResponseCode.UN_ERROR.getCode())
                    .info("操作失败")
                    .build();
        }
    }

    private String getCookieValue(HttpServletRequest request, String name) {
        Cookie[] cookies = request.getCookies();
        if (cookies != null) {
            for (Cookie cookie : cookies) {
                if (name.equals(cookie.getName())) {
                    return cookie.getValue();
                }
            }
        }
        return null;
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

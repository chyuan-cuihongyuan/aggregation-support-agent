package cn.chyuan.ai.trigger.http;

import cn.chyuan.ai.api.dto.ChangePasswordRequestDTO;
import cn.chyuan.ai.api.dto.UpdateRoleRequestDTO;
import cn.chyuan.ai.api.dto.UpdateStatusRequestDTO;
import cn.chyuan.ai.api.dto.UpdateUserRequestDTO;
import cn.chyuan.ai.api.dto.UserInfoDTO;
import cn.chyuan.ai.api.response.Response;
import cn.chyuan.ai.domain.audit.service.IAuditLogService;
import cn.chyuan.ai.domain.auth.adapter.repository.IUserRepository;
import cn.chyuan.ai.domain.auth.model.entity.UserEntity;
import cn.chyuan.ai.domain.auth.service.IAuthService;
import cn.chyuan.ai.trigger.annotation.RequireRole;
import cn.chyuan.ai.trigger.filter.JwtAuthFilter;
import cn.chyuan.ai.trigger.support.AuditContextSupport;
import cn.chyuan.ai.trigger.support.CurrentUserSupport;
import cn.chyuan.ai.types.enums.AuditAction;
import cn.chyuan.ai.types.enums.AuditResult;
import cn.chyuan.ai.types.enums.ResponseCode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import jakarta.annotation.Resource;
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
public class UserController {

    @Resource
    private IUserRepository userRepository;

    @Resource
    private IAuthService authService;

    @Resource
    private IAuditLogService auditLogService;

    /**
     * 获取当前登录用户信息（从 Cookie 中的 Token 解析）
     */
    @RequestMapping(value = "info", method = RequestMethod.GET)
    public Response<UserInfoDTO> getUserInfo(HttpServletRequest request) {
        try {
            Long userId = CurrentUserSupport.requireUserId(request);
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
    @RequireRole("admin")
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
     * 更新用户信息
     */
    @RequestMapping(value = "update", method = RequestMethod.PUT)
    public Response<Boolean> updateUserInfo(HttpServletRequest request, @RequestBody UpdateUserRequestDTO updateDTO) {
        try {
            Long userId = CurrentUserSupport.requireUserId(request);
            UserEntity user = userRepository.queryById(userId);
            if (user == null) {
                return Response.<Boolean>builder()
                        .code(ResponseCode.E1003.getCode())
                        .info("用户不存在")
                        .build();
            }

            // 更新用户信息
            if (updateDTO.getNickname() != null) {
                user.setNickname(updateDTO.getNickname());
            }
            if (updateDTO.getEmail() != null) {
                user.setEmail(updateDTO.getEmail());
            }
            if (updateDTO.getAvatar() != null) {
                user.setAvatar(updateDTO.getAvatar());
            }
            userRepository.updateUser(user);

            log.info("更新用户信息: userId={}", userId);
            return Response.<Boolean>builder()
                    .code(ResponseCode.SUCCESS.getCode())
                    .info(ResponseCode.SUCCESS.getInfo())
                    .data(true)
                    .build();
        } catch (Exception e) {
            log.error("更新用户信息失败", e);
            return Response.<Boolean>builder()
                    .code(ResponseCode.UN_ERROR.getCode())
                    .info("更新失败")
                    .build();
        }
    }

    /**
     * 修改密码
     */
    @RequestMapping(value = "change-password", method = RequestMethod.POST)
    public Response<Boolean> changePassword(HttpServletRequest request, @RequestBody ChangePasswordRequestDTO body) {
        try {
            Long userId = CurrentUserSupport.requireUserId(request);
            authService.changePassword(userId, body.getOldPassword(), body.getNewPassword());

            return Response.<Boolean>builder()
                    .code(ResponseCode.SUCCESS.getCode())
                    .info(ResponseCode.SUCCESS.getInfo())
                    .data(true)
                    .build();
        } catch (cn.chyuan.ai.types.exception.AppException e) {
            log.warn("修改密码失败: {}", e.getInfo());
            return Response.<Boolean>builder()
                    .code(e.getCode())
                    .info(e.getInfo())
                    .build();
        } catch (Exception e) {
            log.error("修改密码异常", e);
            return Response.<Boolean>builder()
                    .code(ResponseCode.UN_ERROR.getCode())
                    .info("修改密码失败")
                    .build();
        }
    }

    /**
     * 更新用户状态（仅管理员可操作）
     */
    @RequestMapping(value = "{userId}/status", method = RequestMethod.PUT)
    @RequireRole("admin")
    public Response<Boolean> updateStatus(HttpServletRequest request,
                                          @PathVariable("userId") Long userId,
                                          @RequestBody UpdateStatusRequestDTO body) {
        String ip = AuditContextSupport.extractIp(request);
        String ua = AuditContextSupport.extractUserAgent(request);
        // 操作者审计字段
        Object opUidAttr = request.getAttribute(JwtAuthFilter.ATTR_USER_ID);
        Object opUnameAttr = request.getAttribute(JwtAuthFilter.ATTR_USERNAME);
        Long opUserId = (opUidAttr instanceof Long) ? (Long) opUidAttr : 0L;
        String opUsername = (opUnameAttr instanceof String) ? (String) opUnameAttr : "";
        try {
            userRepository.updateStatus(userId, body.getStatus());
            log.info("更新用户状态: userId={}, status={}", userId, body.getStatus());
            // 修改状态成功审计
            safeAuditUser(opUserId, opUsername, AuditAction.CHANGE_STATUS,
                    String.valueOf(userId), AuditResult.SUCCESS,
                    "目标状态=" + body.getStatus(), ip, ua);
            return Response.<Boolean>builder()
                    .code(ResponseCode.SUCCESS.getCode())
                    .info(ResponseCode.SUCCESS.getInfo())
                    .data(true)
                    .build();
        } catch (Exception e) {
            log.error("更新用户状态失败: userId={}", userId, e);
            safeAuditUser(opUserId, opUsername, AuditAction.CHANGE_STATUS,
                    String.valueOf(userId), AuditResult.FAILURE,
                    "操作失败: " + e.getMessage(), ip, ua);
            return Response.<Boolean>builder()
                    .code(ResponseCode.UN_ERROR.getCode())
                    .info("操作失败")
                    .build();
        }
    }

    /**
     * 更新用户角色（仅管理员可操作）
     */
    @RequestMapping(value = "{userId}/role", method = RequestMethod.PUT)
    @RequireRole("admin")
    public Response<Boolean> updateRole(HttpServletRequest request,
                                        @PathVariable("userId") Long userId,
                                        @RequestBody UpdateRoleRequestDTO body) {
        String ip = AuditContextSupport.extractIp(request);
        String ua = AuditContextSupport.extractUserAgent(request);
        // 操作者审计字段
        Object opUidAttr = request.getAttribute(JwtAuthFilter.ATTR_USER_ID);
        Object opUnameAttr = request.getAttribute(JwtAuthFilter.ATTR_USERNAME);
        Long opUserId = (opUidAttr instanceof Long) ? (Long) opUidAttr : 0L;
        String opUsername = (opUnameAttr instanceof String) ? (String) opUnameAttr : "";
        try {
            userRepository.updateRole(userId, body.getRole());
            log.info("更新用户角色: userId={}, role={}", userId, body.getRole());
            // 修改角色成功审计
            safeAuditUser(opUserId, opUsername, AuditAction.CHANGE_ROLE,
                    String.valueOf(userId), AuditResult.SUCCESS,
                    "目标角色=" + body.getRole(), ip, ua);
            return Response.<Boolean>builder()
                    .code(ResponseCode.SUCCESS.getCode())
                    .info(ResponseCode.SUCCESS.getInfo())
                    .data(true)
                    .build();
        } catch (Exception e) {
            log.error("更新用户角色失败: userId={}", userId, e);
            safeAuditUser(opUserId, opUsername, AuditAction.CHANGE_ROLE,
                    String.valueOf(userId), AuditResult.FAILURE,
                    "操作失败: " + e.getMessage(), ip, ua);
            return Response.<Boolean>builder()
                    .code(ResponseCode.UN_ERROR.getCode())
                    .info("操作失败")
                    .build();
        }
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

    /**
     * 用户管理审计兜底 — 用于 CHANGE_ROLE / CHANGE_STATUS
     */
    private void safeAuditUser(Long opUserId, String opUsername, AuditAction action,
                               String targetUserId, AuditResult result,
                               String detail, String ip, String ua) {
        try {
            if (auditLogService != null) {
                auditLogService.recordAsync(opUserId, opUsername, action,
                        "USER", targetUserId == null ? "" : targetUserId,
                        result, "", detail, ip, ua);
            }
        } catch (Exception ex) {
            log.warn("审计调用失败：action={}, err={}", action, ex.getMessage());
        }
    }
}

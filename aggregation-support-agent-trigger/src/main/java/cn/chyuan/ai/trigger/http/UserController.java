package cn.chyuan.ai.trigger.http;

import cn.chyuan.ai.api.dto.*;
import cn.chyuan.ai.api.response.Response;
import cn.chyuan.ai.domain.auth.model.entity.UserEntity;
import cn.chyuan.ai.domain.auth.service.UserService;
import cn.chyuan.ai.trigger.annotation.RequireRole;
import cn.chyuan.ai.types.enums.ResponseCode;
import cn.chyuan.ai.types.exception.AppException;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Slf4j
@RestController
@RequestMapping("/api/v1/user")
public class UserController {

    private final UserService userService;

    public UserController(UserService userService) {
        this.userService = userService;
    }

    @GetMapping("/info")
    public Response<UserInfoDTO> getCurrentUser(HttpServletRequest request) {
        try {
            Long userId = (Long) request.getAttribute("userId");
            UserEntity user = userService.getUserById(userId);
            if (user == null) {
                return Response.<UserInfoDTO>builder()
                        .code("A0006").info("用户不存在").build();
            }
            return Response.<UserInfoDTO>builder()
                    .code(ResponseCode.SUCCESS.getCode())
                    .info(ResponseCode.SUCCESS.getInfo())
                    .data(toDTO(user))
                    .build();
        } catch (Exception e) {
            log.error("获取用户信息失败", e);
            return Response.<UserInfoDTO>builder()
                    .code(ResponseCode.UN_ERROR.getCode())
                    .info(ResponseCode.UN_ERROR.getInfo())
                    .build();
        }
    }

    @PutMapping("/info")
    public Response<Boolean> updateUserInfo(@RequestBody UpdateUserRequestDTO requestDTO,
                                            HttpServletRequest request) {
        try {
            Long userId = (Long) request.getAttribute("userId");
            userService.updateUserInfo(userId, requestDTO.getNickname(),
                    requestDTO.getEmail(), requestDTO.getAvatar());
            return Response.<Boolean>builder()
                    .code(ResponseCode.SUCCESS.getCode())
                    .info(ResponseCode.SUCCESS.getInfo())
                    .data(true)
                    .build();
        } catch (AppException e) {
            return Response.<Boolean>builder().code(e.getCode()).info(e.getMessage()).build();
        }
    }

    @PutMapping("/password")
    public Response<Boolean> changePassword(@RequestBody ChangePasswordRequestDTO requestDTO,
                                            HttpServletRequest request) {
        try {
            Long userId = (Long) request.getAttribute("userId");
            userService.changePassword(userId, requestDTO.getOldPassword(), requestDTO.getNewPassword());
            return Response.<Boolean>builder()
                    .code(ResponseCode.SUCCESS.getCode())
                    .info(ResponseCode.SUCCESS.getInfo())
                    .data(true)
                    .build();
        } catch (AppException e) {
            return Response.<Boolean>builder().code(e.getCode()).info(e.getMessage()).build();
        }
    }

    @GetMapping("/list")
    @RequireRole("admin")
    public Response<Map<String, Object>> listUsers(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int pageSize) {
        try {
            List<UserEntity> users = userService.listUsers(page, pageSize);
            int total = userService.countUsers();
            Map<String, Object> data = new HashMap<>();
            data.put("list", users.stream().map(this::toDTO).collect(Collectors.toList()));
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
                    .info(ResponseCode.UN_ERROR.getInfo())
                    .build();
        }
    }

    @PutMapping("/{id}/status")
    @RequireRole("admin")
    public Response<Boolean> updateStatus(@PathVariable Long id,
                                          @RequestBody UpdateStatusRequestDTO requestDTO) {
        try {
            userService.updateStatus(id, requestDTO.getStatus());
            return Response.<Boolean>builder()
                    .code(ResponseCode.SUCCESS.getCode())
                    .info(ResponseCode.SUCCESS.getInfo())
                    .data(true)
                    .build();
        } catch (AppException e) {
            return Response.<Boolean>builder().code(e.getCode()).info(e.getMessage()).build();
        }
    }

    @PutMapping("/{id}/role")
    @RequireRole("admin")
    public Response<Boolean> updateRole(@PathVariable Long id,
                                        @RequestBody UpdateRoleRequestDTO requestDTO) {
        try {
            userService.updateRole(id, requestDTO.getRole());
            return Response.<Boolean>builder()
                    .code(ResponseCode.SUCCESS.getCode())
                    .info(ResponseCode.SUCCESS.getInfo())
                    .data(true)
                    .build();
        } catch (AppException e) {
            return Response.<Boolean>builder().code(e.getCode()).info(e.getMessage()).build();
        }
    }

    private UserInfoDTO toDTO(UserEntity user) {
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

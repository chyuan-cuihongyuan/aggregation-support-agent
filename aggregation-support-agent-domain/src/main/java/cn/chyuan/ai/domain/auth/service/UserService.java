package cn.chyuan.ai.domain.auth.service;

import cn.chyuan.ai.domain.auth.adapter.port.IPasswordEncoder;
import cn.chyuan.ai.domain.auth.adapter.repository.IUserRepository;
import cn.chyuan.ai.domain.auth.model.entity.UserEntity;
import cn.chyuan.ai.types.enums.UserRoleEnum;
import cn.chyuan.ai.types.enums.UserStatusEnum;
import cn.chyuan.ai.types.exception.AppException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

@Slf4j
@Service
public class UserService {

    private final IUserRepository userRepository;
    private final IPasswordEncoder passwordEncoder;

    public UserService(IUserRepository userRepository, IPasswordEncoder passwordEncoder) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
    }

    public UserEntity register(String username, String password, String email, String nickname) {
        validateUsername(username);
        validatePassword(password);

        UserEntity existing = userRepository.queryUserByUsername(username);
        if (existing != null) {
            throw new AppException("A0002", "用户名已存在");
        }

        UserEntity user = new UserEntity();
        user.setUsername(username);
        user.setPasswordHash(passwordEncoder.encode(password));
        user.setEmail(email != null ? email : "");
        user.setNickname(nickname != null ? nickname : username);
        user.setRole(UserRoleEnum.USER.getCode());
        user.setStatus(UserStatusEnum.ENABLED.getCode());

        userRepository.insertUser(user);
        log.info("用户注册成功: username={}", username);
        return user;
    }

    public UserEntity login(String username, String password) {
        UserEntity user = userRepository.queryUserByUsername(username);
        if (user == null || !passwordEncoder.matches(password, user.getPasswordHash())) {
            throw new AppException("A0001", "用户名或密码错误");
        }
        if (UserStatusEnum.DISABLED.getCode().equals(user.getStatus())) {
            throw new AppException("A0003", "用户已被禁用");
        }
        return user;
    }

    public UserEntity getUserById(Long id) {
        return userRepository.queryUserById(id);
    }

    public void updateUserInfo(Long id, String nickname, String email, String avatar) {
        UserEntity user = userRepository.queryUserById(id);
        if (user == null) {
            throw new AppException("A0006", "用户不存在");
        }
        if (nickname != null) user.setNickname(nickname);
        if (email != null) user.setEmail(email);
        if (avatar != null) user.setAvatar(avatar);
        userRepository.updateUser(user);
    }

    public void changePassword(Long id, String oldPassword, String newPassword) {
        validatePassword(newPassword);
        UserEntity user = userRepository.queryUserById(id);
        if (user == null) {
            throw new AppException("A0006", "用户不存在");
        }
        if (!passwordEncoder.matches(oldPassword, user.getPasswordHash())) {
            throw new AppException("A0001", "原密码错误");
        }
        user.setPasswordHash(passwordEncoder.encode(newPassword));
        userRepository.updateUser(user);
    }

    public List<UserEntity> listUsers(int page, int pageSize) {
        int offset = (page - 1) * pageSize;
        return userRepository.queryUserList(offset, pageSize);
    }

    public int countUsers() {
        return userRepository.countUsers();
    }

    public void updateStatus(Long id, Integer status) {
        UserEntity user = userRepository.queryUserById(id);
        if (user == null) {
            throw new AppException("A0006", "用户不存在");
        }
        user.setStatus(status);
        userRepository.updateUser(user);
    }

    public void updateRole(Long id, String role) {
        UserEntity user = userRepository.queryUserById(id);
        if (user == null) {
            throw new AppException("A0006", "用户不存在");
        }
        user.setRole(role);
        userRepository.updateUser(user);
    }

    private void validateUsername(String username) {
        if (username == null || username.length() < 4 || username.length() > 32
                || !username.matches("^[a-zA-Z0-9_]+$")) {
            throw new AppException("A0006", "用户名须为4-32位字母数字下划线");
        }
    }

    private void validatePassword(String password) {
        if (password == null || password.length() < 6 || password.length() > 64) {
            throw new AppException("A0006", "密码须为6-64位");
        }
    }
}

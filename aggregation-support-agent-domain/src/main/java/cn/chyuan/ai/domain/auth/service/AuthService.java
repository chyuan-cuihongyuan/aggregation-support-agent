package cn.chyuan.ai.domain.auth.service;

import cn.chyuan.ai.domain.auth.adapter.repository.IUserRepository;
import cn.chyuan.ai.domain.auth.model.entity.UserEntity;
import cn.chyuan.ai.types.enums.ResponseCode;
import cn.chyuan.ai.types.exception.AppException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import jakarta.annotation.Resource;

/**
 * 认证服务实现
 */
@Slf4j
@Service
public class AuthService implements IAuthService {

    @Resource
    private IUserRepository userRepository;

    private final BCryptPasswordEncoder passwordEncoder = new BCryptPasswordEncoder();

    @Override
    @Transactional(rollbackFor = Exception.class)
    public UserEntity register(String username, String password, String phone, String email, String nickname) {
        UserEntity existing = userRepository.queryByUsername(username);
        if (existing != null) {
            throw new AppException(ResponseCode.E1001.getCode(), ResponseCode.E1001.getInfo());
        }

        String encodedPassword = passwordEncoder.encode(password);

        UserEntity entity = UserEntity.builder()
                .username(username)
                .password(encodedPassword)
                .phone(phone != null ? phone : "")
                .email(email != null ? email : "")
                .nickname(nickname != null && !nickname.isEmpty() ? nickname : username)
                .role("user")
                .build();

        userRepository.save(entity);
        log.info("用户注册成功: {}", username);

        return userRepository.queryByUsername(username);
    }

    @Override
    public UserEntity login(String username, String password) {
        UserEntity user = userRepository.queryByUsername(username);
        if (user == null) {
            throw new AppException(ResponseCode.E1002.getCode(), ResponseCode.E1002.getInfo());
        }

        if (user.getStatus() != null && user.getStatus() == 0) {
            throw new AppException(ResponseCode.E1004.getCode(), ResponseCode.E1004.getInfo());
        }

        if (!passwordEncoder.matches(password, user.getPassword())) {
            throw new AppException(ResponseCode.E1002.getCode(), ResponseCode.E1002.getInfo());
        }

        log.info("用户登录成功: {}", username);
        return user;
    }

    @Override
    public UserEntity queryById(Long id) {
        return userRepository.queryById(id);
    }

    @Override
    public void changePassword(Long userId, String oldPassword, String newPassword) {
        UserEntity user = userRepository.queryById(userId);
        if (user == null) {
            throw new AppException(ResponseCode.E1003.getCode(), "用户不存在");
        }

        if (!passwordEncoder.matches(oldPassword, user.getPassword())) {
            throw new AppException(ResponseCode.E1002.getCode(), "原密码错误");
        }

        user.setPassword(passwordEncoder.encode(newPassword));
        userRepository.updateUser(user);
        log.info("用户密码修改成功: userId={}", userId);
    }
}

package cn.chyuan.ai.domain.auth.service;

import cn.chyuan.ai.domain.auth.adapter.repository.IUserRepository;
import cn.chyuan.ai.domain.auth.model.entity.UserEntity;
import cn.chyuan.ai.types.enums.ResponseCode;
import cn.chyuan.ai.types.exception.AppException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AuthServiceTest {

    @Mock
    private IUserRepository userRepository;

    @InjectMocks
    private AuthService authService;

    private BCryptPasswordEncoder passwordEncoder;

    @BeforeEach
    void setUp() {
        passwordEncoder = new BCryptPasswordEncoder();
    }

    @Test
    void testRegister_Success() {
        // Given
        String username = "testuser";
        String password = "password123";
        String phone = "13800138000";
        String email = "test@example.com";
        String nickname = "Test User";

        when(userRepository.queryByUsername(eq(username))).thenReturn(null);
        when(userRepository.save(any(UserEntity.class))).thenAnswer(invocation -> {
            UserEntity entity = invocation.getArgument(0);
            entity.setId(1L);
            return entity;
        });
        when(userRepository.queryByUsername(eq(username))).thenReturn(
                UserEntity.builder()
                        .id(1L)
                        .username(username)
                        .password(passwordEncoder.encode(password))
                        .phone(phone)
                        .email(email)
                        .nickname(nickname)
                        .role("user")
                        .build()
        );

        // When
        UserEntity result = authService.register(username, password, phone, email, nickname);

        // Then
        assertNotNull(result);
        assertEquals(username, result.getUsername());
        assertEquals("user", result.getRole());
        verify(userRepository).save(any(UserEntity.class));
    }

    @Test
    void testRegister_UsernameAlreadyExists() {
        // Given
        String username = "existinguser";
        String password = "password123";

        when(userRepository.queryByUsername(eq(username))).thenReturn(
                UserEntity.builder().id(1L).username(username).build()
        );

        // When & Then
        AppException exception = assertThrows(AppException.class, () -> {
            authService.register(username, password, null, null, null);
        });

        assertEquals(ResponseCode.E1001.getCode(), exception.getCode());
        verify(userRepository, never()).save(any());
    }

    @Test
    void testRegister_WithDefaultValues() {
        // Given
        String username = "testuser";
        String password = "password123";

        when(userRepository.queryByUsername(eq(username))).thenReturn(null);
        when(userRepository.save(any(UserEntity.class))).thenAnswer(invocation -> {
            UserEntity entity = invocation.getArgument(0);
            entity.setId(1L);
            return entity;
        });
        when(userRepository.queryByUsername(eq(username))).thenReturn(
                UserEntity.builder()
                        .id(1L)
                        .username(username)
                        .nickname(username) // Default nickname
                        .phone("")
                        .email("")
                        .role("user")
                        .build()
        );

        // When
        UserEntity result = authService.register(username, password, null, null, null);

        // Then
        assertNotNull(result);
        assertEquals(username, result.getNickname());
        assertEquals("", result.getPhone());
        assertEquals("", result.getEmail());
    }

    @Test
    void testLogin_Success() {
        // Given
        String username = "testuser";
        String password = "password123";
        String encodedPassword = passwordEncoder.encode(password);

        UserEntity user = UserEntity.builder()
                .id(1L)
                .username(username)
                .password(encodedPassword)
                .status(1)
                .build();

        when(userRepository.queryByUsername(eq(username))).thenReturn(user);

        // When
        UserEntity result = authService.login(username, password);

        // Then
        assertNotNull(result);
        assertEquals(username, result.getUsername());
        verify(userRepository).queryByUsername(eq(username));
    }

    @Test
    void testLogin_UserNotFound() {
        // Given
        String username = "nonexistent";
        String password = "password123";

        when(userRepository.queryByUsername(eq(username))).thenReturn(null);

        // When & Then
        AppException exception = assertThrows(AppException.class, () -> {
            authService.login(username, password);
        });

        assertEquals(ResponseCode.E1002.getCode(), exception.getCode());
    }

    @Test
    void testLogin_UserDisabled() {
        // Given
        String username = "disableduser";
        String password = "password123";
        String encodedPassword = passwordEncoder.encode(password);

        UserEntity user = UserEntity.builder()
                .id(1L)
                .username(username)
                .password(encodedPassword)
                .status(0) // Disabled
                .build();

        when(userRepository.queryByUsername(eq(username))).thenReturn(user);

        // When & Then
        AppException exception = assertThrows(AppException.class, () -> {
            authService.login(username, password);
        });

        assertEquals(ResponseCode.E1004.getCode(), exception.getCode());
    }

    @Test
    void testLogin_WrongPassword() {
        // Given
        String username = "testuser";
        String correctPassword = "correct123";
        String wrongPassword = "wrong123";
        String encodedPassword = passwordEncoder.encode(correctPassword);

        UserEntity user = UserEntity.builder()
                .id(1L)
                .username(username)
                .password(encodedPassword)
                .status(1)
                .build();

        when(userRepository.queryByUsername(eq(username))).thenReturn(user);

        // When & Then
        AppException exception = assertThrows(AppException.class, () -> {
            authService.login(username, wrongPassword);
        });

        assertEquals(ResponseCode.E1002.getCode(), exception.getCode());
    }

    @Test
    void testQueryById_Success() {
        // Given
        Long userId = 1L;
        UserEntity user = UserEntity.builder()
                .id(userId)
                .username("testuser")
                .build();

        when(userRepository.queryById(eq(userId))).thenReturn(user);

        // When
        UserEntity result = authService.queryById(userId);

        // Then
        assertNotNull(result);
        assertEquals(userId, result.getId());
        verify(userRepository).queryById(eq(userId));
    }

    @Test
    void testQueryById_UserNotFound() {
        // Given
        Long userId = 999L;

        when(userRepository.queryById(eq(userId))).thenReturn(null);

        // When
        UserEntity result = authService.queryById(userId);

        // Then
        assertNull(result);
    }

    @Test
    void testChangePassword_Success() {
        // Given
        Long userId = 1L;
        String oldPassword = "old123";
        String newPassword = "new456";
        String encodedOldPassword = passwordEncoder.encode(oldPassword);

        UserEntity user = UserEntity.builder()
                .id(userId)
                .username("testuser")
                .password(encodedOldPassword)
                .build();

        when(userRepository.queryById(eq(userId))).thenReturn(user);
        when(userRepository.updateUser(any(UserEntity.class))).thenReturn(true);

        // When
        authService.changePassword(userId, oldPassword, newPassword);

        // Then
        verify(userRepository).queryById(eq(userId));
        verify(userRepository).updateUser(argThat(u -> {
            String encodedNew = u.getPassword();
            return passwordEncoder.matches(newPassword, encodedNew);
        }));
    }

    @Test
    void testChangePassword_UserNotFound() {
        // Given
        Long userId = 999L;
        String oldPassword = "old123";
        String newPassword = "new456";

        when(userRepository.queryById(eq(userId))).thenReturn(null);

        // When & Then
        AppException exception = assertThrows(AppException.class, () -> {
            authService.changePassword(userId, oldPassword, newPassword);
        });

        assertEquals("1003", exception.getCode());
    }

    @Test
    void testChangePassword_WrongOldPassword() {
        // Given
        Long userId = 1L;
        String wrongOldPassword = "wrong123";
        String newPassword = "new456";
        String correctOldPassword = "correct123";
        String encodedPassword = passwordEncoder.encode(correctOldPassword);

        UserEntity user = UserEntity.builder()
                .id(userId)
                .username("testuser")
                .password(encodedPassword)
                .build();

        when(userRepository.queryById(eq(userId))).thenReturn(user);

        // When & Then
        AppException exception = assertThrows(AppException.class, () -> {
            authService.changePassword(userId, wrongOldPassword, newPassword);
        });

        assertEquals(ResponseCode.E1002.getCode(), exception.getCode());
        verify(userRepository, never()).updateUser(any());
    }
}

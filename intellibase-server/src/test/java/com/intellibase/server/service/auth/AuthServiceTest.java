package com.intellibase.server.service.auth;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.intellibase.server.common.Constants;
import com.intellibase.server.common.JwtUtils;
import com.intellibase.server.domain.dto.LoginRequest;
import com.intellibase.server.domain.dto.RegisterRequest;
import com.intellibase.server.domain.entity.SysUser;
import com.intellibase.server.domain.vo.LoginVO;
import com.intellibase.server.domain.vo.UserVO;
import com.intellibase.server.mapper.UserMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 认证服务单元测试
 * 
 * 使用 Mockito 模拟依赖项，确保测试只关注 AuthServiceImpl 本身的逻辑。
 */
@ExtendWith(MockitoExtension.class)
class AuthServiceTest {

    @Mock
    private UserMapper userMapper;

    @Mock
    private PasswordEncoder passwordEncoder;

    @Mock
    private JwtUtils jwtUtils;

    @InjectMocks
    private AuthServiceImpl authService;

    private SysUser testUser;
    private LoginRequest loginRequest;
    private RegisterRequest registerRequest;

    @BeforeEach
    void setUp() {
        // 初始化测试数据
        testUser = new SysUser();
        testUser.setId(1L);
        testUser.setUsername("testuser");
        testUser.setPasswordHash("encoded_password");
        testUser.setRole(Constants.ROLE_USER);
        testUser.setStatus(1);
        testUser.setEmail("test@example.com");

        loginRequest = new LoginRequest();
        loginRequest.setUsername("testuser");
        loginRequest.setPassword("password123");

        registerRequest = new RegisterRequest();
        registerRequest.setUsername("newuser");
        registerRequest.setPassword("password123");
        registerRequest.setEmail("newuser@example.com");
    }

    /**
     * 测试登录：成功场景
     */
    @Test
    void login_Success() {
        // 配置 Mock 行为
        when(userMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(testUser);
        when(passwordEncoder.matches(eq("password123"), eq("encoded_password"))).thenReturn(true);
        when(jwtUtils.generateToken(anyLong(), anyString(), anyString())).thenReturn("mock_token");
        when(jwtUtils.getExpirationSeconds()).thenReturn(3600L);

        // 执行测试
        LoginVO result = authService.login(loginRequest);

        // 验证结果
        assertNotNull(result);
        assertEquals("mock_token", result.getAccessToken());
        assertEquals("Bearer", result.getTokenType());
        assertEquals(3600L, result.getExpiresIn());

        // 验证方法调用次数
        verify(userMapper, times(1)).selectOne(any());
        verify(passwordEncoder, times(1)).matches(anyString(), anyString());
    }

    /**
     * 测试登录：用户不存在场景
     */
    @Test
    void login_UserNotFound_ThrowsException() {
        // 配置 Mock 行为：返回空用户
        when(userMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(null);

        // 验证是否抛出 IllegalArgumentException
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class, () -> {
            authService.login(loginRequest);
        });

        assertEquals("用户名或密码错误", exception.getMessage());
    }

    /**
     * 测试登录：密码错误场景
     */
    @Test
    void login_WrongPassword_ThrowsException() {
        // 配置 Mock 行为：用户存在但密码不匹配
        when(userMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(testUser);
        when(passwordEncoder.matches(anyString(), anyString())).thenReturn(false);

        // 验证
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class, () -> {
            authService.login(loginRequest);
        });

        assertEquals("用户名或密码错误", exception.getMessage());
    }

    /**
     * 测试登录：账号被禁用场景
     */
    @Test
    void login_UserDisabled_ThrowsException() {
        // 配置 Mock 行为：账号状态设为 0 (禁用)
        testUser.setStatus(0);
        when(userMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(testUser);
        when(passwordEncoder.matches(anyString(), anyString())).thenReturn(true);

        // 验证
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class, () -> {
            authService.login(loginRequest);
        });

        assertEquals("账号已被禁用", exception.getMessage());
    }

    /**
     * 测试注册：成功场景
     */
    @Test
    void register_Success() {
        // 配置 Mock 行为
        when(userMapper.selectCount(any(LambdaQueryWrapper.class))).thenReturn(0L);
        when(passwordEncoder.encode(anyString())).thenReturn("encoded_password");

        // 执行测试
        UserVO result = authService.register(registerRequest);

        // 验证结果
        assertNotNull(result);
        assertEquals("newuser", result.getUsername());
        assertEquals("newuser@example.com", result.getEmail());
        assertEquals(Constants.ROLE_USER, result.getRole());

        // 验证数据库插入调用
        verify(userMapper, times(1)).insert(any(SysUser.class));
    }

    /**
     * 测试注册：用户名已存在场景
     */
    @Test
    void register_UserAlreadyExists_ThrowsException() {
        // 配置 Mock 行为：selectCount 返回大于 0
        when(userMapper.selectCount(any(LambdaQueryWrapper.class))).thenReturn(1L);

        // 验证
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class, () -> {
            authService.register(registerRequest);
        });

        assertEquals("用户名已存在", exception.getMessage());
    }
}

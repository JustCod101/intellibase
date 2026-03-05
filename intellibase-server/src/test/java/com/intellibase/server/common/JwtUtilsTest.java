package com.intellibase.server.common;

import io.jsonwebtoken.Claims;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * JWT 工具类单元测试
 */
class JwtUtilsTest {

    private JwtUtils jwtUtils;
    
    // 固定的测试密钥 (Base64 编码，长度需满足 HS256 算法要求)
    private final String secret = "ZW5jb2RlZF9zZWNyZXRfa2V5X2Zvcl90ZXN0aW5nX3B1cnBvc2VzX211c3RfYmVfbG9uZw==";
    private final long expiration = 3600000; // 1 小时

    @BeforeEach
    void setUp() {
        jwtUtils = new JwtUtils(secret, expiration);
    }

    /**
     * 测试 Token 生成与解析
     */
    @Test
    void generateAndParseToken_Success() {
        Long userId = 123L;
        String username = "admin";
        String role = "ADMIN";

        // 1. 生成 Token
        String token = jwtUtils.generateToken(userId, username, role);
        assertNotNull(token);

        // 2. 解析 Token
        Claims claims = jwtUtils.parseToken(token);
        
        // 3. 验证 Payload 内容
        assertEquals(String.valueOf(userId), claims.getSubject());
        assertEquals(username, claims.get("username"));
        assertEquals(role, claims.get("role"));
    }

    /**
     * 测试解析无效 Token
     */
    @Test
    void parseInvalidToken_ThrowsException() {
        String invalidToken = "invalid.token.string";
        assertThrows(Exception.class, () -> {
            jwtUtils.parseToken(invalidToken);
        });
    }

    /**
     * 测试获取过期时间
     */
    @Test
    void getExpirationSeconds() {
        assertEquals(3600L, jwtUtils.getExpirationSeconds());
    }
}

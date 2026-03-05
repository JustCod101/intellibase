package com.intellibase.server.common;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.io.Decoders;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.util.Date;

/**
 * JWT (JSON Web Token) 工具类
 * <p>
 * 主要功能：
 * 1. 生成 Token：将用户信息加密成一段字符串（Token），发给前端。
 * 2. 解析/验证 Token：接收前端传来的字符串，验证其合法性并还原出用户信息。
 * <p>
 * JWT 的结构：Header（头部）、Payload（负载）、Signature（签名），三者用 "." 连接。
 */
@Component
public class JwtUtils {

    /**
     * 签名密钥。
     * 用于对生成的 Token 进行加密，以及对接收到的 Token 进行解密校验。
     * 必须保密，如果泄露，任何人都可以伪造你的 Token。
     */
    private final SecretKey signingKey;

    /**
     * Token 的有效时间（毫秒）。
     * 超过这个时间，Token 就会失效，用户需要重新登录。
     */
    private final long expiration;

    /**
     * 构造函数：初始化密钥和过期时间。
     * 
     * @param secret 从配置文件 (application.yml) 中读取的 Base64 编码的原始密钥字符串。
     * @param expiration 从配置文件中读取的过期时间。
     */
    public JwtUtils(@Value("${jwt.secret}") String secret,
                    @Value("${jwt.expiration}") long expiration) {
        // 将 Base64 编码的密钥字符串解码并转换为 HMAC 算法所需的密钥对象
        this.signingKey = Keys.hmacShaKeyFor(Decoders.BASE64.decode(secret));
        this.expiration = expiration;
    }

    /**
     * 生成 JWT Token
     * 
     * @param userId   用户唯一标识 ID
     * @param username 用户名
     * @param role     用户角色（用于后续权限校验）
     * @return 返回生成的加密字符串（Token）
     */
    public String generateToken(Long userId, String username, String role) {
        Date now = new Date(); // 当前时间
        
        // 使用 Jwts 构建器创建 Token
        return Jwts.builder()
                .subject(String.valueOf(userId))     // 设置主题（通常存用户ID）
                .claim("username", username)         // 自定义负载：存放用户名
                .claim("role", role)                 // 自定义负载：存放角色
                .issuedAt(now)                       // 设置签发时间
                .expiration(new Date(now.getTime() + expiration)) // 设置过期时间 = 当前时间 + 配置的有效时长
                .signWith(signingKey)                // 使用密钥进行签名加密
                .compact();                          // 压缩并生成最终字符串
    }

    /**
     * 解析并校验 Token
     * <p>
     * 该方法会自动验证：
     * 1. Token 是否被篡改（通过签名验证）。
     * 2. Token 是否已过期。
     * 3. Token 格式是否正确。
     * 
     * @param token 前端传回的加密字符串
     * @return 返回 Token 中包含的所有声明（Claims，即用户信息）
     * @throws io.jsonwebtoken.JwtException 如果验证失败（过期、伪造等）会抛出异常
     */
    public Claims parseToken(String token) {
        return Jwts.parser()
                .verifyWith(signingKey)             // 设置验证签名时使用的密钥
                .build()                            // 构建解析器
                .parseSignedClaims(token)           // 解析签名的 Claims
                .getPayload();                      // 获取其中的数据载体（Payload）
    }

    /**
     * 获取 Token 的有效时长（单位：秒）
     * 常用于前端计算倒计时或 Redis 缓存过期时间设置。
     * 
     * @return 有效时长（秒）
     */
    public long getExpirationSeconds() {
        return expiration / 1000;
    }

}

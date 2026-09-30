package com.per.server.common;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;

/**
 * JWT 工具类：基于 jjwt 0.12.x 实现令牌生成与解析，
 * 载荷中携带 uid / username / role 三个自定义声明
 */
@Component
public class JwtUtil {

    /** 自定义声明：用户ID */
    public static final String CLAIM_UID = "uid";
    /** 自定义声明：用户名 */
    public static final String CLAIM_USERNAME = "username";
    /** 自定义声明：角色 */
    public static final String CLAIM_ROLE = "role";

    /** JWT 签名密钥（来自配置 per.jwt.secret） */
    @Value("${per.jwt.secret}")
    private String secret;

    /** 令牌有效期（小时，来自配置 per.jwt.expire-hours） */
    @Value("${per.jwt.expire-hours}")
    private long expireHours;

    /** 签名密钥对象 */
    private SecretKey key;

    /**
     * 初始化签名密钥（依赖注入完成后执行）
     */
    @PostConstruct
    public void init() {
        this.key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * 生成 JWT 令牌
     *
     * @param uid      用户ID
     * @param username 用户名
     * @param role     角色（ADMIN/USER）
     * @return 签名后的 JWT 字符串
     */
    public String generateToken(Long uid, String username, String role) {
        Date now = new Date();
        Date expire = new Date(now.getTime() + expireHours * 3600_000L);
        return Jwts.builder()
                .claim(CLAIM_UID, uid)
                .claim(CLAIM_USERNAME, username)
                .claim(CLAIM_ROLE, role)
                .issuedAt(now)
                .expiration(expire)
                .signWith(key)
                .compact();
    }

    /**
     * 解析 JWT 令牌
     *
     * @param token JWT 字符串
     * @return 登录用户信息；令牌无效或已过期时返回 null
     */
    public LoginUser parseToken(String token) {
        try {
            Claims claims = Jwts.parser().verifyWith(key).build()
                    .parseSignedClaims(token).getPayload();
            Number uid = claims.get(CLAIM_UID, Number.class);
            String username = claims.get(CLAIM_USERNAME, String.class);
            String role = claims.get(CLAIM_ROLE, String.class);
            if (uid == null || username == null) {
                return null;
            }
            return new LoginUser(uid.longValue(), username, role);
        } catch (Exception e) {
            return null;
        }
    }
}

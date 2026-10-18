package com.kuma.search.admin.security;

import com.kuma.search.config.KumaSearchProperties;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.List;
import java.util.UUID;

/**
 * JWT 签发与解析，基于 jjwt 0.12.7 的新 API（{@code Jwts.builder()...signWith(key)} /
 * {@code Jwts.parser().verifyWith(key).build().parseSignedClaims(token)}）。
 *
 * <p>密钥来自 {@code kuma.jwt.secret}（HS256，长度必须 ≥ 32 字节），
 * <b>启动时即校验</b>：密钥未配置或过短直接抛异常，fail-fast 好过线上被弱令牌击穿。
 */
@Component
public class JwtTokenProvider {

    /** access token 类型标识。 */
    public static final String TYPE_ACCESS = "access";
    /** refresh token 类型标识。 */
    public static final String TYPE_REFRESH = "refresh";

    private static final String CLAIM_USER_ID = "userId";
    private static final String CLAIM_USERNAME = "username";
    private static final String CLAIM_ROLES = "roles";
    private static final String CLAIM_PERMS = "perms";
    private static final String CLAIM_TYPE = "typ";

    private static final int MIN_SECRET_BYTES = 32;

    private final SecretKey secretKey;
    private final KumaSearchProperties.Jwt jwt;

    public JwtTokenProvider(KumaSearchProperties properties) {
        this.jwt = properties.getJwt();
        String secret = jwt.getSecret();
        if (secret == null || secret.getBytes(StandardCharsets.UTF_8).length < MIN_SECRET_BYTES) {
            throw new IllegalStateException(
                    "kuma.jwt.secret 未配置或长度不足 " + MIN_SECRET_BYTES + " 字节，无法使用 HS256 签发 JWT");
        }
        this.secretKey = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * 签发 access token，内嵌 userId/username/roles/perms，支撑完全无状态的鉴权。
     *
     * <p>代价是权限变更要等令牌过期才生效；需要即时作废时走 Redis 黑名单（logout 即此路径）。
     */
    public GeneratedToken generateAccessToken(LoginUser user) {
        return generateAccessToken(user, new Date());
    }

    /**
     * 以<b>指定时刻</b>签发 access token。
     *
     * <p>存在的唯一理由是「作废与换发必须能分开」：作废水位线是毫秒、{@code iat} 只有秒，
     * 用当前时刻签发的新令牌会被刚写的作废记录判为已作废。调用方要传的时刻由
     * {@code TokenRevocationService#issuableDateAfterRevoke} 算出 ——
     * <b>不要在这里收一个"随便传"的 Date</b>，那个约束属于吊销逻辑，不属于签发逻辑。
     */
    public GeneratedToken generateAccessToken(LoginUser user, Date issuedAt) {
        long ttlSeconds = jwt.getAccessTokenTtl();
        String jti = UUID.randomUUID().toString();
        String token = Jwts.builder()
                .subject(String.valueOf(user.getUserId()))
                .issuer(jwt.getIssuer())
                .id(jti)
                .issuedAt(issuedAt)
                .expiration(new Date(issuedAt.getTime() + ttlSeconds * 1000L))
                .claim(CLAIM_USER_ID, user.getUserId())
                .claim(CLAIM_USERNAME, user.getUsername())
                .claim(CLAIM_ROLES, user.getRoles())
                .claim(CLAIM_PERMS, user.getPermissions())
                .claim(CLAIM_TYPE, TYPE_ACCESS)
                .signWith(secretKey)
                .compact();
        return new GeneratedToken(token, jti, ttlSeconds);
    }

    /**
     * 签发 refresh token。
     *
     * <p>刻意<b>不</b>内嵌 roles/perms：续期时由服务端重新查库装载最新权限，
     * 避免"降权后旧 refresh 仍带着高权限复活"。
     */
    public GeneratedToken generateRefreshToken(LoginUser user) {
        return generateRefreshToken(user, new Date());
    }

    /**
     * 以指定时刻签发 refresh token。
     *
     * <p><b>必须与 access token 一起用同一个 {@code issuedAt}</b>。
     * 只改 access 那一侧的话，换发出来的 refresh token 仍带着"现在"的 iat
     * 而被水位线判为已作废 —— 症状是「改完密码能用两小时，之后续期突然失败」，
     * 一个比当场掉线更难查的故障。
     */
    public GeneratedToken generateRefreshToken(LoginUser user, Date issuedAt) {
        long ttlSeconds = jwt.getRefreshTokenTtl();
        String jti = UUID.randomUUID().toString();
        String token = Jwts.builder()
                .subject(String.valueOf(user.getUserId()))
                .issuer(jwt.getIssuer())
                .id(jti)
                .issuedAt(issuedAt)
                .expiration(new Date(issuedAt.getTime() + ttlSeconds * 1000L))
                .claim(CLAIM_USER_ID, user.getUserId())
                .claim(CLAIM_TYPE, TYPE_REFRESH)
                .signWith(secretKey)
                .compact();
        return new GeneratedToken(token, jti, ttlSeconds);
    }

    /**
     * 解析并校验签名与有效期。
     *
     * @throws io.jsonwebtoken.ExpiredJwtException 令牌过期
     * @throws io.jsonwebtoken.JwtException        签名错误、格式非法等
     */
    public Claims parseClaims(String token) {
        return Jwts.parser().verifyWith(secretKey).build().parseSignedClaims(token).getPayload();
    }

    public boolean isAccessToken(Claims claims) {
        return TYPE_ACCESS.equals(tokenType(claims));
    }

    public boolean isRefreshToken(Claims claims) {
        return TYPE_REFRESH.equals(tokenType(claims));
    }

    public String tokenType(Claims claims) {
        return claims.get(CLAIM_TYPE, String.class);
    }

    /** 取 userId。jjwt 可能把数字反序列化成 Integer，这里统一按字符串解析再转 Long，避免类型转换踩坑。 */
    public Long userId(Claims claims) {
        Object raw = claims.get(CLAIM_USER_ID);
        return raw == null ? null : Long.valueOf(raw.toString());
    }

    public String username(Claims claims) {
        return claims.get(CLAIM_USERNAME, String.class);
    }

    @SuppressWarnings("unchecked")
    public List<String> roles(Claims claims) {
        Object raw = claims.get(CLAIM_ROLES);
        return raw instanceof List<?> list ? (List<String>) list : List.of();
    }

    @SuppressWarnings("unchecked")
    public List<String> permissions(Claims claims) {
        Object raw = claims.get(CLAIM_PERMS);
        return raw instanceof List<?> list ? (List<String>) list : List.of();
    }

    public long accessTokenTtlSeconds() {
        return jwt.getAccessTokenTtl();
    }

    public String header() {
        return jwt.getHeader();
    }

    public String tokenPrefix() {
        return jwt.getTokenPrefix();
    }

    /**
     * 新签发令牌的返回体。
     *
     * @param token           令牌字符串
     * @param jti             令牌唯一 ID，用于登出加黑名单
     * @param expiresInSeconds 有效期（秒）
     */
    public record GeneratedToken(String token, String jti, long expiresInSeconds) {
    }
}

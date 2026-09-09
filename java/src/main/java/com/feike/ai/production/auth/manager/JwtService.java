package com.feike.ai.production.auth.manager;

import com.feike.ai.production.auth.model.ProductionPrincipal;

import com.feike.ai.production.secret.dao.SecretResolver;
import com.feike.ai.production.secret.service.SecretUnavailableException;
import com.feike.ai.production.web.BusinessException;
import com.feike.ai.production.web.ErrorCodeEnum;
import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jose.crypto.MACVerifier;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;

import java.nio.charset.StandardCharsets;
import java.text.ParseException;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * HS256 JWT 签发与校验。HMAC 来自信封解密的 {@code jwt.hmac}，不写在配置文件明文里。
 */
public class JwtService {

    private static final String ISSUER = "ai-example-production";

    private final SecretResolver secrets;
    private final java.time.Duration ttl;

    /**
     * @param secrets 读取 jwt.hmac
     * @param ttl     令牌有效期
     */
    public JwtService(SecretResolver secrets, java.time.Duration ttl) {
        this.secrets = secrets;
        this.ttl = ttl;
    }

    /**
     * 签发访问令牌。
     *
     * @param principal 主体
     * @return 紧凑序列化的 JWT
     */
    public String issue(ProductionPrincipal principal) {
        byte[] hmac = hmacBytes();
        Instant now = Instant.now();
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
            .issuer(ISSUER)
            .subject(principal.subject())
            .issueTime(Date.from(now))
            .expirationTime(Date.from(now.plus(ttl)))
            .claim("tenant", principal.tenantId())
            .claim("roles", List.copyOf(principal.roles()))
            .build();
        SignedJWT jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), claims);
        try {
            jwt.sign(new MACSigner(hmac));
            return jwt.serialize();
        } catch (JOSEException ex) {
            throw new SecretUnavailableException("签发 JWT 失败: " + ex.getMessage(), ex);
        }
    }

    /**
     * 校验并解析。
     *
     * @param token 紧凑 JWT
     * @return 主体
     * @throws BusinessException 401 令牌无效
     */
    public ProductionPrincipal parse(String token) {
        try {
            SignedJWT jwt = SignedJWT.parse(token);
            if (!jwt.verify(new MACVerifier(hmacBytes()))) {
                throw unauthorized("令牌签名无效");
            }
            JWTClaimsSet claims = jwt.getJWTClaimsSet();
            if (claims.getExpirationTime() == null || claims.getExpirationTime().toInstant().isBefore(Instant.now())) {
                throw unauthorized("令牌已过期");
            }
            if (!ISSUER.equals(claims.getIssuer())) {
                throw unauthorized("令牌签发者不匹配");
            }
            String tenant = stringClaim(claims, "tenant");
            List<String> roles = claims.getStringListClaim("roles");
            Set<String> roleSet = new LinkedHashSet<>();
            if (roles != null) {
                for (String role : roles) {
                    if (role != null && !role.isBlank()) {
                        roleSet.add(role.trim().toUpperCase());
                    }
                }
            }
            return new ProductionPrincipal(claims.getSubject(), tenant, Set.copyOf(roleSet));
        } catch (ParseException | JOSEException ex) {
            throw unauthorized("令牌无法解析");
        }
    }

    private byte[] hmacBytes() {
        String encoded = secrets.require(SecretResolver.JWT_HMAC);
        try {
            byte[] decoded = Base64.getDecoder().decode(encoded.trim());
            if (decoded.length < 32) {
                // Nimbus HS256 要求至少 256 bit；seed 阶段写的是 32 字节 Base64
                return decoded.length == 0 ? encoded.getBytes(StandardCharsets.UTF_8) : decoded;
            }
            return decoded;
        } catch (IllegalArgumentException ex) {
            byte[] raw = encoded.getBytes(StandardCharsets.UTF_8);
            if (raw.length < 32) {
                throw new SecretUnavailableException("jwt.hmac 长度不足 32 字节");
            }
            return raw;
        }
    }

    private static String stringClaim(JWTClaimsSet claims, String name) throws ParseException {
        Object value = claims.getClaim(name);
        if (value == null) {
            throw unauthorized("令牌缺少 " + name);
        }
        return String.valueOf(value);
    }

    private static BusinessException unauthorized(String message) {
        return new BusinessException(ErrorCodeEnum.AUTH_INVALID, message);
    }
}

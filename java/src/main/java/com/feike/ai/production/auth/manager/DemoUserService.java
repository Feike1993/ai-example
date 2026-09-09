package com.feike.ai.production.auth.manager;

import com.feike.ai.production.auth.model.ProductionPrincipal;

import com.feike.ai.production.config.ProductionProperties;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.server.ResponseStatusException;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 演示账号校验。密码在 YAML 里可以是明文 {@code demo}，装载时编成 BCrypt。
 * <p>
 * 本仓不做真实用户库：三人三租户足够演示隔离、角色和审计。
 */
public class DemoUserService {

    private final Map<String, ProductionProperties.DemoUser> users;
    private final Map<String, String> encodedPasswords = new LinkedHashMap<>();
    private final PasswordEncoder encoder = new BCryptPasswordEncoder();

    /**
     * @param properties 演示账号列表
     */
    public DemoUserService(ProductionProperties properties) {
        Map<String, ProductionProperties.DemoUser> byName = new LinkedHashMap<>();
        for (ProductionProperties.DemoUser user : properties.users()) {
            if (user.username() == null || user.username().isBlank()) {
                continue;
            }
            String name = user.username().trim().toLowerCase(Locale.ROOT);
            byName.put(name, user);
            String password = user.password() == null ? "" : user.password();
            encodedPasswords.put(name, password.startsWith("$2") ? password : encoder.encode(password));
        }
        this.users = Map.copyOf(byName);
    }

    /**
     * 校验用户名密码。
     *
     * @param username 用户名
     * @param password 明文密码
     * @return 主体
     */
    public ProductionPrincipal authenticate(String username, String password) {
        if (username == null || username.isBlank() || password == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "用户名或密码错误");
        }
        String key = username.trim().toLowerCase(Locale.ROOT);
        ProductionProperties.DemoUser user = users.get(key);
        String encoded = encodedPasswords.get(key);
        if (user == null || encoded == null || !encoder.matches(password, encoded)) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "用户名或密码错误");
        }
        return new ProductionPrincipal(
            user.username(),
            user.tenant(),
            Set.copyOf(user.roles().stream().map(role -> role.trim().toUpperCase(Locale.ROOT)).toList())
        );
    }
}

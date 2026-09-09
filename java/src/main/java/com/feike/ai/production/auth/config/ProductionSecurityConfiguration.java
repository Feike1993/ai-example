package com.feike.ai.production.auth.config;

import com.feike.ai.production.auth.controller.JwtAuthFilter;
import com.feike.ai.production.auth.manager.JwtService;

import com.feike.ai.production.observability.service.ProductionMetrics;
import com.feike.ai.production.secret.dao.SecretResolver;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

/**
 * 只在工业级打开时介入。教学样例路径全部放行；{@code /api/v1/**} 由 {@link JwtAuthFilter} 处理。
 * <p>
 * 应用主类排除了默认 {@code SecurityAutoConfiguration}，避免 starter 把所有接口锁上。
 */
@Configuration
@EnableWebSecurity
@ConditionalOnProperty(prefix = "app.production", name = "enabled", havingValue = "true")
public class ProductionSecurityConfiguration {

    /**
     * @param jwtService 令牌
     * @param secrets    信封是否可用
     * @param metrics    鉴权失败计数
     * @param http       构建器
     * @return 过滤器链
     * @throws Exception 配置失败
     */
    @Bean
    public SecurityFilterChain productionSecurityFilterChain(
        JwtService jwtService,
        SecretResolver secrets,
        ProductionMetrics metrics,
        HttpSecurity http
    ) throws Exception {
        JwtAuthFilter jwtFilter = new JwtAuthFilter(jwtService, secrets, metrics);
        return http
            .csrf(csrf -> csrf.disable())
            .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(auth -> auth
                .requestMatchers("/actuator/health", "/actuator/health/**").permitAll()
                .anyRequest().permitAll())
            .addFilterBefore(jwtFilter, UsernamePasswordAuthenticationFilter.class)
            .build();
    }
}

package com.feike.ai.production.auth.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.web.SecurityFilterChain;

/** 工业链路关闭时仍提供基础过滤链，让匿名教学接口和管理端健康检查可正常启动。 */
@Configuration
@EnableWebSecurity
@ConditionalOnProperty(prefix = "app.production", name = "enabled", havingValue = "false", matchIfMissing = true)
public class TeachingSecurityConfiguration {

    @Bean
    public SecurityFilterChain teachingSecurityFilterChain(HttpSecurity http) throws Exception {
        return http
            .csrf(csrf -> csrf.disable())
            .authorizeHttpRequests(auth -> auth.anyRequest().permitAll())
            .build();
    }
}

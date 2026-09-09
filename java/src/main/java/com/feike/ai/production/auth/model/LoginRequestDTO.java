package com.feike.ai.production.auth.model;

import jakarta.validation.constraints.NotBlank;

/**
 * 登录请求。
 *
 * @param username 用户名
 * @param password 密码
 */
public record LoginRequestDTO(@NotBlank String username, @NotBlank String password) {}

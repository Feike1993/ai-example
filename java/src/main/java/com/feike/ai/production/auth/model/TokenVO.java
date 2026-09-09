package com.feike.ai.production.auth.model;

import java.util.List;

/**
 * 登录成功后的令牌展示。
 *
 * @param token     JWT
 * @param username  用户
 * @param tenant    租户
 * @param roles     角色
 * @param expiresIn 秒
 */
public record TokenVO(
    String token,
    String username,
    String tenant,
    List<String> roles,
    long expiresIn
) {}

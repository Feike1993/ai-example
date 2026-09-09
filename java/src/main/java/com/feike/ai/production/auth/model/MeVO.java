package com.feike.ai.production.auth.model;

import java.util.List;

/**
 * 当前登录身份展示。
 *
 * @param username 用户
 * @param tenant   租户
 * @param roles    角色
 */
public record MeVO(String username, String tenant, List<String> roles) {}

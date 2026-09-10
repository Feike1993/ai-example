package com.feike.ai.production.secret.model;

/**
 * KEK 重加密结果。
 *
 * @param rewritten 重写行数
 * @param kekId     写入的新版本
 */
public record SecretRotateVO(int rewritten, String kekId) {}

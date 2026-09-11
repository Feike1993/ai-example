package com.feike.ai.production.media.model;

/**
 * 媒体探针成功体。不含文件内容。
 *
 * @param mime   归一化 mime
 * @param bytes  字节数
 * @param sha256 内容 SHA-256 小写 hex
 */
public record MediaProbeVO(String mime, int bytes, String sha256) {}

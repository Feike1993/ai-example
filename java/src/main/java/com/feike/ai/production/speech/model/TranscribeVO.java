package com.feike.ai.production.speech.model;

/**
 * 转写结果。
 *
 * @param text 识别出的文本；可能为空串
 */
public record TranscribeVO(String text) {}

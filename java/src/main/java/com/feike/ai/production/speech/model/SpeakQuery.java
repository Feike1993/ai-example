package com.feike.ai.production.speech.model;

import jakarta.validation.constraints.NotBlank;

/**
 * TTS 请求。长度上限在服务层按配置再卡一道。
 *
 * @param text 要朗读的文本
 */
public record SpeakQuery(
    @NotBlank(message = "朗读文本不能为空")
    String text
) {}

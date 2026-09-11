package com.feike.ai.production.media.model;

/**
 * 上传探针区分图片与音频允许列表。
 */
public enum MediaKindEnum {
    /** 识图。 */
    IMAGE,
    /** 语音转写。 */
    AUDIO,
    /** 探针：图片或音频均可。 */
    ANY
}

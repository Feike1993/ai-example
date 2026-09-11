package com.feike.ai.production.media.model;

/**
 * 上传探针区分图片、音频与文档允许列表。
 */
public enum MediaKindEnum {
    /** 识图。 */
    IMAGE,
    /** 语音转写。 */
    AUDIO,
    /** 本轮文档附件。 */
    DOCUMENT,
    /** 探针：图片、音频或文档均可。 */
    ANY
}

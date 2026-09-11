package com.feike.ai.production.chat.model;

import java.util.List;

/**
 * 本轮识图附件。只在内存里走完这一次 SSE，不落盘。
 *
 * @param bytes 图片字节
 * @param mime  归一化 mime
 */
public record ChatImage(byte[] bytes, String mime) {

    /**
     * 单张可选图转列表。
     *
     * @param bytes 可空
     * @param mime  mime
     * @return 空或单元素
     */
    public static List<ChatImage> ofNullable(byte[] bytes, String mime) {
        if (bytes == null || bytes.length == 0) {
            return List.of();
        }
        return List.of(new ChatImage(bytes, mime));
    }
}

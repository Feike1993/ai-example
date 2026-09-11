package com.feike.ai.production.chat.model;

import java.util.List;

/**
 * 本轮识图与文档摘录。只在有文档时走这条装配路径，避免和单图 {@code byte[]} 重载抢签名。
 *
 * @param images    识图；可空
 * @param documents 文档摘录；可空
 */
public record ChatAttachments(List<ChatImage> images, List<ChatDocument> documents) {

    /**
     * @param images    识图；可空
     * @param documents 文档摘录；可空
     * @return 空列表替代 null 的附件
     */
    public static ChatAttachments of(List<ChatImage> images, List<ChatDocument> documents) {
        return new ChatAttachments(
            images == null ? List.of() : images,
            documents == null ? List.of() : documents
        );
    }

    /**
     * @return 是否带了文档
     */
    public boolean hasDocuments() {
        return documents != null && !documents.isEmpty();
    }
}

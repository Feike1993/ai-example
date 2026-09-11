package com.feike.ai.production.agent.model;

import com.feike.ai.production.chat.model.ChatDocument;

import java.util.List;

/**
 * 流式 Agent 本轮附件：文档抽出正文 + 图片 VL 转写，不把位图带进工具循环。
 *
 * @param documents         抽出后的文档；可空
 * @param imageTranscript   图片转写/简述；无图为空串
 * @param imageCount        本轮原图张数（落库占位用）
 * @param ocrUsed           文档是否走过扫描 OCR
 * @param visionTranscribed 是否调用过 VL 转写图片
 */
public record AgentTurnMedia(
    List<ChatDocument> documents,
    String imageTranscript,
    int imageCount,
    boolean ocrUsed,
    boolean visionTranscribed
) {

    /**
     * @return 无附件
     */
    public static AgentTurnMedia none() {
        return new AgentTurnMedia(List.of(), "", 0, false, false);
    }

    /**
     * @return 文档件数
     */
    public int documentCount() {
        return documents == null ? 0 : documents.size();
    }
}

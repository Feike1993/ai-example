package com.feike.ai.production.chat.model;

/**
 * 本轮文档附件的抽出结果。只在内存里走完这一次 SSE，不落盘。
 * <p>
 * {@code sourceBytes} 仅扫描 PDF 候选保留，供 OCR 渲染后丢弃。
 *
 * @param filename       原始文件名
 * @param mime           归一化 mime
 * @param extractedText  数字抽取或 OCR 正文
 * @param ocrCandidate   PDF 抽出字太少且有页，需要 VL 转写
 * @param sourceBytes    扫描候选的源文件；转写后应清空
 */
public record ChatDocument(
    String filename,
    String mime,
    String extractedText,
    boolean ocrCandidate,
    byte[] sourceBytes
) {

    /**
     * 用新正文替换，并按是否仍需 OCR 决定是否保留源字节。
     *
     * @param text          正文
     * @param stillCandidate 是否仍要 OCR
     * @return 新记录
     */
    public ChatDocument withText(String text, boolean stillCandidate) {
        return new ChatDocument(filename, mime, text == null ? "" : text, stillCandidate,
            stillCandidate ? sourceBytes : null);
    }
}

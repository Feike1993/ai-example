package com.feike.ai.production.media.manager;

import com.feike.ai.production.chat.model.ChatDocument;
import com.feike.ai.production.config.ProductionProperties;
import com.feike.ai.production.secret.manager.ProductionModelFactory;
import com.feike.ai.production.secret.service.SecretUnavailableException;
import com.feike.ai.production.web.BusinessException;
import com.feike.ai.production.web.ErrorCodeEnum;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.content.Media;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.util.MimeTypeUtils;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * 扫描 PDF：数字抽取字太少时渲染页，用现有 VL 只转写。
 * <p>
 * 渲染位图用完即弃，不塞进本轮识图 Media。无 Key 时 503，转写失败 422。
 */
public class ProductionDocumentOcr {

    private static final String OCR_SYSTEM = """
        你只做文字转写。只输出图中可见的文字，不要描述画面，不要翻译，不要补全。
        若没有文字则输出空。
        """;

    private final ProductionProperties.Media media;
    private final ProductionModelFactory models;

    /**
     * @param properties 页数上限
     * @param models     视觉客户端
     */
    public ProductionDocumentOcr(ProductionProperties properties, ProductionModelFactory models) {
        this.media = properties.media();
        this.models = models;
    }

    /**
     * 对扫描候选做 VL 转写；其余原样返回并丢掉源字节。
     *
     * @param documents 抽出结果
     * @param provider  视觉 Provider；空则用配置默认
     * @return 转写后的列表与是否用过 OCR
     */
    public OcrBatch transcribeIfNeeded(List<ChatDocument> documents, String provider) {
        if (documents == null || documents.isEmpty()) {
            return new OcrBatch(List.of(), false);
        }
        List<ChatDocument> out = new ArrayList<>();
        boolean used = false;
        for (ChatDocument document : documents) {
            if (!document.ocrCandidate()) {
                out.add(document.withText(document.extractedText(), false));
                continue;
            }
            String text = transcribePdf(document.sourceBytes(), provider);
            used = true;
            out.add(document.withText(text, false));
        }
        return new OcrBatch(List.copyOf(out), used);
    }

    private String transcribePdf(byte[] pdf, String provider) {
        if (pdf == null || pdf.length == 0) {
            throw new BusinessException(ErrorCodeEnum.MEDIA_UNREADABLE, "无法读取文档内容");
        }
        List<Media> pages;
        try {
            pages = renderPages(pdf);
        } catch (IOException ex) {
            throw new BusinessException(ErrorCodeEnum.MEDIA_UNREADABLE, "无法渲染 PDF 页面");
        }
        if (pages.isEmpty()) {
            throw new BusinessException(ErrorCodeEnum.MEDIA_UNREADABLE, "无法渲染 PDF 页面");
        }
        try {
            String content = models.visionClient(provider)
                .prompt()
                .messages(
                    new SystemMessage(OCR_SYSTEM),
                    UserMessage.builder()
                        .text("只输出图中文字，不要描述。")
                        .media(pages.toArray(Media[]::new))
                        .build()
                )
                .call()
                .content();
            String text = content == null ? "" : content.strip();
            if (text.isEmpty()) {
                throw new BusinessException(ErrorCodeEnum.MEDIA_UNREADABLE, "未能从扫描件识别出文字");
            }
            int max = media.maxExtractChars();
            if (text.length() > max) {
                return text.substring(0, max) + "\n…（已截断）";
            }
            return text;
        } catch (BusinessException | SecretUnavailableException ex) {
            throw ex;
        } catch (RuntimeException ex) {
            throw new BusinessException(ErrorCodeEnum.MEDIA_UNREADABLE, "扫描件文字识别失败");
        }
    }

    private List<Media> renderPages(byte[] pdf) throws IOException {
        try (PDDocument doc = Loader.loadPDF(pdf)) {
            PDFRenderer renderer = new PDFRenderer(doc);
            int limit = Math.min(doc.getNumberOfPages(), media.ocrMaxPages());
            List<Media> pages = new ArrayList<>();
            for (int i = 0; i < limit; i++) {
                BufferedImage image = renderer.renderImageWithDPI(i, 120, ImageType.RGB);
                ByteArrayOutputStream buffer = new ByteArrayOutputStream();
                ImageIO.write(image, "png", buffer);
                pages.add(new Media(MimeTypeUtils.IMAGE_PNG, new ByteArrayResource(buffer.toByteArray())));
            }
            return pages;
        }
    }

    /**
     * @param documents 转写后的附件
     * @param ocrUsed   是否调用过 VL
     */
    public record OcrBatch(List<ChatDocument> documents, boolean ocrUsed) {}
}

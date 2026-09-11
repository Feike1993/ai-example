package com.feike.ai.production.media.manager;

import com.feike.ai.production.chat.model.ChatDocument;
import com.feike.ai.production.config.ProductionProperties;
import com.feike.ai.production.web.BusinessException;
import com.feike.ai.production.web.ErrorCodeEnum;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.encryption.InvalidPasswordException;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.apache.poi.xwpf.extractor.XWPFWordExtractor;
import org.apache.poi.xwpf.usermodel.XWPFDocument;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * 把本轮文档抽成纯文本。不调模型、不落盘。
 * <p>
 * PDF 抽出字太少时标 {@code ocrCandidate}，扫描转写留给 {@link ProductionDocumentOcr}。
 */
public class ProductionDocumentExtractor {

    static final String PDF = "application/pdf";
    static final String TXT = "text/plain";
    static final String MD = "text/markdown";
    static final String DOCX = "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
    static final String XLSX = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";

    private final ProductionProperties.Media media;

    /**
     * @param properties 抽出上限与 OCR 阈值
     */
    public ProductionDocumentExtractor(ProductionProperties properties) {
        this.media = properties.media();
    }

    /**
     * @param body     已通过 mime / 大小校验的字节
     * @param mime     归一化 mime
     * @param filename 原始名；可空
     * @return 抽出结果
     */
    public ChatDocument extract(byte[] body, String mime, String filename) {
        String name = filename == null || filename.isBlank() ? "document" : filename;
        try {
            return switch (mime) {
                case PDF -> extractPdf(body, name);
                case DOCX -> extractDocx(body, name);
                case XLSX -> extractXlsx(body, name);
                case TXT, MD -> extractText(body, mime, name);
                default -> throw unreadable("不支持的文档类型");
            };
        } catch (BusinessException ex) {
            throw ex;
        } catch (RuntimeException | IOException ex) {
            throw unreadable("无法读取文档内容");
        }
    }

    private ChatDocument extractPdf(byte[] body, String filename) throws IOException {
        try (PDDocument doc = Loader.loadPDF(body)) {
            if (doc.isEncrypted()) {
                throw unreadable("加密 PDF 无法抽取");
            }
            int pages = doc.getNumberOfPages();
            if (pages < 1) {
                throw unreadable("PDF 没有页面");
            }
            String raw = new PDFTextStripper().getText(doc);
            String text = truncate(raw == null ? "" : raw.strip());
            boolean ocr = text.length() < media.ocrMinChars();
            return new ChatDocument(filename, PDF, text, ocr, ocr ? body : null);
        } catch (InvalidPasswordException ex) {
            throw unreadable("加密 PDF 无法抽取");
        }
    }

    private ChatDocument extractDocx(byte[] body, String filename) throws IOException {
        try (XWPFDocument doc = new XWPFDocument(new ByteArrayInputStream(body));
             XWPFWordExtractor extractor = new XWPFWordExtractor(doc)) {
            String text = truncate(nullToEmpty(extractor.getText()).strip());
            if (text.isEmpty()) {
                throw unreadable("Word 文档没有可抽取的文字");
            }
            return new ChatDocument(filename, DOCX, text, false, null);
        }
    }

    private ChatDocument extractXlsx(byte[] body, String filename) throws IOException {
        try (Workbook workbook = WorkbookFactory.create(new ByteArrayInputStream(body))) {
            DataFormatter formatter = new DataFormatter();
            StringBuilder sb = new StringBuilder();
            for (Sheet sheet : workbook) {
                if (sb.length() > 0) {
                    sb.append('\n');
                }
                sb.append("sheet=").append(sheet.getSheetName()).append('\n');
                for (Row row : sheet) {
                    boolean first = true;
                    for (Cell cell : row) {
                        if (!first) {
                            sb.append('\t');
                        }
                        first = false;
                        sb.append(formatter.formatCellValue(cell));
                    }
                    sb.append('\n');
                }
            }
            String text = truncate(sb.toString().strip());
            if (text.isEmpty()) {
                throw unreadable("表格没有可抽取的文字");
            }
            return new ChatDocument(filename, XLSX, text, false, null);
        }
    }

    private ChatDocument extractText(byte[] body, String mime, String filename) {
        String text = truncate(new String(body, StandardCharsets.UTF_8).strip());
        if (text.isEmpty()) {
            throw unreadable("文本文件为空");
        }
        return new ChatDocument(filename, mime, text, false, null);
    }

    private String truncate(String text) {
        int max = media.maxExtractChars();
        if (text.length() <= max) {
            return text;
        }
        return text.substring(0, max) + "\n…（已截断）";
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private static BusinessException unreadable(String message) {
        return new BusinessException(ErrorCodeEnum.MEDIA_UNREADABLE, message);
    }
}

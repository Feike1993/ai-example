package com.feike.ai.production.media.manager;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.encryption.AccessPermission;
import org.apache.pdfbox.pdmodel.encryption.StandardProtectionPolicy;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.poi.xwpf.usermodel.XWPFDocument;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * 单测夹具：用 PDFBox / POI 生成最小可读文件，避免手搓损坏的 zip。
 */
public final class ProductionDocumentFixtures {

    private ProductionDocumentFixtures() {
    }

    public static byte[] utf8(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }

    public static byte[] pdfWithText(String text) {
        try (PDDocument doc = new PDDocument();
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            PDPage page = new PDPage();
            doc.addPage(page);
            try (PDPageContentStream stream = new PDPageContentStream(doc, page)) {
                stream.beginText();
                stream.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                stream.newLineAtOffset(72, 700);
                stream.showText(text);
                stream.endText();
            }
            doc.save(out);
            return out.toByteArray();
        } catch (IOException ex) {
            throw new IllegalStateException("生成 PDF 失败", ex);
        }
    }

    public static byte[] blankPdf(int pages) {
        try (PDDocument doc = new PDDocument();
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            for (int i = 0; i < pages; i++) {
                doc.addPage(new PDPage());
            }
            doc.save(out);
            return out.toByteArray();
        } catch (IOException ex) {
            throw new IllegalStateException("生成空白 PDF 失败", ex);
        }
    }

    public static byte[] encryptedPdf() {
        try (PDDocument doc = new PDDocument();
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            doc.addPage(new PDPage());
            AccessPermission permission = new AccessPermission();
            StandardProtectionPolicy policy = new StandardProtectionPolicy("owner", "user", permission);
            policy.setEncryptionKeyLength(128);
            doc.protect(policy);
            doc.save(out);
            return out.toByteArray();
        } catch (IOException ex) {
            throw new IllegalStateException("生成加密 PDF 失败", ex);
        }
    }

    public static byte[] docxWithText(String text) {
        try (XWPFDocument doc = new XWPFDocument();
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            doc.createParagraph().createRun().setText(text);
            doc.write(out);
            return out.toByteArray();
        } catch (IOException ex) {
            throw new IllegalStateException("生成 docx 失败", ex);
        }
    }

    public static byte[] xlsxWithText(String text) {
        try (XSSFWorkbook workbook = new XSSFWorkbook();
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            workbook.createSheet("s1").createRow(0).createCell(0).setCellValue(text);
            workbook.write(out);
            return out.toByteArray();
        } catch (IOException ex) {
            throw new IllegalStateException("生成 xlsx 失败", ex);
        }
    }
}

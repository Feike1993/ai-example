package com.feike.ai.production.media.manager;

import com.feike.ai.production.chat.model.ChatDocument;
import com.feike.ai.production.config.ProductionProperties;
import com.feike.ai.production.web.BusinessException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("ProductionDocumentExtractor")
class ProductionDocumentExtractorTest {

    private final ProductionProperties properties = properties();
    private final ProductionDocumentExtractor extractor = new ProductionDocumentExtractor(properties);
    private final ProductionMediaInspector inspector = new ProductionMediaInspector(properties, null, extractor);

    @Test
    void pdfTxtDocxXlsxShouldExtractWithoutVision() {
        ChatDocument pdf = extractor.extract(
            ProductionDocumentFixtures.pdfWithText("hello pdf document extract"), ProductionDocumentExtractor.PDF, "a.pdf");
        assertTrue(pdf.extractedText().contains("hello pdf document extract"));
        assertFalse(pdf.ocrCandidate());

        ChatDocument txt = extractor.extract(
            ProductionDocumentFixtures.utf8("hello txt"), ProductionDocumentExtractor.TXT, "a.txt");
        assertEquals("hello txt", txt.extractedText());

        ChatDocument docx = extractor.extract(
            ProductionDocumentFixtures.docxWithText("hello docx"), ProductionDocumentExtractor.DOCX, "a.docx");
        assertTrue(docx.extractedText().contains("hello docx"));

        ChatDocument xlsx = extractor.extract(
            ProductionDocumentFixtures.xlsxWithText("hello xlsx"), ProductionDocumentExtractor.XLSX, "a.xlsx");
        assertTrue(xlsx.extractedText().contains("hello xlsx"));
    }

    @Test
    void threeTxtShouldBeTooMany() {
        MockMultipartFile txt = new MockMultipartFile(
            "document", "a.txt", "text/plain", "hello".getBytes(StandardCharsets.UTF_8));
        BusinessException ex = assertThrows(BusinessException.class, () ->
            inspector.inspectDocuments(new MockMultipartFile[] {txt, txt, txt}));
        assertEquals("media_too_many", ex.getErrorCode().getCode());
    }

    @Test
    void exeShouldBeUnsupported() {
        BusinessException ex = assertThrows(BusinessException.class, () ->
            inspector.inspectDocuments(new MockMultipartFile[] {
                new MockMultipartFile("document", "a.exe", "application/octet-stream", new byte[] {'M', 'Z', 0x00})
            }));
        assertEquals("media_unsupported", ex.getErrorCode().getCode());
    }

    @Test
    void encryptedPdfShouldBeUnreadable() {
        BusinessException ex = assertThrows(BusinessException.class, () ->
            extractor.extract(ProductionDocumentFixtures.encryptedPdf(), ProductionDocumentExtractor.PDF, "secret.pdf"));
        assertEquals("media_unreadable", ex.getErrorCode().getCode());
    }

    @Test
    void blankPdfShouldBeOcrCandidateWithoutCallingVision() {
        ChatDocument scanned = extractor.extract(
            ProductionDocumentFixtures.blankPdf(2), ProductionDocumentExtractor.PDF, "scan.pdf");
        assertTrue(scanned.ocrCandidate());
        assertTrue(scanned.extractedText().length() < properties.media().ocrMinChars());
        assertNotNull(scanned.sourceBytes());
    }

    private static ProductionProperties properties() {
        return new ProductionProperties(
            true, "c", 4, 400, 1, true, 60, 4,
            null, null, null, null, null, null, null, null
        );
    }
}

package com.feike.ai.production.media.manager;

import com.feike.ai.production.chat.model.ChatDocument;
import com.feike.ai.production.config.ProductionProperties;
import com.feike.ai.production.secret.manager.ProductionModelFactory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.Message;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;

@DisplayName("ProductionDocumentOcr")
class ProductionDocumentOcrTest {

    @Test
    void scannedPdfShouldUseVisionTranscript() {
        ProductionModelFactory models = mock(ProductionModelFactory.class);
        ChatClient vision = mock(ChatClient.class, RETURNS_DEEP_STUBS);
        when(models.visionClient(any())).thenReturn(vision);
        when(vision.prompt().messages(any(Message.class), any(Message.class)).call().content())
            .thenReturn("扫描件上的字");

        ProductionDocumentOcr ocr = new ProductionDocumentOcr(properties(), models);
        byte[] blank = ProductionDocumentFixtures.blankPdf(2);
        ChatDocument scanned = new ChatDocument("scan.pdf", ProductionDocumentExtractor.PDF, "", true, blank);

        ProductionDocumentOcr.OcrBatch batch = ocr.transcribeIfNeeded(List.of(scanned), "dashscope");

        assertTrue(batch.ocrUsed());
        assertEquals("扫描件上的字", batch.documents().get(0).extractedText());
        assertFalse(batch.documents().get(0).ocrCandidate());
        assertNull(batch.documents().get(0).sourceBytes());
        verify(models).visionClient(any());
    }

    @Test
    void digitalExtractShouldSkipVision() {
        ProductionModelFactory models = mock(ProductionModelFactory.class);
        ProductionDocumentOcr ocr = new ProductionDocumentOcr(properties(), models);
        ChatDocument txt = new ChatDocument("a.txt", ProductionDocumentExtractor.TXT, "hello", false, null);

        ProductionDocumentOcr.OcrBatch batch = ocr.transcribeIfNeeded(List.of(txt), "dashscope");

        assertFalse(batch.ocrUsed());
        assertEquals("hello", batch.documents().get(0).extractedText());
        verify(models, never()).visionClient(any());
    }

    private static ProductionProperties properties() {
        return new ProductionProperties(
            true, "c", 4, 400, 1, true, 60, 4,
            null, null, null, null, null, null, null, null
        );
    }
}

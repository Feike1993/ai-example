package com.feike.ai.production.media.manager;

import com.feike.ai.production.chat.model.ChatImage;
import com.feike.ai.production.config.ProductionProperties;
import com.feike.ai.production.secret.manager.ProductionModelFactory;
import com.feike.ai.production.web.BusinessException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.Message;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("ProductionImageDescribe")
class ProductionImageDescribeTest {

    @Test
    void jpegShouldUseVisionTranscript() {
        ProductionModelFactory models = mock(ProductionModelFactory.class);
        ChatClient vision = mock(ChatClient.class, RETURNS_DEEP_STUBS);
        when(models.visionClient(any())).thenReturn(vision);
        when(vision.prompt().messages(any(Message.class), any(Message.class)).call().content())
            .thenReturn("图里写着 hello");

        ProductionImageDescribe describe = new ProductionImageDescribe(properties(), models);
        byte[] jpeg = new byte[] {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, 0x00};
        ProductionImageDescribe.DescribeBatch batch = describe.describe(
            List.of(new ChatImage(jpeg, "image/jpeg")), "dashscope");

        assertTrue(batch.used());
        assertEquals("图里写着 hello", batch.transcript());
        verify(models).visionClient(any());
    }

    @Test
    void emptyImagesShouldSkipVision() {
        ProductionModelFactory models = mock(ProductionModelFactory.class);
        ProductionImageDescribe describe = new ProductionImageDescribe(properties(), models);

        ProductionImageDescribe.DescribeBatch batch = describe.describe(List.of(), "dashscope");

        assertFalse(batch.used());
        assertEquals("", batch.transcript());
        verify(models, never()).visionClient(any());
    }

    @Test
    void emptyVisionOutputShouldBeUnreadable() {
        ProductionModelFactory models = mock(ProductionModelFactory.class);
        ChatClient vision = mock(ChatClient.class, RETURNS_DEEP_STUBS);
        when(models.visionClient(any())).thenReturn(vision);
        when(vision.prompt().messages(any(Message.class), any(Message.class)).call().content())
            .thenReturn("  ");

        ProductionImageDescribe describe = new ProductionImageDescribe(properties(), models);
        byte[] jpeg = new byte[] {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, 0x00};

        assertThrows(BusinessException.class, () ->
            describe.describe(List.of(new ChatImage(jpeg, "image/jpeg")), "dashscope"));
    }

    private static ProductionProperties properties() {
        return new ProductionProperties(
            true, "c", 4, 400, 1, true, 60, 4,
            null, null, null, null, null, null, null, null
        );
    }
}

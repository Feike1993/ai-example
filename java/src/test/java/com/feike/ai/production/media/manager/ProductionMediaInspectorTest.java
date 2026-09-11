package com.feike.ai.production.media.manager;

import com.feike.ai.production.config.ProductionProperties;
import com.feike.ai.production.media.model.MediaKindEnum;
import com.feike.ai.production.observability.service.ProductionMetrics;
import com.feike.ai.production.web.BusinessException;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("ProductionMediaInspector")
class ProductionMediaInspectorTest {

    /** 最小 JPEG 魔数，足以通过探针而不解码。 */
    static final byte[] TINY_JPEG = new byte[] {
        (byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 0x00, 0x10, 0x4A, 0x46, 0x49, 0x46, 0x00, 0x01
    };

    private final ProductionMetrics metrics = new ProductionMetrics(new SimpleMeterRegistry());
    private final ProductionMediaInspector inspector = new ProductionMediaInspector(properties(), metrics);

    @Test
    void jpegShouldBeAcceptedWithoutCallingModel() {
        var result = inspector.inspectAny(new MockMultipartFile(
            "file", "tiny.jpg", "image/jpeg", TINY_JPEG));
        assertEquals("image/jpeg", result.mime());
        assertEquals(TINY_JPEG.length, result.bytes());
        assertEquals(64, result.sha256().length());
        assertEquals(1.0, metrics.snapshot().get("mediaAccepted").doubleValue());
    }

    @Test
    void textFileShouldBeUnsupported() {
        BusinessException ex = assertThrows(BusinessException.class, () ->
            inspector.inspectAny(new MockMultipartFile("file", "a.txt", "text/plain", "hello".getBytes())));
        assertEquals("media_unsupported", ex.getErrorCode().getCode());
        assertEquals(1.0, metrics.snapshot().get("mediaRejected").doubleValue());
    }

    @Test
    void fourImagesShouldBeTooManyWithoutAccepting() {
        MockMultipartFile jpeg = jpegFile();
        BusinessException ex = assertThrows(BusinessException.class, () ->
            inspector.inspectImages(new MockMultipartFile[] {jpeg, jpeg, jpeg, jpeg}));
        assertEquals("media_too_many", ex.getErrorCode().getCode());
        assertEquals(1.0, metrics.snapshot().get("mediaRejected").doubleValue());
        assertEquals(0.0, metrics.snapshot().get("mediaAccepted").doubleValue());
    }

    @Test
    void emptyImagesShouldBeNoOp() {
        assertTrue(inspector.inspectImages(null).isEmpty());
        assertTrue(inspector.inspectImages(new MockMultipartFile[0]).isEmpty());
    }

    @Test
    void threeJpegsShouldPass() {
        MockMultipartFile jpeg = jpegFile();
        var images = inspector.inspectImages(new MockMultipartFile[] {jpeg, jpeg, jpeg});
        assertEquals(3, images.size());
        assertEquals("image/jpeg", images.get(0).mime());
        assertEquals(3.0, metrics.snapshot().get("mediaAccepted").doubleValue());
    }

    private static MockMultipartFile jpegFile() {
        return new MockMultipartFile("image", "tiny.jpg", "image/jpeg", TINY_JPEG);
    }

    @Test
    void oversizedShouldBeTooLarge() {
        byte[] body = new byte[(int) ProductionProperties.Media.DEFAULT_MAX_BYTES + 1];
        body[0] = (byte) 0xFF;
        body[1] = (byte) 0xD8;
        body[2] = (byte) 0xFF;
        BusinessException ex = assertThrows(BusinessException.class, () ->
            inspector.inspectBytes(body, "image/jpeg", MediaKindEnum.IMAGE));
        assertEquals("media_too_large", ex.getErrorCode().getCode());
    }

    @Test
    void audioMimeShouldRejectOnImageInspect() {
        BusinessException ex = assertThrows(BusinessException.class, () ->
            inspector.inspectImage(new MockMultipartFile(
                "file", "a.webm", "audio/webm", new byte[] {0x1A, 0x45, (byte) 0xDF, (byte) 0xA3})));
        assertEquals("media_unsupported", ex.getErrorCode().getCode());
    }

    @Test
    void webmShouldPassAudioInspect() {
        byte[] webm = new byte[] {0x1A, 0x45, (byte) 0xDF, (byte) 0xA3, 0x01};
        var result = inspector.inspectAudio(new MockMultipartFile("audio", "a.webm", "audio/webm", webm));
        assertEquals("audio/webm", result.mime());
        assertTrue(result.bytes() > 0);
    }

    private static ProductionProperties properties() {
        return new ProductionProperties(
            true, "c", 4, 400, 1, true, 60, 4,
            null, null, null, null, null, null, null, null
        );
    }
}

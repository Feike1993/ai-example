package com.feike.ai.production.speech.service.impl;

import com.feike.ai.production.config.ProductionProperties;
import com.feike.ai.production.media.manager.ProductionMediaInspector;
import com.feike.ai.production.speech.manager.DashScopeSpeechClient;
import com.feike.ai.production.speech.model.TranscribeVO;
import com.feike.ai.production.speech.service.ProductionSpeechService;
import com.feike.ai.production.web.BusinessException;
import com.feike.ai.production.web.ErrorCodeEnum;
import org.springframework.web.multipart.MultipartFile;

/**
 * 转写先走媒体校验再调网关；合成立即校验长度，护栏由控制器先做。
 */
public class ProductionSpeechServiceImpl implements ProductionSpeechService {

    private final ProductionMediaInspector inspector;
    private final DashScopeSpeechClient client;
    private final ProductionProperties.Media media;

    /**
     * @param inspector mime / 大小
     * @param client    DashScope HTTP
     * @param properties 长度上限
     */
    public ProductionSpeechServiceImpl(
        ProductionMediaInspector inspector,
        DashScopeSpeechClient client,
        ProductionProperties properties
    ) {
        this.inspector = inspector;
        this.client = client;
        this.media = properties.media();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public TranscribeVO transcribe(MultipartFile audio) {
        ProductionMediaInspector.InspectedMedia inspected = inspector.inspectAudioBody(audio);
        String filename = audio.getOriginalFilename();
        String text = client.transcribe(inspected.body(), inspected.probe().mime(), filename);
        return new TranscribeVO(text == null ? "" : text.trim());
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public byte[] speak(String text) {
        String trimmed = text == null ? "" : text.trim();
        if (trimmed.isEmpty()) {
            throw new BusinessException(ErrorCodeEnum.BAD_REQUEST, "朗读文本不能为空");
        }
        if (trimmed.length() > media.maxSpeakChars()) {
            throw new BusinessException(
                ErrorCodeEnum.BAD_REQUEST,
                "朗读文本超过 " + media.maxSpeakChars() + " 字上限"
            );
        }
        return client.speak(trimmed);
    }
}

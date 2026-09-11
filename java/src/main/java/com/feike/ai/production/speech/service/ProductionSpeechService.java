package com.feike.ai.production.speech.service;

import com.feike.ai.production.speech.model.TranscribeVO;
import org.springframework.web.multipart.MultipartFile;

/**
 * 本机语音演示：ASR / TTS。不写入会话、不落盘。
 */
public interface ProductionSpeechService {

    /**
     * 语音转写。
     *
     * @param audio 音频
     * @return 文本
     */
    TranscribeVO transcribe(MultipartFile audio);

    /**
     * 语音合成。
     *
     * @param text 已通过护栏的文本
     * @return mpeg 字节
     */
    byte[] speak(String text);
}

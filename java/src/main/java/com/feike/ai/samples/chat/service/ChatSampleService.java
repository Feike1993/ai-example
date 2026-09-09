package com.feike.ai.samples.chat.service;

import com.feike.ai.samples.chat.model.ChatDTO;
import reactor.core.publisher.Flux;

/**
 * Chat 样例：同步补全与 token 流。
 */
public interface ChatSampleService {

    /**
     * 一次性返回完整回复。
     *
     * @param prompt      用户输入
     * @param temperature 为空则用工厂默认温度
     * @param provider    Provider id，空则用默认 DeepSeek
     * @return 回复正文与 token 用量
     */
    ChatDTO chat(String prompt, Double temperature, String provider);

    /**
     * 按 token 流式输出，用于观察 TTFT。
     *
     * @param prompt   用户输入
     * @param provider Provider id，空则用默认 DeepSeek
     * @return 增量文本流
     */
    Flux<String> stream(String prompt, String provider);
}

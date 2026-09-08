package com.feike.ai.production.rag.generate;

import com.feike.ai.core.LlmProviderRegistry;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.document.Document;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * 生成层：把检索命中拼成受限上下文，交给 LLM 出答案。
 * <p>
 * 只做一件事——「基于给定上下文生成」。检索、空检索判定、事件推送都不在这里，
 * 这样换模型、改 prompt、加 citation 约束都只动这一个类，
 * 也让它可以脱离向量库和 Redis 单测。
 * <p>
 * 多轮历史由调用方裁剪后传进来，本类不碰存储也不做预算——
 * 「裁多少」是会话策略，「怎么问」才是这里的职责。
 */
public class ProductionAnswerGenerator {

    /** 空检索时的固定拒答，绕过 LLM。 */
    public static final String EMPTY_REFUSAL =
        "知识库中没有检索到与该问题相关的内容，无法作答。请换个问法，或确认相关文档已入库。";

    private static final String SYSTEM_GROUNDED = """
        你是企业知识库助手。只根据「检索上下文」回答用户问题；不要编造，也不要用上下文之外的知识补全。
        若用户一题多问：能被上下文支撑的子问先正面回答，不能支撑的子问单独说明无法回答。
        禁止因为某个子问答不了就对整题回复「不知道」。
        上下文整体为空或与全部子问都无关时，才可以说不知道。
        回答用简体中文。
        """;

    private final LlmProviderRegistry registry;

    /**
     * @param registry 多 Provider 注册表
     */
    public ProductionAnswerGenerator(LlmProviderRegistry registry) {
        this.registry = registry;
    }

    /**
     * 同步生成。
     *
     * @param question 用户问题
     * @param provider Chat Provider id；空则用默认
     * @param hits     检索命中
     * @param history  已裁剪的多轮历史，可为空
     * @return 答案正文
     */
    public String generate(String question, String provider, List<Document> hits, List<Message> history) {
        String answer = registry.plainClient(provider)
            .prompt()
            .messages(buildMessages(question, hits, history))
            .call()
            .content();
        return answer == null ? "" : answer;
    }

    /**
     * 流式生成，逐块回调。
     * <p>
     * 用阻塞式 {@code toStream()} 而不是把 Flux 交出去：调用方运行在虚拟线程上，
     * 这样「取消」就是普通的中断语义，不必在两套并发模型之间桥接。
     *
     * @param question 用户问题
     * @param provider Chat Provider id；空则用默认
     * @param hits     检索命中
     * @param history  已裁剪的多轮历史，可为空
     * @param onChunk  增量回调
     */
    public void stream(
        String question,
        String provider,
        List<Document> hits,
        List<Message> history,
        Consumer<String> onChunk
    ) {
        registry.plainClient(provider)
            .prompt()
            .messages(buildMessages(question, hits, history))
            .stream()
            .content()
            .toStream()
            .forEach(chunk -> {
                if (chunk != null && !chunk.isEmpty()) {
                    onChunk.accept(chunk);
                }
            });
    }

    /**
     * 组装完整消息序列：system → 历史 → 本轮（含检索上下文）。
     * <p>
     * 自己拼列表而不是用 {@code .system()} + {@code .user()}：检索上下文只跟当前这一轮走，
     * 必须排在历史之后。如果交给 ChatClient 分别设置，system / history / user 的相对次序
     * 就变成了框架实现细节，而这个次序直接决定模型把上下文关联到哪一轮。
     *
     * @param question 用户问题
     * @param hits     检索命中
     * @param history  已裁剪的多轮历史，可为空
     * @return 送入模型的消息序列
     */
    static List<Message> buildMessages(String question, List<Document> hits, List<Message> history) {
        List<Message> messages = new ArrayList<>();
        messages.add(new SystemMessage(SYSTEM_GROUNDED));
        if (history != null) {
            messages.addAll(history);
        }
        messages.add(new UserMessage(buildUserMessage(question, hits)));
        return messages;
    }

    /**
     * 拼装带编号来源的用户消息。
     *
     * @param question 用户问题
     * @param hits     检索命中
     * @return 完整用户消息
     */
    static String buildUserMessage(String question, List<Document> hits) {
        StringBuilder sb = new StringBuilder();
        sb.append("检索上下文：\n");
        if (hits == null || hits.isEmpty()) {
            sb.append("（无检索结果）\n");
        } else {
            for (int i = 0; i < hits.size(); i++) {
                Document doc = hits.get(i);
                sb.append("[").append(i + 1).append("] source=")
                    .append(doc.getMetadata() == null ? "?" : doc.getMetadata().getOrDefault("source", "?"))
                    .append("\n")
                    .append(doc.getText() == null ? "" : doc.getText())
                    .append("\n\n");
            }
        }
        sb.append("用户问题：").append(question == null ? "" : question);
        return sb.toString();
    }
}

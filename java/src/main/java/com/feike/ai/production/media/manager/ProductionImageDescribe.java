package com.feike.ai.production.media.manager;

import com.feike.ai.production.chat.model.ChatImage;
import com.feike.ai.production.config.ProductionProperties;
import com.feike.ai.production.secret.manager.ProductionModelFactory;
import com.feike.ai.production.secret.service.SecretUnavailableException;
import com.feike.ai.production.web.BusinessException;
import com.feike.ai.production.web.ErrorCodeEnum;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.content.Media;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.util.MimeType;
import org.springframework.util.MimeTypeUtils;

import java.util.List;

/**
 * Agent 本轮看图：现有 VL 只转写/简述，不调工具、不作答用户任务。
 * <p>
 * 默认 {@code qwen-vl-plus} 不支持 Function Calling，不能把视觉模型挂进工具循环；
 * 转写正文交给文本 {@code ChatModel} 再跑 Agent。位图用完即弃。
 */
public class ProductionImageDescribe {

    private static final String DESCRIBE_SYSTEM = """
        你只转写或简述图中可见内容。只根据画面本身，不要回答用户任务，不要调工具，不要发挥或猜测意图。
        多张图时按顺序用 [图片1]、[图片2] 分段。若没有可见内容则输出空。
        """;

    private final ProductionProperties.Media media;
    private final ProductionModelFactory models;

    /**
     * @param properties 抽出上限
     * @param models     视觉客户端
     */
    public ProductionImageDescribe(ProductionProperties properties, ProductionModelFactory models) {
        this.media = properties.media();
        this.models = models;
    }

    /**
     * 用视觉模型把本轮图片转写成受控文本，供不支持图片或 Function Calling 的 Agent 后续使用。
     * <p>
     * 本方法只描述图片中的可见内容，不直接回答用户问题，也不触发工具调用。图片按输入顺序组装到
     * 同一条用户消息中，视觉模型通过 {@code [图片1]}、{@code [图片2]} 等标记保留多图之间的
     * 对应关系。模型返回的正文受 {@code maxExtractChars} 限制，避免图片描述无限扩张 Agent 上下文。
     * <p>
     * 调用方必须先完成文件大小、MIME 和文件特征校验；本方法只负责把已校验的图片适配成模型媒体，
     * 不重复承担上传边界检查。
     *
     * @param images   已校验的图片，顺序即最终转写顺序；为空时不调用视觉模型
     * @param provider 视觉模型 Provider；为空时由模型工厂使用默认配置
     * @return 图片转写正文及是否实际调用过视觉模型
     * @throws BusinessException 当模型未识别出有效内容或视觉调用失败时
     * @throws SecretUnavailableException 当所选 Provider 的密钥不可用时
     */
    public DescribeBatch describe(List<ChatImage> images, String provider) {
        // 空输入直接返回“未使用”，既避免无意义的模型请求，也让调用方准确记录本轮是否发生视觉调用。
        if (images == null || images.isEmpty()) {
            return new DescribeBatch("", false);
        }

        Media[] mediaParts = new Media[images.size()];
        for (int i = 0; i < images.size(); i++) {
            ChatImage image = images.get(i);
            String raw = image.mime();
            // 正常情况下 MIME 已由媒体检查器归一化；JPEG 兜底只用于兼容缺少元数据的内部对象，
            // 避免在构造 Spring AI Media 时因空 MIME 中断整批转写。
            MimeType mime = MimeType.valueOf(raw == null || raw.isBlank()
                ? MimeTypeUtils.IMAGE_JPEG_VALUE
                : raw);
            // 按列表索引原样组装，确保模型输出中的图片编号能与用户上传顺序对应。
            mediaParts[i] = new Media(mime, new ByteArrayResource(image.bytes()));
        }

        try {
            String content = models.visionClient(provider)
                .prompt()
                .messages(
                    // SystemMessage 从能力边界上限制模型只做“看图转写”，避免视觉阶段提前回答或臆测用户意图。
                    new SystemMessage(DESCRIBE_SYSTEM),
                    UserMessage.builder()
                        // UserMessage 再次强调顺序和任务边界，并将所有图片放入一次调用以保留跨图上下文。
                        .text("只按顺序简述每张图中可见内容，不要回答用户任务。")
                        .media(mediaParts)
                        .build()
                )
                .call()
                .content();

            // 统一去除模型输出首尾空白；null 或纯空白都表示没有得到可供 Agent 使用的视觉上下文。
            String text = content == null ? "" : content.strip();
            if (text.isEmpty()) {
                throw new BusinessException(ErrorCodeEnum.MEDIA_UNREADABLE, "未能从图片识别出可见内容");
            }

            int max = media.maxExtractChars();
            if (text.length() > max) {
                // 与文档抽取共用字符上限，防止超长视觉描述挤占 Agent 上下文；显式标记可避免误认为内容完整。
                text = text.substring(0, max) + "\n…（已截断）";
            }
            return new DescribeBatch(text, true);
        } catch (BusinessException | SecretUnavailableException ex) {
            // 保留可读媒体错误及密钥不可用的原始语义，交由统一异常层映射成对应 HTTP 状态。
            throw ex;
        } catch (RuntimeException ex) {
            // 隔离供应商 SDK 的实现异常，不向接口泄漏底层细节，并统一呈现为图片内容不可读。
            throw new BusinessException(ErrorCodeEnum.MEDIA_UNREADABLE, "图片内容识别失败");
        }
    }

    /**
     * @param transcript 转写/简述正文
     * @param used       是否调用过 VL
     */
    public record DescribeBatch(String transcript, boolean used) {}
}

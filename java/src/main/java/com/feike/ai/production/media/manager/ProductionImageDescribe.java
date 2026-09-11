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
     * 用 VL 转写本轮图片；无图则跳过。
     *
     * @param images   已校验的图
     * @param provider 视觉 Provider；空则用配置默认
     * @return 转写正文与是否调用过 VL
     */
    public DescribeBatch describe(List<ChatImage> images, String provider) {
        if (images == null || images.isEmpty()) {
            return new DescribeBatch("", false);
        }
        Media[] mediaParts = new Media[images.size()];
        for (int i = 0; i < images.size(); i++) {
            ChatImage image = images.get(i);
            String raw = image.mime();
            MimeType mime = MimeType.valueOf(raw == null || raw.isBlank()
                ? MimeTypeUtils.IMAGE_JPEG_VALUE
                : raw);
            mediaParts[i] = new Media(mime, new ByteArrayResource(image.bytes()));
        }
        try {
            String content = models.visionClient(provider)
                .prompt()
                .messages(
                    new SystemMessage(DESCRIBE_SYSTEM),
                    UserMessage.builder()
                        .text("只按顺序简述每张图中可见内容，不要回答用户任务。")
                        .media(mediaParts)
                        .build()
                )
                .call()
                .content();
            String text = content == null ? "" : content.strip();
            if (text.isEmpty()) {
                throw new BusinessException(ErrorCodeEnum.MEDIA_UNREADABLE, "未能从图片识别出可见内容");
            }
            int max = media.maxExtractChars();
            if (text.length() > max) {
                text = text.substring(0, max) + "\n…（已截断）";
            }
            return new DescribeBatch(text, true);
        } catch (BusinessException | SecretUnavailableException ex) {
            throw ex;
        } catch (RuntimeException ex) {
            throw new BusinessException(ErrorCodeEnum.MEDIA_UNREADABLE, "图片内容识别失败");
        }
    }

    /**
     * @param transcript 转写/简述正文
     * @param used       是否调用过 VL
     */
    public record DescribeBatch(String transcript, boolean used) {}
}

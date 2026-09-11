package com.feike.ai.production.media.manager;

import com.feike.ai.production.chat.model.ChatImage;
import com.feike.ai.production.config.ProductionProperties;
import com.feike.ai.production.media.model.MediaKindEnum;
import com.feike.ai.production.media.model.MediaProbeVO;
import com.feike.ai.production.observability.service.ProductionMetrics;
import com.feike.ai.production.web.BusinessException;
import com.feike.ai.production.web.ErrorCodeEnum;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;

/**
 * 只校验 mime / 体积并算 SHA-256，不调模型、不落盘。
 * <p>
 * 探针、带图问答、转写共用这一把尺子，避免三处各写各的上限。
 */
public class ProductionMediaInspector {

    private final ProductionProperties.Media media;
    private final ProductionMetrics metrics;

    /**
     * @param properties 允许列表与上限
     * @param metrics    接受 / 拒绝计数；可空
     */
    public ProductionMediaInspector(ProductionProperties properties, ProductionMetrics metrics) {
        this.media = properties.media();
        this.metrics = metrics;
    }

    /**
     * 探针：图片或音频均可。
     *
     * @param file 上传
     * @return 摘要
     */
    public MediaProbeVO inspectAny(MultipartFile file) {
        return inspect(file, MediaKindEnum.ANY);
    }

    /**
     * 识图附件。
     *
     * @param file 上传
     * @return 摘要
     */
    public MediaProbeVO inspectImage(MultipartFile file) {
        return inspect(file, MediaKindEnum.IMAGE);
    }

    /**
     * 一轮问答的多张图：先数张数，再逐张走 jpeg/png/webp 校验。
     * <p>
     * 空数组视为无图。超过 {@code max-images} 直接 422，避免先 accept 再拒把指标打乱。
     *
     * @param files 同名 {@code image} parts；可空
     * @return 已校验的附件；无图时为空列表
     */
    public List<ChatImage> inspectImages(MultipartFile[] files) {
        List<MultipartFile> present = new ArrayList<>();
        if (files != null) {
            for (MultipartFile file : files) {
                if (file != null && !file.isEmpty()) {
                    present.add(file);
                }
            }
        }
        if (present.size() > media.maxImages()) {
            reject(ErrorCodeEnum.MEDIA_TOO_MANY,
                "一次最多上传 " + media.maxImages() + " 张图片");
        }
        List<ChatImage> images = new ArrayList<>();
        for (MultipartFile file : present) {
            MediaProbeVO probe = inspectImage(file);
            try {
                images.add(new ChatImage(file.getBytes(), probe.mime()));
            } catch (IOException ex) {
                throw new BusinessException(ErrorCodeEnum.BAD_REQUEST, "无法读取图片");
            }
        }
        return List.copyOf(images);
    }

    /**
     * 转写音频。
     *
     * @param file 上传
     * @return 摘要
     */
    public MediaProbeVO inspectAudio(MultipartFile file) {
        return inspect(file, MediaKindEnum.AUDIO);
    }

    /**
     * 校验内存中的字节（带图 SSE 已读过 Multipart）。
     *
     * @param body        文件体
     * @param contentType 声明的 mime
     * @param kind        期望种类
     * @return 摘要
     */
    public MediaProbeVO inspectBytes(byte[] body, String contentType, MediaKindEnum kind) {
        if (body == null || body.length == 0) {
            reject(ErrorCodeEnum.BAD_REQUEST, "未上传文件");
        }
        if (body.length > media.maxBytes()) {
            reject(ErrorCodeEnum.MEDIA_TOO_LARGE,
                "文件超过大小上限（" + media.maxBytes() + " 字节）");
        }
        String mime = ProductionProperties.Media.normalizeMime(contentType);
        if (mime.isBlank()) {
            mime = sniffMime(body);
        }
        if (!allowed(mime, kind) || !magicMatches(mime, body)) {
            reject(ErrorCodeEnum.MEDIA_UNSUPPORTED, "不支持的媒体类型");
        }
        MediaProbeVO result = new MediaProbeVO(mime, body.length, sha256(body));
        if (metrics != null) {
            metrics.mediaAccepted();
        }
        return result;
    }

    private MediaProbeVO inspect(MultipartFile file, MediaKindEnum kind) {
        if (file == null || file.isEmpty()) {
            reject(ErrorCodeEnum.BAD_REQUEST, "未上传文件");
        }
        byte[] body;
        try {
            body = file.getBytes();
        } catch (IOException ex) {
            throw new BusinessException(ErrorCodeEnum.BAD_REQUEST, "无法读取上传文件");
        }
        String declared = file.getContentType();
        if (declared == null || declared.isBlank()) {
            declared = file.getOriginalFilename() == null
                ? ""
                : mimeFromName(file.getOriginalFilename());
        }
        return inspectBytes(body, declared, kind);
    }

    private boolean allowed(String mime, MediaKindEnum kind) {
        List<String> images = media.imageMimes();
        List<String> audios = media.audioMimes();
        return switch (kind) {
            case IMAGE -> images.contains(mime);
            case AUDIO -> audios.contains(mime);
            case ANY -> images.contains(mime) || audios.contains(mime);
        };
    }

    private static boolean magicMatches(String mime, byte[] body) {
        if (mime.startsWith("image/")) {
            return switch (mime) {
                case "image/jpeg" -> startsWith(body, (byte) 0xFF, (byte) 0xD8, (byte) 0xFF);
                case "image/png" -> startsWith(body, (byte) 0x89, 0x50, 0x4E, 0x47);
                case "image/webp" -> body.length >= 12
                    && startsWith(body, 'R', 'I', 'F', 'F')
                    && body[8] == 'W' && body[9] == 'E' && body[10] == 'B' && body[11] == 'P';
                default -> false;
            };
        }
        if ("audio/wav".equals(mime)) {
            return body.length >= 12
                && startsWith(body, 'R', 'I', 'F', 'F')
                && body[8] == 'W' && body[9] == 'A' && body[10] == 'V' && body[11] == 'E';
        }
        // webm / mpeg 容器花样多，允许列表 + 非空即可，避免误杀浏览器录音
        return body.length > 0;
    }

    private static String sniffMime(byte[] body) {
        if (startsWith(body, (byte) 0xFF, (byte) 0xD8, (byte) 0xFF)) {
            return "image/jpeg";
        }
        if (startsWith(body, (byte) 0x89, 0x50, 0x4E, 0x47)) {
            return "image/png";
        }
        if (body.length >= 12 && startsWith(body, 'R', 'I', 'F', 'F')
            && body[8] == 'W' && body[9] == 'E' && body[10] == 'B' && body[11] == 'P') {
            return "image/webp";
        }
        if (body.length >= 12 && startsWith(body, 'R', 'I', 'F', 'F')
            && body[8] == 'W' && body[9] == 'A' && body[10] == 'V' && body[11] == 'E') {
            return "audio/wav";
        }
        return "";
    }

    private static String mimeFromName(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) {
            return "image/jpeg";
        }
        if (lower.endsWith(".png")) {
            return "image/png";
        }
        if (lower.endsWith(".webp")) {
            return "image/webp";
        }
        if (lower.endsWith(".wav")) {
            return "audio/wav";
        }
        if (lower.endsWith(".webm")) {
            return "audio/webm";
        }
        if (lower.endsWith(".mp3") || lower.endsWith(".mpeg")) {
            return "audio/mpeg";
        }
        return "";
    }

    private static boolean startsWith(byte[] body, int... prefix) {
        if (body.length < prefix.length) {
            return false;
        }
        for (int i = 0; i < prefix.length; i++) {
            if ((body[i] & 0xFF) != (prefix[i] & 0xFF)) {
                return false;
            }
        }
        return true;
    }

    private static String sha256(byte[] body) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(body);
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 不可用", ex);
        }
    }

    private void reject(ErrorCodeEnum code, String message) {
        if (metrics != null) {
            metrics.mediaRejected();
        }
        throw new BusinessException(code, message);
    }
}

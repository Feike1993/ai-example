package com.feike.ai.production.media.service;

import com.feike.ai.production.media.model.MediaProbeVO;
import org.springframework.web.multipart.MultipartFile;

/**
 * 媒体探针：只校验，不调模型。
 */
public interface ProductionMediaService {

    /**
     * 校验 mime / 大小并返回摘要。
     *
     * @param file 上传文件
     * @return mime、字节数、SHA-256
     */
    MediaProbeVO probe(MultipartFile file);
}

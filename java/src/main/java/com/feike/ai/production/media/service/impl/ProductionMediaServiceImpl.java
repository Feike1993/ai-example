package com.feike.ai.production.media.service.impl;

import com.feike.ai.production.media.manager.ProductionMediaInspector;
import com.feike.ai.production.media.model.MediaProbeVO;
import com.feike.ai.production.media.service.ProductionMediaService;
import org.springframework.web.multipart.MultipartFile;

/**
 * 探针委托 {@link ProductionMediaInspector}，保持控制器不含校验细节。
 */
public class ProductionMediaServiceImpl implements ProductionMediaService {

    private final ProductionMediaInspector inspector;

    /**
     * @param inspector 校验器
     */
    public ProductionMediaServiceImpl(ProductionMediaInspector inspector) {
        this.inspector = inspector;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public MediaProbeVO probe(MultipartFile file) {
        return inspector.inspectAny(file);
    }
}

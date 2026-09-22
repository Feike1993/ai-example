package com.feike.ai.production.modelsettings.model;

import java.util.List;

/** 管理页的完整只读视图。 */
public record ModelSettingsVO(List<GlobalProviderVO> providers, List<ModelRouteVO> routes) {}

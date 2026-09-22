package com.feike.ai.production.modelsettings.model;

/** 一项能力到具体 Provider/模型的全局路由。 */
public record ModelRouteVO(String capability, String providerId, String model, String voice) {}

package com.feike.ai.production.agent.model;

import java.util.List;

/**
 * 当前身份允许看见的工具。
 *
 * @param tools 工具名，稳定顺序
 */
public record AgentToolsVO(List<String> tools) {}

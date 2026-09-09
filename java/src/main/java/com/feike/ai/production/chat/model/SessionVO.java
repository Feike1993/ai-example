package com.feike.ai.production.chat.model;

import com.feike.ai.production.session.model.SessionMessageDO;

import java.util.List;

/**
 * 会话历史展示。
 *
 * @param sessionId 会话 id
 * @param messages  按 seq 升序的消息
 */
public record SessionVO(String sessionId, List<SessionMessageDO> messages) {}

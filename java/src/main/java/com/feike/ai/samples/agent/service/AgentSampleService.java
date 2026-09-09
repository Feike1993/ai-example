package com.feike.ai.samples.agent.service;

import com.feike.ai.core.model.TokenUsageDTO;
import com.feike.ai.samples.agent.service.ReactAgentLoop;
import org.springframework.http.codec.ServerSentEvent;
import reactor.core.publisher.Flux;

/** AgentSampleService 业务门面。 */
public interface AgentSampleService {

    record FrameworkResult(String content, TokenUsageDTO usage) {}
    ReactAgentLoop.Trace react(String prompt, Integer maxSteps, String provider);
    Flux<ServerSentEvent<String>> reactStream(String prompt, Integer maxSteps, String provider);
    FrameworkResult framework(String prompt, String provider);
}

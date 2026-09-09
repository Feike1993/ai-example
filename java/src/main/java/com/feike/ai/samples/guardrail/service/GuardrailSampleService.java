package com.feike.ai.samples.guardrail.service;

import com.feike.ai.core.model.TokenUsageDTO;
import java.util.List;

/** GuardrailSampleService 业务门面。 */
public interface GuardrailSampleService {

    String BLOCKED_REFUSAL = "请求或回复未通过护栏检查，已拒绝生成/展示内容。请修改表述后重试。";
    record Check(String name, boolean passed, String detail) {}
    record SafeEnvelope(boolean safe, String content) {}
    record GuardrailResult(boolean blocked, String blockStage, String answer, List<Check> checks, TokenUsageDTO usage) {}
    GuardrailResult chat(String prompt, String provider, boolean requireStructured);
}

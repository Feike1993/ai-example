package com.feike.ai.samples.eval.service;

import com.feike.ai.core.model.TokenUsageDTO;
import com.feike.ai.samples.eval.service.EvalGoldenLoader.EvalCase;
import java.util.List;

/** EvalSampleService 业务门面。 */
public interface EvalSampleService {

    record EvalRunResult(int total, int passed, int failed, List<EvalCaseResult> cases, TokenUsageDTO usageSummary) {}
    record EvalCaseResult(String id, boolean passed, long durationMs, int steps, int toolFailures, String error, String answer, TokenUsageDTO usage) {}
    EvalRunResult runAll(String provider);
    EvalCaseResult runCase(EvalCase evalCase, String provider);
}

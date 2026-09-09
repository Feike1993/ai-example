package com.feike.ai.samples.structured.service;

import com.feike.ai.core.model.TokenUsageDTO;
import java.util.List;

/** StructuredSampleService 业务门面。 */
public interface StructuredSampleService {

    record Ticket(String title, String priority, List<String> labels, String summary) {}
    record ExtractResult(Ticket ticket, TokenUsageDTO usage) {}
    ExtractResult extract(String userText, String provider);
}

package com.feike.ai.production.rag.ingest.service;

import java.util.List;

/** ProductionIngestService 业务门面。 */
public interface ProductionIngestService {

    String META_CORPUS = "corpus";
    String META_TENANT_ID = "tenant_id";
    record IngestResult(String corpus, int chunkCount, List<String> sources) {}
    IngestResult ingest();
    IngestResult ingest(String tenantId);
}

package com.feike.ai.production.agent.manager;

import com.feike.ai.production.config.ProductionProperties;
import com.feike.ai.production.auth.model.ProductionPrincipal;
import com.feike.ai.production.rag.ingest.service.ProductionIngestService;
import com.feike.ai.production.rag.retrieve.service.ProductionRetrievalService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@DisplayName("ProductionTools")
class ProductionToolsTest {

    @Test
    void userRebuildIndexShouldNotCallIngest() {
        ProductionIngestService ingest = mock(ProductionIngestService.class);
        ProductionTools tools = new ProductionTools(
            mock(ProductionRetrievalService.class),
            ingest,
            new ProductionPrincipal("alice", "tenant-a", Set.of("USER")),
            new ProductionProperties(true, "c", 4, 400, 1, true, 60, 4, null, null, null, null, null, null)
        );
        String result = tools.rebuildIndex();
        assertTrue(result.startsWith("denied"));
        verify(ingest, never()).ingest();
        verify(ingest, never()).ingest(org.mockito.ArgumentMatchers.anyString());
    }
}

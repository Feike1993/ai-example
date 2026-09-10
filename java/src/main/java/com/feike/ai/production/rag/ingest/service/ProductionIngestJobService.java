package com.feike.ai.production.rag.ingest.service;

import com.feike.ai.production.rag.ingest.model.IngestJobVO;

import java.util.Optional;

/** ProductionIngestJobService 业务门面。 */
public interface ProductionIngestJobService {

    /**
     * 投递一次幂等重建。同一 corpus 已有未完成任务时复用该 job，不重复入队。
     *
     * @return 新任务或正在进行的任务
     */
    IngestJobVO submit();

    /**
     * 查询任务。
     *
     * @param jobId 任务 id
     * @return 视图
     */
    IngestJobVO get(String jobId);

    /**
     * 最近一次任务，供 ops snapshot。
     *
     * @return 最近任务
     */
    Optional<IngestJobVO> getLatest();
}

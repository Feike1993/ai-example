package com.feike.ai.production.rag.ingest.service;

import com.feike.ai.production.rag.ingest.model.IngestJobVO;

import java.util.Optional;

/** ProductionIngestJobService 业务门面。 */
public interface ProductionIngestJobService {

    /**
     * 投递一次幂等重建。同一 corpus 已有未完成任务时复用该 job，不重复入队。
     *
     * @param tenantId 提交者租户，写入任务供查询时校验
     * @return 新任务或正在进行的任务
     */
    IngestJobVO submit(String tenantId);

    /**
     * 投递一次幂等重建。
     *
     * @return 新任务或正在进行的任务
     */
    default IngestJobVO submit() {
        return submit(null);
    }

    /**
     * 查询任务。
     *
     * @param jobId    任务 id
     * @param tenantId 调用方租户；空则跳过租户校验（进程内消费）
     * @return 视图
     */
    IngestJobVO get(String jobId, String tenantId);

    /**
     * 查询任务（不校验租户）。
     *
     * @param jobId 任务 id
     * @return 视图
     */
    default IngestJobVO get(String jobId) {
        return get(jobId, null);
    }

    /**
     * 最近一次任务，供 ops snapshot。
     *
     * @return 最近任务
     */
    Optional<IngestJobVO> getLatest();
}

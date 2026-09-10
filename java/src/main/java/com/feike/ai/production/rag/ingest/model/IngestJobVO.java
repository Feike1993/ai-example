package com.feike.ai.production.rag.ingest.model;

import java.util.List;

/**
 * 入库任务视图。
 *
 * @param jobId        任务 id
 * @param status       queued / running / succeeded / failed
 * @param corpus       语料名
 * @param chunkCount   成功时的块数
 * @param sources      成功时的源文件
 * @param errorMessage 失败原因
 * @param instanceId   执行该任务的实例；排队中可能为空
 */
public record IngestJobVO(
    String jobId,
    String status,
    String corpus,
    Integer chunkCount,
    List<String> sources,
    String errorMessage,
    String instanceId
) {}

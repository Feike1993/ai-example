package com.feike.ai.production.sse.dao;

import com.feike.ai.production.sse.dao.impl.RedisRunEventLogDAOImpl;
import com.feike.ai.production.sse.model.RunSnapshot;
import com.feike.ai.production.sse.model.RunStateEnum;
import com.feike.ai.production.sse.model.StreamEvent;

import java.util.List;
import java.util.Optional;

/**
 * run 事件日志：既是断线续传的数据源，也是「这次 run 到底完成了没有」的唯一权威。
 * <p>
 * 抽成接口有两个原因：一是单测不该为了验证 seq 递增就去起容器，
 * 二是单实例部署可以退化成内存实现而不改调用方代码。
 * 默认实现是 {@link RedisRunEventLogDAOImpl}，因为只有把状态放到进程外，
 * 重连打到另一个实例时才可能续上。
 */
public interface RunEventLogDAO {

    /**
     * 登记一次新的 run。
     *
     * @param runId run 唯一标识
     */
    void begin(String runId);

    /**
     * 追加一条事件。调用方负责保证 seq 单调递增。
     *
     * @param runId run 唯一标识
     * @param event 已定序的事件
     */
    void append(String runId, StreamEvent event);

    /**
     * 标记 run 进入终态。
     *
     * @param runId    run 唯一标识
     * @param terminal 终态；非终态值由实现忽略
     */
    void finish(String runId, RunStateEnum terminal);

    /**
     * @param runId run 唯一标识
     * @return 状态快照；run 不存在或已过保留窗口时为空
     */
    Optional<RunSnapshot> snapshot(String runId);

    /**
     * 回放序号大于 afterSeq 的事件。
     *
     * @param runId    run 唯一标识
     * @param afterSeq 客户端已经收到的最大序号；首次连接传 -1
     * @return 按 seq 升序的事件；无缺口时为空列表
     */
    List<StreamEvent> replay(String runId, long afterSeq);
}

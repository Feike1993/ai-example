package com.feike.ai.core.rag;

/**
 * 可被引用校验的检索命中最小视图。
 * <p>
 * 存在的原因：{@link CitationValidator} 同时服务教学样例与生产链路，两侧的 SourceView
 * 字段并不相同（生产链路不需要 rank / rrfScore 等对比字段）。抽出这个只含
 * id 与 source 的接口，让 core 不必反向依赖任何上层模块的具体记录类型。
 */
public interface CitedSource {

    /**
     * @return 文档唯一 id，即 citation 里必须落在检索命中集合内的那个值
     */
    String id();

    /**
     * @return 来源文件名；同一文件切出多块时会重复，因此不能单独作为唯一标识
     */
    String source();
}

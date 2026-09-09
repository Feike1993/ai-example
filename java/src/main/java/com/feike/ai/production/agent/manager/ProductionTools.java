package com.feike.ai.production.agent.manager;

import com.feike.ai.production.config.ProductionProperties;
import com.feike.ai.production.auth.model.ProductionPrincipal;
import com.feike.ai.production.rag.ingest.service.ProductionIngestService;
import com.feike.ai.production.rag.retrieve.service.ProductionRetrievalService;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

/**
 * 生产 Agent 工具。每个请求 new 一份，把 {@link ProductionPrincipal} 编进闭包，避免单例串租户。
 * <p>
 * 全部无真实外部副作用：天气是写死的，重建索引只动本租户可读的捆绑语料。
 */
public class ProductionTools {

    private final ProductionRetrievalService retrieval;
    private final ProductionIngestService ingest;
    private final ProductionPrincipal principal;
    private final ProductionProperties properties;

    /**
     * @param retrieval  检索
     * @param ingest     入库
     * @param principal  当前用户
     * @param properties topK / 公开租户
     */
    public ProductionTools(
        ProductionRetrievalService retrieval,
        ProductionIngestService ingest,
        ProductionPrincipal principal,
        ProductionProperties properties
    ) {
        this.retrieval = retrieval;
        this.ingest = ingest;
        this.principal = principal;
        this.properties = properties;
    }

    /**
     * 在当前租户可见的知识库里检索。
     *
     * @param query 查询
     * @return 摘录文本
     */
    @Tool(name = "search_kb", description = "在企业知识库中检索与问题相关的段落，返回来源摘录。回答事实性问题时必须先检索。")
    public String searchKb(@ToolParam(description = "检索问句") String query) {
        var result = retrieval.retrieve(query, properties.topK(), principal.tenantId());
        if (result.empty()) {
            return "未检索到相关内容";
        }
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < result.sources().size(); i++) {
            var source = result.sources().get(i);
            builder.append("[C").append(i + 1).append("] ")
                .append(source.source())
                .append(": ")
                .append(source.excerpt())
                .append('\n');
        }
        return builder.toString();
    }

    /**
     * @param a 加数
     * @param b 加数
     * @return 和
     */
    @Tool(name = "add", description = "计算两个数字的和")
    public double add(
        @ToolParam(description = "第一个加数") double a,
        @ToolParam(description = "第二个加数") double b
    ) {
        return a + b;
    }

    /**
     * @param city 城市
     * @return 模拟天气
     */
    @Tool(name = "get_weather", description = "查询指定城市的天气。演示用，返回模拟数据。")
    public String getWeather(@ToolParam(description = "城市名，例如 北京") String city) {
        if (city == null || city.isBlank()) {
            return "请提供城市名";
        }
        return switch (city.trim()) {
            case "北京" -> "北京：晴，25°C，北风 2 级";
            case "上海" -> "上海：多云，28°C，东南风 3 级";
            default -> city.trim() + "：阴，22°C";
        };
    }

    /**
     * 仅 ADMIN。重建捆绑语料索引。
     *
     * @return 入库摘要
     */
    @Tool(name = "rebuild_index", description = "重建生产知识库索引。仅管理员可调用。")
    public String rebuildIndex() {
        if (!principal.admin()) {
            return "denied: 需要 ADMIN 角色";
        }
        var result = ingest.ingest(properties.agent().publicTenantId());
        return "已重建 corpus=" + result.corpus() + " chunks=" + result.chunkCount();
    }
}

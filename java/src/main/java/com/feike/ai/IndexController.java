package com.feike.ai;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 根路径索引，列出样例 HTTP 入口，避免打开浏览器后不知道该调哪个接口。
 */
@RestController
public class IndexController {

    /**
 * 返回样例清单，按基础闭环与进阶（第四至十期）分组。
 *
 * @return 项目名、baseline / advanced 样例路径
 */
    @GetMapping("/")
    public Map<String, Object> index() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", "ai-example");
        body.put("providers", "GET /ai-example/providers");

        Map<String, Object> baseline = new LinkedHashMap<>();
        baseline.put("version", "0.2.0");
        baseline.put("samples", Map.of(
            "chat", "POST /ai-example/chat  GET /ai-example/chat/stream?prompt=",
            "structured", "POST /ai-example/structured/ticket",
            "tools", "POST /ai-example/tools",
            "agentReact", "POST /ai-example/agent/react  GET /ai-example/agent/react/stream?prompt=",
            "agentFramework", "POST /ai-example/agent/framework",
            "mcp", "GET /ai-example/mcp/tools  POST /ai-example/mcp/chat（默认 remote 需 mcp-server:8081 + Bearer）",
            "rag", "POST /ai-example/rag/ingest  POST /ai-example/rag/query  GET /ai-example/rag/query/stream",
            "context", "POST /ai-example/context/chat  GET|DELETE /ai-example/context/session/{id}",
            "multiagent", "POST /ai-example/multiagent/run"
        ));
        body.put("baseline", baseline);

        Map<String, Object> advanced = new LinkedHashMap<>();
        advanced.put("phase", 10);
        Map<String, String> advancedSamples = new LinkedHashMap<>();
        advancedSamples.put(
            "hybridRag",
            "POST /ai-example/rag/query (retrievalMode=hybrid)  POST /ai-example/rag/query/compare"
        );
        advancedSamples.put("eval", "POST /ai-example/eval/run");
        advancedSamples.put(
            "memory",
            "POST /ai-example/memory/remember  POST /ai-example/memory/recall  POST /ai-example/memory/chat  DELETE /ai-example/memory"
        );
        advancedSamples.put(
            "mcpRemote",
            "mcp-server:8081 + GET/POST /ai-example/mcp/*（app.ai.mcp.mode=remote|inprocess）"
        );
        advancedSamples.put(
            "hyde",
            "POST /ai-example/rag/query (queryExpansion=hyde)  POST /ai-example/rag/query/compare-expansion"
        );
        advancedSamples.put(
            "semanticChunk",
            "POST /ai-example/rag/ingest (strategy)  POST /ai-example/rag/query/compare-chunking"
        );
        advancedSamples.put(
            "parentChild",
            "POST /ai-example/rag/query (chunkingStrategy=parent_child)  compare-chunking.parentChild"
        );
        advancedSamples.put("memoryExtract", "POST /ai-example/memory/extract");
        advancedSamples.put(
            "memoryCompare",
            "POST /ai-example/memory/recall/compare  POST /ai-example/memory/chat/compare"
        );
        advancedSamples.put(
            "mcpBearer",
            "mcp-server Bearer + 主应用 MCP_BEARER_TOKEN（见 docs/samples/19-mcp-bearer.md）"
        );
        advancedSamples.put(
            "agentToolSse",
            "GET /ai-example/agent/react/stream（tool_call / tool_result / usage / done）"
        );
        advancedSamples.put(
            "agentUsage",
            "POST /ai-example/agent/react（Trace.usage + usageCalls）"
        );
        advanced.put("samples", advancedSamples);
        body.put("advanced", advanced);

        return body;
    }
}

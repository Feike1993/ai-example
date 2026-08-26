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
     * 返回样例清单。
     *
     * @return 项目名、期数和各样例路径
     */
    @GetMapping("/")
    public Map<String, Object> index() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", "ai-example");
        body.put("phase", 3);
        body.put("providers", "GET /ai-example/providers");
        body.put("samples", Map.of(
            "chat", "POST /ai-example/chat  GET /ai-example/chat/stream?prompt=",
            "structured", "POST /ai-example/structured/ticket",
            "tools", "POST /ai-example/tools",
            "agentReact", "POST /ai-example/agent/react",
            "agentFramework", "POST /ai-example/agent/framework",
            "mcp", "GET /ai-example/mcp/tools  POST /ai-example/mcp/chat",
            "rag", "POST /ai-example/rag/ingest  POST /ai-example/rag/query  GET /ai-example/rag/query/stream",
            "context", "POST /ai-example/context/chat  GET|DELETE /ai-example/context/session/{id}",
            "multiagent", "POST /ai-example/multiagent/run"
        ));
        return body;
    }
}

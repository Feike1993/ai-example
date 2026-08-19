package com.feike.ai;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 根路径索引，列出第一期样例的 HTTP 入口，避免打开浏览器后不知道该调哪个接口。
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
        body.put("phase", 1);
        body.put("samples", Map.of(
            "chat", "POST /ai-example/chat  GET /ai-example/chat/stream?prompt=",
            "structured", "POST /ai-example/structured/ticket",
            "tools", "POST /ai-example/tools",
            "agentReact", "POST /ai-example/agent/react",
            "agentFramework", "POST /ai-example/agent/framework"
        ));
        return body;
    }
}

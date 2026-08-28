package com.feike.ai.samples.eval;

import tools.jackson.databind.json.JsonMapper;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 从 classpath {@code eval/golden/*.json} 加载评测用例。
 */
@Component
public class EvalGoldenLoader {

    private final JsonMapper jsonMapper;

    public EvalGoldenLoader(JsonMapper jsonMapper) {
        this.jsonMapper = jsonMapper;
    }

    /**
     * @return 按 id 排序的 golden 用例
     */
    public List<EvalCase> loadAll() {
        try {
            PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();
            Resource[] resources = resolver.getResources("classpath:eval/golden/*.json");
            List<EvalCase> cases = new ArrayList<>();
            for (Resource resource : resources) {
                EvalCase single = jsonMapper.readValue(resource.getInputStream(), EvalCase.class);
                if (single.id() != null && !single.id().isBlank()) {
                    cases.add(single);
                }
            }
            cases.sort(Comparator.comparing(EvalCase::id));
            return cases;
        } catch (IOException ex) {
            throw new IllegalStateException("加载 eval golden 失败: " + ex.getMessage(), ex);
        }
    }

    /**
     * 单条 golden 用例。
     *
     * @param id              用例 id
     * @param target          tools | agentReact | rag | multiagent
     * @param prompt          输入
     * @param mustContain     答案须包含的子串（全部）
     * @param mustNotContain  答案不得包含的子串
     * @param maxSteps        Agent 类最大步数；为空则用配置默认
     * @param expectSources   RAG 类是否要求 sources 非空
     * @param expectToolName  Agent 类是否要求出现某工具名
     */
    public record EvalCase(
        String id,
        String target,
        String prompt,
        List<String> mustContain,
        List<String> mustNotContain,
        Integer maxSteps,
        Boolean expectSources,
        String expectToolName
    ) {
        public EvalCase {
            if (mustContain == null) {
                mustContain = List.of();
            }
            if (mustNotContain == null) {
                mustNotContain = List.of();
            }
        }
    }
}

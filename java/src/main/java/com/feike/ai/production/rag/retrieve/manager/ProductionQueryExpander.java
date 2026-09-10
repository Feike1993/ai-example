package com.feike.ai.production.rag.retrieve.manager;

import com.feike.ai.core.rag.RagQueryExpander;
import com.feike.ai.production.secret.manager.ProductionModelFactory;

/**
 * 生产侧查询扩展：Chat 走信封密钥，不读教学环境变量里的明文 Key。
 */
public class ProductionQueryExpander {

    private final ProductionModelFactory models;

    /**
     * @param models 生产 ChatClient 工厂
     */
    public ProductionQueryExpander(ProductionModelFactory models) {
        this.models = models;
    }

    /**
     * 改写成检索短句。
     *
     * @param question 用户问题
     * @param provider Provider id
     * @return 检索 query
     */
    public String rewrite(String question, String provider) {
        return RagQueryExpander.rewrite(question, (system, user) -> complete(provider, system, user));
    }

    /**
     * 生成 HyDE 假想文档。
     *
     * @param question 用户问题
     * @param provider Provider id
     * @return 假想段落或原问题
     */
    public String hypotheticalDocument(String question, String provider) {
        return RagQueryExpander.hypotheticalDocument(question, (system, user) -> complete(provider, system, user));
    }

    private String complete(String provider, String system, String user) {
        String content = models.plainClient(provider)
            .prompt()
            .system(system)
            .user(user)
            .call()
            .content();
        return content == null ? "" : content;
    }
}

package com.feike.ai.samples.rag;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Reciprocal Rank Fusion（RRF）：把多路检索排名融合为统一排序。
 * <p>
 * 公式：{@code score(d) = Σ 1 / (k + rank_i(d))}，{@code k} 通常取 60。
 */
public final class RrfFusion {

    private RrfFusion() {}

    /**
     * @param id         文档 id
     * @param rrfScore   融合分
     * @param vectorRank 向量路排名（1-based）；未出现则为 {@code null}
     * @param keywordRank 关键词路排名（1-based）；未出现则为 {@code null}
     */
    public record RankedId(String id, double rrfScore, Integer vectorRank, Integer keywordRank) {}

    /**
     * 对向量路与关键词路的 id 排名做 RRF 融合。
     *
     * @param vectorIds   向量路 id 列表（按 rank 1..n 排序）
     * @param keywordIds  关键词路 id 列表（按 rank 1..n 排序）
     * @param k           RRF 常数，须 &gt; 0
     * @param limit       返回条数上限
     * @return 按 rrfScore 降序的融合结果
     */
    public static List<RankedId> fuse(List<String> vectorIds, List<String> keywordIds, int k, int limit) {
        if (k < 1) {
            k = 60;
        }
        if (limit < 1) {
            limit = 1;
        }
        Map<String, Double> scores = new LinkedHashMap<>();
        Map<String, Integer> vectorRanks = rankMap(vectorIds);
        Map<String, Integer> keywordRanks = rankMap(keywordIds);

        accumulate(scores, vectorIds, k);
        accumulate(scores, keywordIds, k);

        List<RankedId> merged = new ArrayList<>();
        for (Map.Entry<String, Double> entry : scores.entrySet()) {
            String id = entry.getKey();
            merged.add(new RankedId(
                id,
                entry.getValue(),
                vectorRanks.get(id),
                keywordRanks.get(id)
            ));
        }
        merged.sort(Comparator.comparingDouble(RankedId::rrfScore).reversed());
        if (merged.size() > limit) {
            return merged.subList(0, limit);
        }
        return merged;
    }

    private static Map<String, Integer> rankMap(List<String> ids) {
        Map<String, Integer> ranks = new LinkedHashMap<>();
        if (ids == null) {
            return ranks;
        }
        for (int i = 0; i < ids.size(); i++) {
            String id = ids.get(i);
            if (id != null && !id.isBlank()) {
                ranks.putIfAbsent(id, i + 1);
            }
        }
        return ranks;
    }

    /**
     * 向 scores 中累加 id 的 rrf 分数。
     * @param scores 累加的目标 scores
     * @param ids     待累加的 id 列表
     * @param k       RRF 常数
     */
    private static void accumulate(Map<String, Double> scores, List<String> ids, int k) {
        if (ids == null) {
            return;
        }
        for (int i = 0; i < ids.size(); i++) {
            String id = ids.get(i);
            if (id == null || id.isBlank()) {
                continue;
            }
            double increment = 1.0 / (k + i + 1);
            scores.merge(id, increment, Double::sum);
        }
    }
}

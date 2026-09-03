package com.feike.ai.samples.rag;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * RAG citation 后校验：每条 sourceId 必须落在本次检索 hits。
 * <p>
 * 为何校验前先 {@link #resolveCitations}：结构化输出里不同模型对 sourceId 理解不一致，
 * 常填文件名 / 序号 / 短别名；归一化把可逆误填映回真实 id，再严格校验，
 * 避免「其实引用了正确 chunk、只是 id 写错形态」被整单拒答。同文件多 chunk 的文件名故意不猜。
 */
public final class CitationValidator {

    private static final Pattern ORDINAL = Pattern.compile("^\\[?(\\d+)]?$");

    private CitationValidator() {}

    /**
     * 单条引用。
     *
     * @param sourceId 对应 {@link RagSampleService.SourceView#id()}（或归一化前的模型原文）
     * @param quote    摘录或依据说明
     */
    public record Citation(String sourceId, String quote) {}

    /**
     * 结构化 grounded 答（模型输出）。
     *
     * @param answer    面向用户的答案
     * @param citations 引用列表
     */
    public record GroundedAnswer(String answer, List<Citation> citations) {}

    /**
     * 校验结果。
     *
     * @param valid   是否通过
     * @param detail  失败原因；通过时为简短说明
     */
    public record Result(boolean valid, String detail) {}

    /**
     * 按 sources 顺序生成短别名：C1 → sources[0].id，…。
     * <p>
     * 供 prompt allowlist 与 {@link #resolveCitations} 共用，避免 UUID 难抄。
     *
     * @param sources 本次检索 sources
     * @return 有序别名 → 真实 id
     */
    public static Map<String, String> aliasMap(List<RagSampleService.SourceView> sources) {
        Map<String, String> aliases = new LinkedHashMap<>();
        if (sources == null) {
            return aliases;
        }
        int index = 1;
        for (RagSampleService.SourceView source : sources) {
            if (source == null || source.id() == null || source.id().isBlank()) {
                continue;
            }
            aliases.put("C" + index, source.id());
            index++;
        }
        return aliases;
    }

    /**
     * 将模型给出的 sourceId 归一化为真实文档 id。
     * <p>
     * 优先级：真实 id → 别名 C# → 唯一文件名 → 序号 1/[1]；无法唯一映射则保留原文，
     * 交给后续 {@link #validate} 失败（同文件多 chunk 不猜）。
     *
     * @param citations 模型引用
     * @param aliases   {@link #aliasMap} 结果；可为 {@code null}
     * @param sources   本次检索 sources（用于文件名 / 序号）
     * @return 与输入等长的引用列表（空输入返回空列表）
     */
    public static List<Citation> resolveCitations(
        List<Citation> citations,
        Map<String, String> aliases,
        List<RagSampleService.SourceView> sources
    ) {
        if (citations == null || citations.isEmpty()) {
            return List.of();
        }
        Set<String> realIds = idsOf(sources);
        Map<String, String> aliasLookup = normalizeAliasLookup(aliases);
        Map<String, String> uniqueFileToId = uniqueFilenameMap(sources);
        List<String> orderedIds = orderedIds(sources);

        List<Citation> resolved = new ArrayList<>(citations.size());
        for (Citation citation : citations) {
            if (citation == null) {
                resolved.add(null);
                continue;
            }
            String raw = citation.sourceId() == null ? "" : citation.sourceId().trim();
            String mapped = resolveOne(raw, realIds, aliasLookup, uniqueFileToId, orderedIds);
            resolved.add(new Citation(mapped, citation.quote()));
        }
        return List.copyOf(resolved);
    }

    /**
     * 校验 citations 非空且 sourceId 均在 allowedIds 内。
     *
     * @param citations  模型给出的引用（建议先 resolve）
     * @param allowedIds 本次检索文档 id
     * @return 校验结果
     */
    public static Result validate(List<Citation> citations, Set<String> allowedIds) {
        if (citations == null || citations.isEmpty()) {
            return new Result(false, "citations 为空");
        }
        Set<String> allowed = allowedIds == null ? Set.of() : allowedIds;
        List<String> missing = new ArrayList<>();
        for (Citation citation : citations) {
            if (citation == null || citation.sourceId() == null || citation.sourceId().isBlank()) {
                return new Result(false, "存在空 sourceId");
            }
            if (!allowed.contains(citation.sourceId())) {
                missing.add(citation.sourceId());
            }
        }
        if (!missing.isEmpty()) {
            return new Result(false, "sourceId 不在检索结果: " + String.join(", ", missing));
        }
        return new Result(true, "citations 均落在检索 hits（" + citations.size() + " 条）");
    }

    /**
     * 从 SourceView 列表收集 id。
     *
     * @param sources 检索 sources
     * @return 有序去重 id 集
     */
    public static Set<String> idsOf(List<RagSampleService.SourceView> sources) {
        Set<String> ids = new LinkedHashSet<>();
        if (sources == null) {
            return ids;
        }
        for (RagSampleService.SourceView source : sources) {
            if (source != null && source.id() != null && !source.id().isBlank()) {
                ids.add(source.id());
            }
        }
        return ids;
    }

    private static String resolveOne(
        String raw,
        Set<String> realIds,
        Map<String, String> aliasLookup,
        Map<String, String> uniqueFileToId,
        List<String> orderedIds
    ) {
        if (raw.isEmpty()) {
            return raw;
        }
        if (realIds.contains(raw)) {
            return raw;
        }
        // 先别名：prompt 主路径就是 C1..Cn，大小写不敏感
        String fromAlias = aliasLookup.get(raw.toUpperCase(Locale.ROOT));
        if (fromAlias != null) {
            return fromAlias;
        }
        // 再唯一文件名：兼容旧习惯抄 source=03-rag.md；多 chunk 同名不在此 map
        if (uniqueFileToId.containsKey(raw)) {
            return uniqueFileToId.get(raw);
        }
        // 再序号：兼容旧 buildContext 的 [1] / 1
        Matcher ordinal = ORDINAL.matcher(raw);
        if (ordinal.matches()) {
            int oneBased = Integer.parseInt(ordinal.group(1));
            if (oneBased >= 1 && oneBased <= orderedIds.size()) {
                return orderedIds.get(oneBased - 1);
            }
        }
        // 无法可逆映射则原样返回，由 validate 报「不在检索结果」
        return raw;
    }

    private static Map<String, String> normalizeAliasLookup(Map<String, String> aliases) {
        Map<String, String> lookup = new HashMap<>();
        if (aliases == null) {
            return lookup;
        }
        for (Map.Entry<String, String> entry : aliases.entrySet()) {
            if (entry.getKey() == null || entry.getValue() == null) {
                continue;
            }
            lookup.put(entry.getKey().trim().toUpperCase(Locale.ROOT), entry.getValue());
        }
        return lookup;
    }

    /**
     * 仅当某文件名只对应一条 hit 时才可映射。
     * <p>
     * 同文件多 chunk 时文件名无法唯一指向某一 id；若强行猜会破坏「可追溯到具体 hit」的教学约束，
     * 故标为歧义并排除，留给 validate 失败。
     */
    private static Map<String, String> uniqueFilenameMap(List<RagSampleService.SourceView> sources) {
        Map<String, String> first = new HashMap<>();
        Set<String> ambiguous = new LinkedHashSet<>();
        if (sources == null) {
            return Map.of();
        }
        for (RagSampleService.SourceView source : sources) {
            if (source == null || source.id() == null || source.id().isBlank()) {
                continue;
            }
            String file = source.source();
            if (file == null || file.isBlank()) {
                continue;
            }
            if (ambiguous.contains(file)) {
                continue;
            }
            if (first.containsKey(file)) {
                first.remove(file);
                ambiguous.add(file);
            } else {
                first.put(file, source.id());
            }
        }
        return first;
    }

    private static List<String> orderedIds(List<RagSampleService.SourceView> sources) {
        List<String> ids = new ArrayList<>();
        if (sources == null) {
            return ids;
        }
        for (RagSampleService.SourceView source : sources) {
            if (source != null && source.id() != null && !source.id().isBlank()) {
                ids.add(source.id());
            }
        }
        return ids;
    }
}

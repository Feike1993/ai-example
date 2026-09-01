package com.feike.ai.samples.rag;

import org.springframework.ai.document.Document;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 结构感知语义分块：按 Markdown 标题 / 空行切段，再软合并到目标长度。
 * <p>
 * 管线：{@code toSegments}（结构切）→ {@code softMerge}（同标题打包）→ 超长走 {@code hardSplit}。
 * 确定性、无 LLM；与 {@code TokenTextSplitter} 对照用，不追求生产级解析器完备性。
 */
public final class SemanticMarkdownSplitter {

    private final int targetSize;

    /**
     * @param targetSize 合并后单块目标长度（字符近似；与 app.ai.rag.chunk-size 对齐）
     */
    public SemanticMarkdownSplitter(int targetSize) {
        this.targetSize = Math.max(50, targetSize);
    }

    /**
     * 对每个源文档做结构切分；保留 source，并写入 chunking=semantic、可选 heading。
     *
     * @param sourceDocs 完整 Markdown 文档
     * @return 语义块列表
     */
    public List<Document> apply(List<Document> sourceDocs) {
        List<Document> out = new ArrayList<>();
        if (sourceDocs == null) {
            return out;
        }
        for (Document doc : sourceDocs) {
            out.addAll(splitOne(doc));
        }
        return out;
    }

    /**
     * 对单个文档：结构切 + 软合并后，写入 metadata（chunking / heading / source）。
     */
    private List<Document> splitOne(Document doc) {
        String text = doc.getText() == null ? "" : doc.getText();
        String source = String.valueOf(doc.getMetadata().getOrDefault("source", "unknown"));
        List<Segment> merged = softMerge(toSegments(text));
        List<Document> chunks = new ArrayList<>();
        for (Segment seg : merged) {
            Map<String, Object> meta = new LinkedHashMap<>(doc.getMetadata());
            meta.put("source", source);
            meta.put("chunking", "semantic");
            if (seg.heading() != null && !seg.heading().isBlank()) {
                meta.put("heading", seg.heading());
            }
            chunks.add(Document.builder()
                .text(seg.body())
                .metadata(meta)
                .build());
        }
        return chunks;
    }

    /**
     * 先按 H1–H3 标题切开，再按空行切段落；保留各段所属标题。
     * <p>
     * 同节仅首段正文注入 {@code ## heading} 前缀，便于 Embedding 带着章节语境。
     */
    static List<Segment> toSegments(String text) {
        List<Segment> segments = new ArrayList<>();
        if (text == null || text.isBlank()) {
            return segments;
        }
        // (?m)：^ 匹配行首；(?=…) 零宽断言：切在标题前，标题留在下一块开头
        String[] blocks = text.split("(?m)(?=^#{1,3}\\s+)");
        for (String block : blocks) {
            // 文首无标题时 split 可能产生空前缀，丢弃
            if (block == null || block.isBlank()) {
                continue;
            }
            String trimmed = block.trim();
            String heading = null;
            String body = trimmed;
            // (?s)：. 可跨行，判定本块是否以 H1–H3 起头
            if (trimmed.matches("(?s)^#{1,3}\\s+.+")) {
                int nl = trimmed.indexOf('\n');
                if (nl < 0) {
                    // 整块只有标题行 → 无正文可再切，整段作为一块
                    heading = trimmed.replaceFirst("^#{1,3}\\s+", "").trim();
                    segments.add(new Segment(heading, trimmed));
                    continue;
                }
                // 标题与正文分离：首行抽 heading，余下再按空行切
                heading = trimmed.substring(0, nl).replaceFirst("^#{1,3}\\s+", "").trim();
                body = trimmed.substring(nl + 1).trim();
            }
            if (body.isEmpty()) {
                // 空节仍留可检索锚点，避免标题在索引中消失
                if (heading != null) {
                    segments.add(new Segment(heading, "## " + heading));
                }
                continue;
            }
            // \n{2,}：≥2 个换行才切段；单换行视为段内换行，不切断
            String[] paras = body.split("\n{2,}");
            boolean first = true;
            for (String para : paras) {
                String p = para.trim();
                if (p.isEmpty()) {
                    continue;
                }
                // 仅首段注入 ## 标题前缀；同节后续段共享 metadata.heading、正文不重复标题
                String content = (first && heading != null) ? ("## " + heading + "\n\n" + p) : p;
                first = false;
                segments.add(new Segment(heading, content));
            }
        }
        // 无任何标题/空行结构时整文一块，避免吞掉内容
        if (segments.isEmpty()) {
            segments.add(new Segment(null, text.trim()));
        }
        return segments;
    }

    /**
     * 同标题下软合并到目标长度；换标题先 flush；单段超标走 {@link #hardSplit}。
     */
    List<Segment> softMerge(List<Segment> segments) {
        List<Segment> merged = new ArrayList<>();
        if (segments == null || segments.isEmpty()) {
            return merged;
        }
        StringBuilder buf = new StringBuilder();
        String currentHeading = segments.getFirst().heading();
        for (Segment seg : segments) {
            String piece = seg.body();
            if (piece.length() > targetSize) {
                // 单段已超标：先吐缓冲，再硬切；不与邻段粘，避免超长块继续膨胀
                flush(merged, currentHeading, buf);
                for (String part : hardSplit(piece, targetSize)) {
                    merged.add(new Segment(seg.heading(), part));
                }
                currentHeading = seg.heading();
                continue;
            }
            // null 与非 null 也算换节：跨标题必须断块，防止语义混装
            boolean headingChanged = (currentHeading == null) != (seg.heading() == null)
                || (currentHeading != null && !currentHeading.equals(seg.heading()));
            if (headingChanged && !buf.isEmpty()) {
                flush(merged, currentHeading, buf);
            }
            if (buf.isEmpty()) {
                currentHeading = seg.heading();
                buf.append(piece);
            } else if (buf.length() + 2 + piece.length() <= targetSize) {
                // +2 是段间 "\n\n"；能装则软粘
                buf.append("\n\n").append(piece);
            } else {
                // 装不下：断在段边界，新开缓冲（不在段中间硬切）
                flush(merged, currentHeading, buf);
                currentHeading = seg.heading();
                buf.append(piece);
            }
        }
        // 收尾，避免最后一包丢失
        flush(merged, currentHeading, buf);
        return merged;
    }

    private static void flush(List<Segment> out, String heading, StringBuilder buf) {
        if (buf.isEmpty()) {
            return;
        }
        out.add(new Segment(heading, buf.toString().trim()));
        buf.setLength(0);
    }

    /**
     * 超长段按句号 / 换行硬切；断点过靠左则放弃，硬切到 max，避免过碎。
     */
    static List<String> hardSplit(String text, int max) {
        List<String> parts = new ArrayList<>();
        if (text == null || text.isEmpty()) {
            return parts;
        }
        int start = 0;
        while (start < text.length()) {
            // 先按 max 字符取候选右界
            int end = Math.min(start + max, text.length());
            if (end < text.length()) {
                // 优先在句号或换行断，减少切断半句
                int breakAt = Math.max(text.lastIndexOf('。', end), text.lastIndexOf('\n', end));
                // 断点须落在窗口后 2/3：太靠左则放弃，避免切出过短碎片
                if (breakAt > start + max / 3) {
                    end = breakAt + 1;
                }
            }
            String slice = text.substring(start, end).trim();
            if (!slice.isBlank()) {
                parts.add(slice);
            }
            // 无重叠窗口推进
            start = end;
        }
        return parts;
    }

    /** 带可选标题的中间段。 */
    public record Segment(String heading, String body) {}
}

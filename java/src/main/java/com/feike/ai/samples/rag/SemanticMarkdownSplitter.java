package com.feike.ai.samples.rag;

import org.springframework.ai.document.Document;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 结构感知语义分块：按 Markdown 标题 / 空行切段，再软合并到目标长度。
 * <p>
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

    private List<Document> splitOne(Document doc) {
        String text = doc.getText() == null ? "" : doc.getText();
        String source = String.valueOf(doc.getMetadata().getOrDefault("source", "unknown"));
        List<Segment> merged = softMerge(toSegments(text));
        List<Document> chunks = new ArrayList<>();
        for (Segment seg : merged) {
            Map<String, Object> meta = new LinkedHashMap<>();
            if (doc.getMetadata() != null) {
                meta.putAll(doc.getMetadata());
            }
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
     * 先按标题切开，再按空行切段落；保留各段所属标题。
     */
    static List<Segment> toSegments(String text) {
        List<Segment> segments = new ArrayList<>();
        if (text == null || text.isBlank()) {
            return segments;
        }
        String[] blocks = text.split("(?m)(?=^#{1,3}\\s+)");
        for (String block : blocks) {
            if (block == null || block.isBlank()) {
                continue;
            }
            String trimmed = block.trim();
            String heading = null;
            String body = trimmed;
            if (trimmed.matches("(?s)^#{1,3}\\s+.+")) {
                int nl = trimmed.indexOf('\n');
                if (nl < 0) {
                    heading = trimmed.replaceFirst("^#{1,3}\\s+", "").trim();
                    segments.add(new Segment(heading, trimmed));
                    continue;
                }
                heading = trimmed.substring(0, nl).replaceFirst("^#{1,3}\\s+", "").trim();
                body = trimmed.substring(nl + 1).trim();
            }
            if (body.isEmpty()) {
                if (heading != null) {
                    segments.add(new Segment(heading, "## " + heading));
                }
                continue;
            }
            String[] paras = body.split("\n{2,}");
            boolean first = true;
            for (String para : paras) {
                String p = para.trim();
                if (p.isEmpty()) {
                    continue;
                }
                String content = (first && heading != null) ? ("## " + heading + "\n\n" + p) : p;
                first = false;
                segments.add(new Segment(heading, content));
            }
        }
        if (segments.isEmpty()) {
            segments.add(new Segment(null, text.trim()));
        }
        return segments;
    }

    List<Segment> softMerge(List<Segment> segments) {
        List<Segment> merged = new ArrayList<>();
        if (segments == null || segments.isEmpty()) {
            return merged;
        }
        StringBuilder buf = new StringBuilder();
        String currentHeading = segments.get(0).heading();
        for (Segment seg : segments) {
            String piece = seg.body();
            if (piece.length() > targetSize) {
                flush(merged, currentHeading, buf);
                for (String part : hardSplit(piece, targetSize)) {
                    merged.add(new Segment(seg.heading(), part));
                }
                currentHeading = seg.heading();
                continue;
            }
            boolean headingChanged = (currentHeading == null) != (seg.heading() == null)
                || (currentHeading != null && !currentHeading.equals(seg.heading()));
            if (headingChanged && buf.length() > 0) {
                flush(merged, currentHeading, buf);
            }
            if (buf.length() == 0) {
                currentHeading = seg.heading();
                buf.append(piece);
            } else if (buf.length() + 2 + piece.length() <= targetSize) {
                buf.append("\n\n").append(piece);
            } else {
                flush(merged, currentHeading, buf);
                currentHeading = seg.heading();
                buf.append(piece);
            }
        }
        flush(merged, currentHeading, buf);
        return merged;
    }

    private static void flush(List<Segment> out, String heading, StringBuilder buf) {
        if (buf.length() == 0) {
            return;
        }
        out.add(new Segment(heading, buf.toString().trim()));
        buf.setLength(0);
    }

    /** 超长段按句号 / 换行硬切。 */
    static List<String> hardSplit(String text, int max) {
        List<String> parts = new ArrayList<>();
        if (text == null || text.isEmpty()) {
            return parts;
        }
        int start = 0;
        while (start < text.length()) {
            int end = Math.min(start + max, text.length());
            if (end < text.length()) {
                int breakAt = Math.max(text.lastIndexOf('。', end), text.lastIndexOf('\n', end));
                if (breakAt > start + max / 3) {
                    end = breakAt + 1;
                }
            }
            String slice = text.substring(start, end).trim();
            if (!slice.isBlank()) {
                parts.add(slice);
            }
            start = end;
        }
        return parts;
    }

    /** 带可选标题的中间段。 */
    public record Segment(String heading, String body) {}
}

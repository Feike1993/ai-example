"""父子文档对照：父块全文 + 子块切片（内存，无 Embedding）。

与 Java RagSampleService.buildParentChildChunks / expandParents 同构：
语义父块 → hard_split 子块 → 按 parent_id 去重展开父全文。
"""

from __future__ import annotations

from dataclasses import dataclass

from ai_example.samples.semantic_chunk import semantic_chunk


def hard_split(text: str, max_chars: int) -> list[str]:
    """按长度硬切（对齐 Java SemanticMarkdownSplitter.hardSplit）。

    优先在句号 / 换行断；断点过靠左（<= max/3）则硬切到 max，避免过碎。
    """
    max_chars = max(40, max_chars)
    parts: list[str] = []
    start = 0
    while start < len(text):
        # 先按 max 取候选右界
        end = min(start + max_chars, len(text))
        if end < len(text):
            break_at = max(text.rfind("。", start, end), text.rfind("\n", start, end))
            # 断点须落在窗口后 2/3，否则放弃，硬切到 max
            if break_at > start + max_chars // 3:
                end = break_at + 1
        slice_ = text[start:end].strip()
        if slice_:
            parts.append(slice_)
        start = end  # 无重叠推进
    return parts


@dataclass
class ChildChunk:
    """内存子块（对照用，不入库）。"""

    text: str
    source: str
    parent_id: str
    parent_text: str
    chunk_index: int
    chunk_role: str = "child"


def build_parent_child(text: str, source: str = "demo.md", child_size: int = 80) -> list[ChildChunk]:
    """语义父块 → 子块列表。

    只产出子块：父全文写入 parent_text，供 expand_parents 使用（对齐 Java 只 Embedding 子块）。
    """
    # 父块：复用 semantic_chunk，保留标题/段落边界
    parents = semantic_chunk(text, source=source)
    children: list[ChildChunk] = []
    for i, parent in enumerate(parents, start=1):
        # 序号+来源 → 稳定 parent_id，展开时按父去重
        parent_id = f"p-{i}-{source}"
        parts = hard_split(parent.text, child_size)
        for j, part in enumerate(parts):
            children.append(
                ChildChunk(
                    text=part,
                    source=source,
                    parent_id=parent_id,
                    # 每子块复制整段父文（演示取舍，与 Java metadata.parentText 一致）
                    parent_text=parent.text,
                    chunk_index=j,
                )
            )
    return children


def expand_parents(children: list[ChildChunk]) -> list[str]:
    """按 parent_id 去重，返回父全文列表（保序：首次出现优先）。"""
    seen: dict[str, str] = {}
    for c in children:
        # setdefault：同父只留第一份，对齐 Java LinkedHashMap
        seen.setdefault(c.parent_id, c.parent_text)
    return list(seen.values())


def main() -> None:
    sample = """## MCP
MCP 是工具协议。Host 通过 Client 连接 Server。

## RAG
RAG = 检索 + 生成。先分块再 Embedding。
"""
    kids = build_parent_child(sample, child_size=40)
    parents = expand_parents(kids[:3] if len(kids) >= 3 else kids)
    print(f"children={len(kids)} expanded_parents={len(parents)}")
    for p in parents:
        print("-", p[:80].replace("\n", " "))


if __name__ == "__main__":
    main()

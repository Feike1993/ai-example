"""父子文档对照：父块全文 + 子块切片（内存，无 Embedding）。"""

from __future__ import annotations

from dataclasses import dataclass

from ai_example.samples.semantic_chunk import semantic_chunk


def hard_split(text: str, max_chars: int) -> list[str]:
    """按长度硬切（对齐 Java SemanticMarkdownSplitter.hardSplit 简化版）。"""
    max_chars = max(40, max_chars)
    parts: list[str] = []
    start = 0
    while start < len(text):
        end = min(start + max_chars, len(text))
        if end < len(text):
            break_at = max(text.rfind("。", start, end), text.rfind("\n", start, end))
            if break_at > start + max_chars // 3:
                end = break_at + 1
        slice_ = text[start:end].strip()
        if slice_:
            parts.append(slice_)
        start = end
    return parts


@dataclass
class ChildChunk:
    text: str
    source: str
    parent_id: str
    parent_text: str
    chunk_index: int
    chunk_role: str = "child"


def build_parent_child(text: str, source: str = "demo.md", child_size: int = 80) -> list[ChildChunk]:
    """语义父块 → 子块列表。"""
    parents = semantic_chunk(text, source=source)
    children: list[ChildChunk] = []
    for i, parent in enumerate(parents, start=1):
        parent_id = f"p-{i}-{source}"
        parts = hard_split(parent.text, child_size)
        for j, part in enumerate(parts):
            children.append(
                ChildChunk(
                    text=part,
                    source=source,
                    parent_id=parent_id,
                    parent_text=parent.text,
                    chunk_index=j,
                )
            )
    return children


def expand_parents(children: list[ChildChunk]) -> list[str]:
    """按 parent_id 去重，返回父全文列表。"""
    seen: dict[str, str] = {}
    for c in children:
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

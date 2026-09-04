"""记忆辅助改写（假 recall / rewrite / RAG hits，无真 LLM）。"""

from __future__ import annotations

from dataclasses import dataclass, field


EMPTY_REFUSAL = "知识库未检索到相关内容，无法回答。"


@dataclass
class SourceHit:
    """检索或记忆命中（对照 Java SourceView）。"""

    id: str
    source: str
    excerpt: str
    metadata: dict = field(default_factory=dict)


@dataclass
class MemoryRewriteResult:
    """memory_rewrite 单路结果。"""

    query_expansion: str
    rewritten_query: str | None
    memory_hints: list[SourceHit]
    sources: list[SourceHit]
    retrieval_empty: bool
    answer: str


@dataclass
class ExpansionView:
    """三路对照中的一路（默认只比 sources）。"""

    query_expansion: str
    sources: list[SourceHit]
    retrieval_empty: bool
    rewritten_query: str | None = None
    memory_hints: list[SourceHit] = field(default_factory=list)


@dataclass
class MemoryRewriteCompare:
    """none / rewrite / memory_rewrite 三路。"""

    none: ExpansionView
    rewrite: ExpansionView
    memory_rewrite: ExpansionView


# 演示用假语料：记忆 id 与 KB id 刻意不同前缀
_FAKE_MEMORY: list[SourceHit] = [
    SourceHit("mem-1", "memory", "用户在学 ai-example 第一期 Chat 与 Agent", {"corpus": "long-term-memory"}),
    SourceHit("mem-2", "memory", "用户偏好简体中文讲解", {"corpus": "long-term-memory"}),
]

_FAKE_KB: list[SourceHit] = [
    SourceHit("rag-1", "demo.md", "第一期覆盖 Chat、结构化输出、Tool Calling 与 ReAct Agent。", {"corpus": "ai-example-demo"}),
    SourceHit("rag-2", "demo.md", "第二期引入 MCP 与 RAG（pgvector）。", {"corpus": "ai-example-demo"}),
]


def fake_recall_hints(question: str, *, top_k: int = 4) -> list[SourceHit]:
    """假 recall：问题含「一期/学」等关键词时返回记忆 hints。"""
    q = question or ""
    if any(token in q for token in ("一期", "学", "Chat", "Agent")):
        return _FAKE_MEMORY[: max(0, top_k)]
    return []


def fake_rewrite(question: str, hints: list[SourceHit]) -> str:
    """
    假改写：教学演示把 hints 摘录拼进检索句，便于对照。

    生产 Java 侧强调「不要抄记忆原文」；此处为假路径，刻意可见先验。
    """
    base = (question or "").strip() or "检索"
    if not hints:
        return f"检索短句：{base}"
    hint_bits = "；".join(h.excerpt for h in hints)
    return f"检索短句：{base}｜记忆先验：{hint_bits}"


def fake_rag_hits(rewritten_query: str, *, top_k: int = 4) -> list[SourceHit]:
    """假 RAG：改写句含一期/Chat/Agent 时命中 KB；永不返回 mem-* id。"""
    text = rewritten_query or ""
    if any(token in text for token in ("一期", "Chat", "Agent", "第一期")):
        return _FAKE_KB[: max(0, top_k)]
    return []


def run_memory_rewrite(
    question: str,
    *,
    memory_top_k: int = 4,
    rag_top_k: int = 4,
    answer: str | None = None,
) -> MemoryRewriteResult:
    """假链路：recall → rewrite（含 hints）→ RAG；sources 不含记忆 id。"""
    hints = fake_recall_hints(question, top_k=memory_top_k)
    rewritten = fake_rewrite(question, hints)
    hits = fake_rag_hits(rewritten, top_k=rag_top_k)
    hint_ids = {h.id for h in hints}
    assert all(h.id not in hint_ids for h in hits), "RAG sources 不得含记忆 hint id"
    empty = len(hits) == 0
    return MemoryRewriteResult(
        query_expansion="memory_rewrite",
        rewritten_query=rewritten,
        memory_hints=hints,
        sources=hits,
        retrieval_empty=empty,
        answer=EMPTY_REFUSAL if empty else (answer or "根据知识库：第一期含 Chat 与 Agent。"),
    )


def compare_memory_rewrite(question: str, *, top_k: int = 4) -> MemoryRewriteCompare:
    """三路只比 sources（教学假数据）。"""
    none_hits = fake_rag_hits(question, top_k=top_k)
    rewrite_q = fake_rewrite(question, [])
    rewrite_hits = fake_rag_hits(rewrite_q, top_k=top_k)
    mr = run_memory_rewrite(question, rag_top_k=top_k)
    return MemoryRewriteCompare(
        none=ExpansionView(
            query_expansion="none",
            sources=none_hits,
            retrieval_empty=len(none_hits) == 0,
        ),
        rewrite=ExpansionView(
            query_expansion="rewrite",
            sources=rewrite_hits,
            retrieval_empty=len(rewrite_hits) == 0,
            rewritten_query=rewrite_q,
        ),
        memory_rewrite=ExpansionView(
            query_expansion="memory_rewrite",
            sources=mr.sources,
            retrieval_empty=mr.retrieval_empty,
            rewritten_query=mr.rewritten_query,
            memory_hints=mr.memory_hints,
        ),
    )


def main() -> None:
    """命令行冒烟（无 Key）。"""
    result = run_memory_rewrite("一期都学了哪些？")
    print(f"rewritten={result.rewritten_query}")
    print(f"hints={[h.id for h in result.memory_hints]}")
    print(f"sources={[h.id for h in result.sources]}")


if __name__ == "__main__":
    main()

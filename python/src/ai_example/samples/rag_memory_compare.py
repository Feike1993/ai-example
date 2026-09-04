"""RAG vs 记忆双路对照（假检索，无真 LLM）。"""

from __future__ import annotations

from dataclasses import dataclass

from ai_example.samples.memory_informed_rag import SourceHit


SKIP_GENERATE = "（generateAnswers=false，跳过生成）"
RAG_EMPTY = "知识库未检索到相关内容，无法回答。"
MEMORY_EMPTY = "未召回相关记忆，无法基于长期记忆回答。"


@dataclass
class PathView:
    """单路结果。"""

    answer: str
    sources: list[SourceHit]
    retrieval_empty: bool
    user_id: str = "demo"


@dataclass
class RagMemoryCompareResult:
    """同问双路。"""

    rag: PathView
    memory: PathView
    generate_answers: bool


_FAKE_RAG: list[SourceHit] = [
    SourceHit("rag-1", "demo.md", "RAG：分块 → Embedding → 检索再生成。", {"corpus": "ai-example-demo"}),
]

_FAKE_MEMORY: list[SourceHit] = [
    SourceHit("mem-1", "memory", "用户喜欢北京烤鸭", {"corpus": "long-term-memory"}),
]


def fake_rag_retrieve(question: str) -> list[SourceHit]:
    """假知识库检索。"""
    q = question or ""
    if any(token in q for token in ("RAG", "一期", "知识库", "检索")):
        return list(_FAKE_RAG)
    return []


def fake_memory_retrieve(question: str, user_id: str = "demo") -> list[SourceHit]:
    """假记忆召回（按 userId 过滤演示）。"""
    if user_id != "demo":
        return []
    q = question or ""
    if any(token in q for token in ("喜欢", "吃", "烤鸭", "偏好")):
        return list(_FAKE_MEMORY)
    return []


def compare_rag_memory(
    question: str,
    *,
    user_id: str = "demo",
    generate_answers: bool = True,
    rag_answer: str | None = None,
    memory_answer: str | None = None,
) -> RagMemoryCompareResult:
    """
    同问双路：rag 用 KB，memory 用个人事实。

    generate_answers=False 时两侧只回 sources / empty，答案占位跳过生成。
    """
    rag_hits = fake_rag_retrieve(question)
    mem_hits = fake_memory_retrieve(question, user_id=user_id)
    rag_empty = len(rag_hits) == 0
    mem_empty = len(mem_hits) == 0

    if not generate_answers:
        return RagMemoryCompareResult(
            rag=PathView(
                answer=SKIP_GENERATE,
                sources=rag_hits,
                retrieval_empty=rag_empty,
                user_id=user_id,
            ),
            memory=PathView(
                answer=SKIP_GENERATE,
                sources=mem_hits,
                retrieval_empty=mem_empty,
                user_id=user_id,
            ),
            generate_answers=False,
        )

    return RagMemoryCompareResult(
        rag=PathView(
            answer=RAG_EMPTY if rag_empty else (rag_answer or "RAG 路：基于知识库回答。"),
            sources=rag_hits,
            retrieval_empty=rag_empty,
            user_id=user_id,
        ),
        memory=PathView(
            answer=MEMORY_EMPTY if mem_empty else (memory_answer or "记忆路：你喜欢北京烤鸭。"),
            sources=mem_hits,
            retrieval_empty=mem_empty,
            user_id=user_id,
        ),
        generate_answers=True,
    )


def main() -> None:
    """命令行冒烟。"""
    result = compare_rag_memory("我喜欢吃什么？一期 RAG 是什么？", generate_answers=False)
    print(f"rag_empty={result.rag.retrieval_empty} mem_empty={result.memory.retrieval_empty}")
    print(f"rag_ids={[s.id for s in result.rag.sources]}")
    print(f"mem_ids={[s.id for s in result.memory.sources]}")


if __name__ == "__main__":
    main()

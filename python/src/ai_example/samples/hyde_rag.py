"""HyDE 对照：假想文档 Embedding 检索（内存向量；生产用 pgvector）。"""

from __future__ import annotations

from dataclasses import dataclass

from openai import OpenAI

from ai_example.core.client import default_model, openai_client
from ai_example.samples.hybrid_rag import rrf_fuse
from ai_example.samples.rag import (
    Chunk,
    EMPTY_REFUSAL,
    _dashscope_client,
    _embed,
    cosine,
    ingest,
)


@dataclass
class HydeResult:
    """对照 Java ExpansionView / RagQueryResult 的精简结构。"""

    query_expansion: str
    sources: list[str]
    retrieval_empty: bool
    hypothetical_document: str | None
    answer: str | None = None


def generate_hypothetical(question: str, client: OpenAI | None = None) -> str:
    """用 Chat 生成假想知识库段落（仅用于检索）。"""
    llm = client or openai_client()
    response = llm.chat.completions.create(
        model=default_model(),
        messages=[
            {
                "role": "system",
                "content": (
                    "根据用户问题写一段可能出现在技术知识库中的假想回答段落。"
                    "用陈述句、百科风格；只输出段落正文，不要解释。"
                ),
            },
            {"role": "user", "content": question},
        ],
    )
    text = (response.choices[0].message.content or "").strip()
    return text or question


def rewrite_query(question: str, client: OpenAI | None = None) -> str:
    """最小改写：口语 → 检索短句。"""
    llm = client or openai_client()
    response = llm.chat.completions.create(
        model=default_model(),
        messages=[
            {
                "role": "system",
                "content": "把用户问题改写成适合检索的短句，只输出一行，不要解释。",
            },
            {"role": "user", "content": question},
        ],
    )
    text = (response.choices[0].message.content or "").strip()
    return text or question


def _vector_top(store: list[Chunk], query_vec: list[float], top_k: int) -> list[int]:
    ranked = sorted(range(len(store)), key=lambda i: cosine(query_vec, store[i].embedding), reverse=True)
    return ranked[:top_k]


def retrieve(
    store: list[Chunk],
    question: str,
    expansion: str = "hyde",
    top_k: int = 4,
    fuse_with_original: bool = True,
    *,
    hypo_override: str | None = None,
) -> HydeResult:
    """
    按扩展策略检索。

    hypo_override：单测可注入假想文档，跳过 Chat。
    """
    embed_client = _dashscope_client()
    hypo: str | None = None
    query_text = question

    if expansion == "rewrite":
        query_text = rewrite_query(question)
    elif expansion == "hyde":
        hypo = hypo_override if hypo_override is not None else generate_hypothetical(question)
        query_text = hypo

    q_vec = _embed(embed_client, [query_text])[0]
    primary_ids = _vector_top(store, q_vec, top_k)

    if expansion == "hyde" and fuse_with_original:
        orig_vec = _embed(embed_client, [question])[0]
        original_ids = _vector_top(store, orig_vec, top_k)
        fused = rrf_fuse(primary_ids, original_ids, limit=top_k)
        hit_ids = [cid for cid, _, _, _ in fused]
    else:
        hit_ids = primary_ids

    sources = [store[i].source for i in hit_ids]
    empty = len(hit_ids) == 0
    return HydeResult(
        query_expansion=expansion,
        sources=sources,
        retrieval_empty=empty,
        hypothetical_document=hypo,
    )


def answer_grounded(question: str, store: list[Chunk], hits: list[Chunk]) -> str:
    """用真实 hits 生成答案；不把假想文档当上下文。"""
    if not hits:
        return EMPTY_REFUSAL
    context = "\n\n".join(f"[{i + 1}] source={c.source}\n{c.text}" for i, c in enumerate(hits))
    llm = openai_client()
    response = llm.chat.completions.create(
        model=default_model(),
        messages=[
            {
                "role": "system",
                "content": "只根据检索上下文回答；不足则说不知道。用简体中文。",
            },
            {"role": "user", "content": f"检索上下文：\n{context}\n\n用户问题：{question}"},
        ],
    )
    return (response.choices[0].message.content or "").strip()


def compare_expansion(question: str, store: list[Chunk] | None = None) -> dict[str, HydeResult]:
    """对照 none / rewrite / hyde 三套 sources（需 Embedding Key；hyde/rewrite 另需 Chat）。"""
    chunks = store if store is not None else ingest()
    return {
        "none": retrieve(chunks, question, expansion="none", fuse_with_original=False),
        "rewrite": retrieve(chunks, question, expansion="rewrite", fuse_with_original=False),
        "hyde": retrieve(chunks, question, expansion="hyde", fuse_with_original=True),
    }


def main() -> None:
    """命令行冒烟：假想文档注入路径（不强制打模型，演示检索形状）。"""
    # 本地演示：用固定假想段落 + 假 embedding 需要真实 Embedding Key；
    # 无 Key 时仅打印流程说明。
    print("HyDE 对照：假想文档 Embedding → 检索真实 chunk")
    print("完整跑法：uv run python -m ai_example.samples.hyde_rag")
    print("文档：docs/samples/14-hyde.md")
    try:
        store = ingest()
        # 注入假想段落，避免依赖 Chat；仍需 DashScope Embedding
        result = retrieve(
            store,
            "MCP 是什么？",
            expansion="hyde",
            hypo_override="MCP 是工具接入协议，Host 通过 Client 连接 Server 暴露工具。",
            fuse_with_original=True,
        )
        print(f"expansion={result.query_expansion} sources={result.sources}")
        print(f"hypo={result.hypothetical_document}")
    except Exception as ex:  # noqa: BLE001 — 对照脚本容错打印
        print(f"跳过在线检索（需 Embedding Key）: {ex}")


if __name__ == "__main__":
    main()

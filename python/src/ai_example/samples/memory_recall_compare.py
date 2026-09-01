"""召回策略对照：topK / 阈值 / 有无记忆。"""

from __future__ import annotations

from dataclasses import dataclass

from openai import OpenAI

from ai_example.core.client import default_model, openai_client
from ai_example.samples.long_term_memory import (
    EMPTY_REFUSAL,
    MemoryItem,
    MemoryStore,
    chat_with_memory,
    cosine,
    remember,
    _embed,
)


@dataclass
class ScoredHit:
    """带分数的召回项。"""

    item: MemoryItem
    score: float


@dataclass
class RecallBranch:
    """一路召回结果。"""

    sources: list[str]
    empty: bool
    note: str | None = None


@dataclass
class RecallCompare:
    """三路召回对照。"""

    low_top_k: RecallBranch
    high_top_k: RecallBranch
    with_threshold: RecallBranch
    low_top_k_size: int
    high_top_k_size: int
    similarity_threshold: float


def recall_scored(
    store: MemoryStore,
    query: str,
    user_id: str = "demo",
    top_k: int = 4,
    client: OpenAI | None = None,
    *,
    similarity_threshold: float | None = None,
) -> RecallBranch:
    """余弦召回；可选阈值过滤。"""
    candidates = [item for item in store.items if item.user_id == user_id]
    if not candidates:
        return RecallBranch(sources=[], empty=True)
    emb_client = client or openai_client()
    q_vec = _embed(emb_client, [query])[0]
    ranked = sorted(
        (ScoredHit(item=item, score=cosine(q_vec, item.embedding)) for item in candidates),
        key=lambda h: h.score,
        reverse=True,
    )
    note = None
    if similarity_threshold is not None:
        ranked = [h for h in ranked if h.score >= similarity_threshold]
    deduped: list[str] = []
    seen: set[str] = set()
    for hit in ranked:
        key = hit.item.text.strip()
        if not key or key in seen:
            continue
        seen.add(key)
        deduped.append(key)
        if len(deduped) >= top_k:
            break
    return RecallBranch(sources=deduped, empty=len(deduped) == 0, note=note)


def compare_recall(
    store: MemoryStore,
    query: str,
    user_id: str = "demo",
    *,
    low_top_k: int = 1,
    high_top_k: int = 8,
    similarity_threshold: float = 0.5,
    client: OpenAI | None = None,
) -> RecallCompare:
    """三路 recall 对照。"""
    return RecallCompare(
        low_top_k=recall_scored(store, query, user_id, low_top_k, client),
        high_top_k=recall_scored(store, query, user_id, high_top_k, client),
        with_threshold=recall_scored(
            store,
            query,
            user_id,
            high_top_k,
            client,
            similarity_threshold=similarity_threshold,
        ),
        low_top_k_size=low_top_k,
        high_top_k_size=high_top_k,
        similarity_threshold=similarity_threshold,
    )


def compare_chat(
    store: MemoryStore,
    prompt: str,
    user_id: str = "demo",
    *,
    generate_answers: bool = True,
    client: OpenAI | None = None,
) -> dict:
    """有记忆 vs 无记忆纯 Chat。"""
    if not generate_answers:
        branch = recall_scored(store, prompt, user_id=user_id, client=client)
        return {
            "withMemory": {
                "answer": EMPTY_REFUSAL if branch.empty else "（generateAnswers=false，跳过生成）",
                "sources": branch.sources,
                "retrievalEmpty": branch.empty,
            },
            "withoutMemory": {
                "answer": "（generateAnswers=false，跳过生成）",
                "sources": [],
                "retrievalEmpty": True,
            },
        }

    with_memory_answer, with_sources, empty = chat_with_memory(
        store, prompt, user_id=user_id, skip_llm_when_empty=True
    )
    llm = client or openai_client()
    response = llm.chat.completions.create(
        model=default_model(),
        messages=[
            {
                "role": "system",
                "content": "你是助手。用简体中文回答；没有依据时可以说不知道。",
            },
            {"role": "user", "content": prompt},
        ],
    )
    without = response.choices[0].message.content or ""
    return {
        "withMemory": {
            "answer": with_memory_answer,
            "sources": with_sources,
            "retrievalEmpty": empty,
        },
        "withoutMemory": {
            "answer": without,
            "sources": [],
            "retrievalEmpty": True,
        },
    }


def main() -> None:
    """演示对照（需 Embedding Key；chat 需 Chat Key）。"""
    store = MemoryStore()
    remember(store, "用户名叫小明，喜欢北京烤鸭", user_id="demo")
    remember(store, "用户住在杭州", user_id="demo")
    compare = compare_recall(store, "喜欢吃什么", user_id="demo", low_top_k=1, high_top_k=4)
    print("low:", compare.low_top_k.sources)
    print("high:", compare.high_top_k.sources)
    print("thr:", compare.with_threshold.sources)


if __name__ == "__main__":
    main()

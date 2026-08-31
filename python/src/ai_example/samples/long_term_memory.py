"""长期记忆对照：内存向量事实库（生产用 pgvector + 独立 corpus）。"""

from __future__ import annotations

import math
from dataclasses import dataclass, field
from uuid import uuid4

from openai import OpenAI

from ai_example.core.env import load_env

# 与 Java app.ai.memory.similarity-threshold 对齐
DEFAULT_SIMILARITY_THRESHOLD = 0.92


@dataclass
class MemoryItem:
    """一条长期记忆。"""

    user_id: str
    text: str
    embedding: list[float]
    id: str = field(default_factory=lambda: str(uuid4()))


@dataclass
class MemoryStore:
    """按 user_id 隔离的内存记忆库。"""

    items: list[MemoryItem] = field(default_factory=list)


@dataclass
class RememberResult:
    """remember 结果：duplicate=跳过；updated=相似合并改写。"""

    item: MemoryItem
    duplicate: bool = False
    updated: bool = False


EMPTY_REFUSAL = (
    "根据当前长期记忆的检索结果，没有找到相关内容，因此无法依据记忆回答。"
    "请先 remember 相关事实，或换个问法。"
)


def _dashscope_client() -> OpenAI:
    load_env()
    import os

    key = os.getenv("PROVIDER_DASHSCOPE_API_KEY") or ""
    base = os.getenv(
        "PROVIDER_DASHSCOPE_BASE_URL",
        "https://dashscope.aliyuncs.com/compatible-mode",
    )
    stripped = base.rstrip("/")
    if not stripped.endswith("/v1"):
        stripped = stripped + "/v1"
    return OpenAI(api_key=key or "missing-key", base_url=stripped)


def _embed(client: OpenAI, texts: list[str]) -> list[list[float]]:
    response = client.embeddings.create(model="text-embedding-v3", input=texts, dimensions=1024)
    return [item.embedding for item in response.data]


def cosine(a: list[float], b: list[float]) -> float:
    dot = sum(x * y for x, y in zip(a, b, strict=True))
    na = math.sqrt(sum(x * x for x in a))
    nb = math.sqrt(sum(y * y for y in b))
    if na == 0 or nb == 0:
        return 0.0
    return dot / (na * nb)


def remember(
    store: MemoryStore,
    text: str,
    user_id: str = "demo",
    client: OpenAI | None = None,
    *,
    similarity_threshold: float = DEFAULT_SIMILARITY_THRESHOLD,
) -> RememberResult:
    """写入事实：精确相同跳过；余弦相似度≥阈值则原地替换；否则新增。"""
    normalized = text.strip()
    emb_client = client or _dashscope_client()
    vec = _embed(emb_client, [normalized])[0]

    candidates = [item for item in store.items if item.user_id == user_id]
    best: MemoryItem | None = None
    best_score = -1.0
    for item in candidates:
        if item.text.strip() == normalized:
            return RememberResult(item=item, duplicate=True, updated=False)
        score = cosine(vec, item.embedding)
        if score > best_score:
            best_score = score
            best = item

    if best is not None and best_score >= similarity_threshold:
        best.text = normalized
        best.embedding = vec
        return RememberResult(item=best, duplicate=False, updated=True)

    item = MemoryItem(user_id=user_id, text=normalized, embedding=vec)
    store.items.append(item)
    return RememberResult(item=item, duplicate=False, updated=False)


def recall(
    store: MemoryStore,
    query: str,
    user_id: str = "demo",
    top_k: int = 4,
    client: OpenAI | None = None,
) -> list[MemoryItem]:
    """按用户过滤后做余弦相似度召回。"""
    candidates = [item for item in store.items if item.user_id == user_id]
    if not candidates:
        return []
    emb_client = client or _dashscope_client()
    q_vec = _embed(emb_client, [query])[0]
    ranked = sorted(candidates, key=lambda item: cosine(q_vec, item.embedding), reverse=True)
    deduped: list[MemoryItem] = []
    seen: set[str] = set()
    for item in ranked:
        key = item.text.strip()
        if not key or key in seen:
            continue
        seen.add(key)
        deduped.append(item)
        if len(deduped) >= top_k:
            break
    return deduped


def chat_with_memory(
    store: MemoryStore,
    prompt: str,
    user_id: str = "demo",
    *,
    skip_llm_when_empty: bool = True,
) -> tuple[str, list[str], bool]:
    """召回后生成；空召回可短路拒答。"""
    hits = recall(store, prompt, user_id=user_id)
    sources = [h.text for h in hits]
    empty = len(hits) == 0
    if empty and skip_llm_when_empty:
        return EMPTY_REFUSAL, sources, True

    from ai_example.core.client import default_model, openai_client

    context = "\n".join(f"[{i + 1}] {h.text}" for i, h in enumerate(hits)) or "（无记忆）"
    client = openai_client()
    response = client.chat.completions.create(
        model=default_model(),
        messages=[
            {
                "role": "system",
                "content": (
                    "只根据长期记忆回答；不足时说不知道，不要编造。"
                    "不得添加记忆中没有的信息。"
                    "允许修正明显笔误或错别字，但不得改动实体、偏好等实质含义。用简体中文。"
                ),
            },
            {"role": "user", "content": f"长期记忆：\n{context}\n\n用户问题：{prompt}"},
        ],
    )
    text = response.choices[0].message.content or ""
    return text, sources, empty


def main() -> None:
    """remember → recall → chat 演示（需 DashScope Key）。"""
    store = MemoryStore()
    print("remember…")
    remember(store, "用户名叫小明，喜欢北京烤鸭", user_id="demo")
    hits = recall(store, "喜欢吃什么", user_id="demo")
    print("recall:", [h.text for h in hits])
    answer, sources, empty = chat_with_memory(store, "根据记忆，我喜欢吃什么？")
    print(f"retrieval_empty={empty}")
    print("sources:", sources)
    print(answer[:200])


if __name__ == "__main__":
    main()

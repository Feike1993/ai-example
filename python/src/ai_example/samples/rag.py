"""RAG 对照：内存向量 + 余弦相似度（生产请用 pgvector）。"""

from __future__ import annotations

import math
import re
from dataclasses import dataclass

from openai import OpenAI

from ai_example.core.env import load_env

# 与 Java rag-docs 对齐的内置样例
_DOCS: dict[str, str] = {
    "01-phase1.md": """# 项目导读：ai-example 第一期
本仓库是 AI Agent 学习 cookbook。第一期：Chat、结构化输出、Tool Calling、Agent Loop。
""",
    "02-mcp.md": """# MCP 是什么
MCP 是工具接入协议。Function Calling 是 LLM 能力；Agent 是系统概念。传输：stdio 或 Streamable HTTP。
""",
    "03-rag.md": """# RAG 检索增强
RAG = 检索 + 生成。离线分块 Embedding 入库；在线相似度检索后拼进 prompt。Embedding 与 Chat Provider 应分离。
""",
}

# 与 Java RagSampleService.EMPTY_REFUSAL 对齐
EMPTY_REFUSAL = (
    "根据当前知识库的检索结果，没有找到与问题相关的内容，因此无法回答。"
    "请换个问法，或先确认已 ingest 相关文档。"
)


@dataclass
class Chunk:
    """内存中的一个文本块及其向量。"""

    source: str
    text: str
    embedding: list[float]


@dataclass
class RagAnswer:
    """对照 Java RagQueryResult 的回答结构。"""

    answer: str
    sources: list[str]
    retrieval_empty: bool


def _dashscope_client() -> OpenAI:
    """Embedding 固定走 DashScope（与 Java 一致）。"""
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
    """调用 text-embedding-v3。"""
    response = client.embeddings.create(model="text-embedding-v3", input=texts, dimensions=1024)
    return [item.embedding for item in response.data]


def chunk_text(text: str, max_chars: int = 280) -> list[str]:
    """按字符粗分块（对照用；Java 侧用 TokenTextSplitter）。"""
    parts = re.split(r"\n{2,}", text.strip())
    chunks: list[str] = []
    buf = ""
    for part in parts:
        part = part.strip()
        if not part:
            continue
        if len(buf) + len(part) + 1 <= max_chars:
            buf = f"{buf}\n{part}".strip()
        else:
            if buf:
                chunks.append(buf)
            buf = part
    if buf:
        chunks.append(buf)
    return chunks or [text.strip()]


def cosine(a: list[float], b: list[float]) -> float:
    """余弦相似度。"""
    dot = sum(x * y for x, y in zip(a, b, strict=True))
    na = math.sqrt(sum(x * x for x in a))
    nb = math.sqrt(sum(y * y for y in b))
    if na == 0 or nb == 0:
        return 0.0
    return dot / (na * nb)


def ingest(client: OpenAI | None = None) -> list[Chunk]:
    """分块并 Embedding，写入内存列表。"""
    emb_client = client or _dashscope_client()
    prepared: list[tuple[str, str]] = []
    for source, body in _DOCS.items():
        for piece in chunk_text(body):
            prepared.append((source, piece))
    vectors = _embed(emb_client, [t for _, t in prepared])
    return [
        Chunk(source=src, text=text, embedding=vec)
        for (src, text), vec in zip(prepared, vectors, strict=True)
    ]


def retrieve(store: list[Chunk], question: str, top_k: int = 3, client: OpenAI | None = None) -> list[Chunk]:
    """问题向量化后按余弦相似度取 topK。"""
    emb_client = client or _dashscope_client()
    q_vec = _embed(emb_client, [question])[0]
    ranked = sorted(store, key=lambda c: cosine(q_vec, c.embedding), reverse=True)
    return ranked[:top_k]


def is_retrieval_empty(hits: list[Chunk], min_sources: int = 1) -> bool:
    """命中条数是否低于 min_sources（与 Java 对齐）。"""
    return len(hits) < min_sources


def answer(
    question: str,
    hits: list[Chunk],
    *,
    min_sources: int = 1,
    skip_llm_when_empty: bool = True,
) -> RagAnswer:
    """用当前 Chat Provider 基于检索上下文生成回答；空检索可短路拒答。"""
    empty = is_retrieval_empty(hits, min_sources)
    sources = [h.source for h in hits]
    if empty and skip_llm_when_empty:
        return RagAnswer(answer=EMPTY_REFUSAL, sources=sources, retrieval_empty=True)

    from ai_example.core.client import openai_client, default_model

    context = "\n\n".join(f"[{i+1}] source={h.source}\n{h.text}" for i, h in enumerate(hits)) or "（无检索结果）"
    client = openai_client()
    response = client.chat.completions.create(
        model=default_model(),
        messages=[
            {
                "role": "system",
                "content": "只根据检索上下文回答；不足或为空时明确说不知道，不要编造。用简体中文。",
            },
            {"role": "user", "content": f"检索上下文：\n{context}\n\n用户问题：{question}"},
        ],
    )
    text = response.choices[0].message.content or ""
    return RagAnswer(answer=text, sources=sources, retrieval_empty=empty)


def main() -> None:
    """ingest → query 演示。"""
    print("ingest…")
    store = ingest()
    print(f"chunks={len(store)}")
    question = "本项目第一期学了什么？RAG 是什么？"
    hits = retrieve(store, question)
    print("sources:", [h.source for h in hits])
    result = answer(question, hits)
    print(f"retrieval_empty={result.retrieval_empty}")
    print(result.answer)

    empty_demo = answer("无关问题", [], skip_llm_when_empty=True)
    print("--- 空检索演示 ---")
    print(f"retrieval_empty={empty_demo.retrieval_empty}")
    print(empty_demo.answer)


if __name__ == "__main__":
    main()

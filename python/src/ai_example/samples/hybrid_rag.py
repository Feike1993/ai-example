"""Hybrid RAG 对照：内存向量 + 简单 BM25 + RRF（生产用 pgvector + PG 全文）。"""

from __future__ import annotations

import math
import re
from collections import Counter
from dataclasses import dataclass

from ai_example.samples.rag import Chunk, _DOCS, chunk_text, cosine, ingest, answer as rag_answer


@dataclass
class RankedChunk:
    """融合后的 chunk 与 RRF 分。"""

    chunk: Chunk
    rrf_score: float
    vector_rank: int | None
    keyword_rank: int | None


def _tokenize(text: str) -> list[str]:
    """简单分词：英文词 + 连续 CJK 字符。"""
    tokens = re.findall(r"[A-Za-z0-9]+|[\u4e00-\u9fff]", text.lower())
    return tokens or [text.lower()]


def _bm25_rank(query: str, chunks: list[Chunk], top_k: int = 4, k1: float = 1.5, b: float = 0.75) -> list[tuple[int, Chunk, float]]:
    """最小 BM25：对内存 chunk 列表打分。"""
    docs = [_tokenize(c.text) for c in chunks]
    doc_lens = [len(d) for d in docs]
    avgdl = sum(doc_lens) / max(len(doc_lens), 1)
    df: Counter[str] = Counter()
    for tokens in docs:
        df.update(set(tokens))
    n = len(docs)
    q_tokens = _tokenize(query)
    scored: list[tuple[int, Chunk, float]] = []
    for idx, (chunk, tokens) in enumerate(zip(chunks, docs, strict=True)):
        score = 0.0
        dl = doc_lens[idx]
        for term in q_tokens:
            tf = tokens.count(term)
            if tf == 0:
                continue
            idf = math.log(1 + (n - df[term] + 0.5) / (df[term] + 0.5))
            denom = tf + k1 * (1 - b + b * dl / avgdl)
            score += idf * (tf * (k1 + 1)) / denom
        scored.append((idx, chunk, score))
    scored.sort(key=lambda item: item[2], reverse=True)
    return scored[:top_k]


def rrf_fuse(
    vector_ids: list[int],
    keyword_ids: list[int],
    k: int = 60,
    limit: int = 4,
) -> list[tuple[int, float, int | None, int | None]]:
    """RRF 融合两路 chunk 下标排名（与 Java RrfFusion 步骤对齐）。"""
    scores: dict[int, float] = {}
    vector_ranks = {cid: i + 1 for i, cid in enumerate(vector_ids)}
    keyword_ranks = {cid: i + 1 for i, cid in enumerate(keyword_ids)}
    for rank_list in (vector_ids, keyword_ids):
        for i, cid in enumerate(rank_list):
            scores[cid] = scores.get(cid, 0.0) + 1.0 / (k + i + 1)
    merged = sorted(scores.items(), key=lambda item: item[1], reverse=True)[:limit]
    return [(cid, score, vector_ranks.get(cid), keyword_ranks.get(cid)) for cid, score in merged]


def hybrid_retrieve(store: list[Chunk], question: str, top_k: int = 4) -> list[RankedChunk]:
    """向量 + BM25 + RRF。"""
    from ai_example.samples.rag import _embed, _dashscope_client

    client = _dashscope_client()
    q_vec = _embed(client, [question])[0]
    vector_ranked = sorted(range(len(store)), key=lambda i: cosine(q_vec, store[i].embedding), reverse=True)
    vector_top = vector_ranked[:top_k]
    keyword_top = [idx for idx, _, _ in _bm25_rank(question, store, top_k=top_k)]
    fused = rrf_fuse(vector_top, keyword_top, limit=top_k)
    return [
        RankedChunk(
            chunk=store[cid],
            rrf_score=score,
            vector_rank=vr,
            keyword_rank=kr,
        )
        for cid, score, vr, kr in fused
    ]


def compare(question: str, store: list[Chunk]) -> tuple[list[Chunk], list[RankedChunk]]:
    """并排返回纯向量 topK 与 hybrid topK。"""
    from ai_example.samples.rag import retrieve

    vector_hits = retrieve(store, question, top_k=4)
    hybrid_hits = hybrid_retrieve(store, question, top_k=4)
    return vector_hits, hybrid_hits


def main() -> None:
    """ingest → vector vs hybrid 对照。"""
    print("ingest…")
    store = ingest()
    question = "MCP 是什么？和 Function Calling 有什么关系？"
    vector_hits, hybrid_hits = compare(question, store)
    print("--- vector sources ---")
    print([h.source for h in vector_hits])
    print("--- hybrid sources (rrf) ---")
    for item in hybrid_hits:
        print(
            item.chunk.source,
            f"rrf={item.rrf_score:.4f}",
            f"vec={item.vector_rank}",
            f"kw={item.keyword_rank}",
        )
    chunks = [item.chunk for item in hybrid_hits]
    result = rag_answer(question, chunks)
    print(result.answer[:200])


if __name__ == "__main__":
    main()

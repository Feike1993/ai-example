"""记忆辅助改写假链路单测（无 LLM）。"""

from ai_example.samples.memory_informed_rag import (
    compare_memory_rewrite,
    fake_rag_hits,
    fake_recall_hints,
    fake_rewrite,
    run_memory_rewrite,
)


def test_hints_never_enter_rag_sources():
    result = run_memory_rewrite("一期都学了哪些？")
    hint_ids = {h.id for h in result.memory_hints}
    source_ids = {s.id for s in result.sources}
    assert hint_ids
    assert source_ids
    assert hint_ids.isdisjoint(source_ids)
    assert all(sid.startswith("rag-") for sid in source_ids)
    assert all(hid.startswith("mem-") for hid in hint_ids)


def test_rewrite_includes_hint_excerpts():
    hints = fake_recall_hints("一期 Chat")
    rewritten = fake_rewrite("一期都学了哪些？", hints)
    assert "记忆先验" in rewritten
    assert hints[0].excerpt in rewritten


def test_compare_three_paths():
    compare = compare_memory_rewrite("一期都学了哪些？")
    assert compare.memory_rewrite.query_expansion == "memory_rewrite"
    assert compare.memory_rewrite.memory_hints
    assert compare.none.query_expansion == "none"
    assert compare.rewrite.rewritten_query


def test_empty_when_no_keyword():
    result = run_memory_rewrite("天气怎么样")
    assert result.memory_hints == []
    assert result.retrieval_empty is True
    assert result.sources == []
    assert "记忆先验" not in (result.rewritten_query or "")


def test_fake_rag_never_returns_mem_ids():
    hits = fake_rag_hits("一期 Chat Agent｜记忆先验：mem-1 原文")
    assert all(not h.id.startswith("mem-") for h in hits)

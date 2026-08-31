"""长期记忆：空召回、精确去重、相似合并。"""

from ai_example.samples.long_term_memory import (
    EMPTY_REFUSAL,
    MemoryItem,
    MemoryStore,
    chat_with_memory,
    recall,
    remember,
)


def test_empty_memory_refuses_without_llm(monkeypatch):
    store = MemoryStore()

    monkeypatch.setattr(
        "ai_example.samples.long_term_memory.recall",
        lambda *_a, **_k: [],
    )
    answer, sources, empty = chat_with_memory(store, "任意问题", skip_llm_when_empty=True)
    assert empty is True
    assert answer == EMPTY_REFUSAL
    assert sources == []


def test_remember_skips_exact_duplicate(monkeypatch):
    store = MemoryStore()

    def fake_embed(_client, texts):
        return [[float(len(text))] for text in texts]

    monkeypatch.setattr("ai_example.samples.long_term_memory._embed", fake_embed)

    first = remember(store, "用户名叫小明", user_id="demo", client=object())
    second = remember(store, "用户名叫小明", user_id="demo", client=object())

    assert first.duplicate is False
    assert second.duplicate is True
    assert first.item is second.item
    assert len(store.items) == 1


def test_remember_updates_similar_fact(monkeypatch):
    store = MemoryStore()
    vectors = {
        "喜欢北京烤鸭": [1.0, 0.0],
        "小明爱吃北京烤鸭": [0.98, 0.1],
        "完全无关的天气": [0.0, 1.0],
    }

    def fake_embed(_client, texts):
        return [vectors[text] for text in texts]

    monkeypatch.setattr("ai_example.samples.long_term_memory._embed", fake_embed)

    first = remember(store, "喜欢北京烤鸭", user_id="demo", client=object())
    second = remember(store, "小明爱吃北京烤鸭", user_id="demo", client=object(), similarity_threshold=0.9)

    assert first.updated is False
    assert second.updated is True
    assert second.duplicate is False
    assert len(store.items) == 1
    assert store.items[0].text == "小明爱吃北京烤鸭"
    assert store.items[0].id == first.item.id


def test_remember_inserts_when_below_threshold(monkeypatch):
    store = MemoryStore()
    vectors = {
        "喜欢北京烤鸭": [1.0, 0.0],
        "今天天气很好": [0.0, 1.0],
    }

    def fake_embed(_client, texts):
        return [vectors[text] for text in texts]

    monkeypatch.setattr("ai_example.samples.long_term_memory._embed", fake_embed)

    remember(store, "喜欢北京烤鸭", user_id="demo", client=object())
    result = remember(store, "今天天气很好", user_id="demo", client=object(), similarity_threshold=0.9)

    assert result.updated is False
    assert result.duplicate is False
    assert len(store.items) == 2


def test_recall_dedupes_identical_text(monkeypatch):
    store = MemoryStore()

    def fake_embed(_client, texts):
        return [[1.0] for _ in texts]

    monkeypatch.setattr("ai_example.samples.long_term_memory._embed", fake_embed)

    remember(store, "喜欢北京烤鸭", user_id="demo", client=object())
    store.items.append(MemoryItem(user_id="demo", text="喜欢北京烤鸭", embedding=[1.0], id="dup"))

    hits = recall(store, "喜欢吃什么", user_id="demo", top_k=4, client=object())
    assert len(hits) == 1

"""召回策略对照单测。"""

from ai_example.samples.long_term_memory import MemoryStore, remember
from ai_example.samples.memory_recall_compare import compare_chat, compare_recall, recall_scored


def test_compare_recall_threshold_filters(monkeypatch):
    store = MemoryStore()
    vectors = {
        "喜欢北京烤鸭": [1.0, 0.0],
        "住在杭州": [0.0, 1.0],
        "喜欢吃什么": [1.0, 0.05],
    }

    def fake_embed(_client, texts):
        return [vectors[text] for text in texts]

    monkeypatch.setattr("ai_example.samples.long_term_memory._embed", fake_embed)
    monkeypatch.setattr("ai_example.samples.memory_recall_compare._embed", fake_embed)

    remember(store, "喜欢北京烤鸭", user_id="demo", client=object())
    remember(store, "住在杭州", user_id="demo", client=object())

    compare = compare_recall(
        store,
        "喜欢吃什么",
        user_id="demo",
        low_top_k=1,
        high_top_k=4,
        similarity_threshold=0.9,
        client=object(),
    )
    assert len(compare.low_top_k.sources) == 1
    assert len(compare.high_top_k.sources) == 2
    assert compare.with_threshold.sources == ["喜欢北京烤鸭"]


def test_recall_scored_empty_store(monkeypatch):
    branch = recall_scored(MemoryStore(), "任意", user_id="demo", client=object())
    assert branch.empty is True


def test_compare_chat_skip_generate(monkeypatch):
    store = MemoryStore()
    result = compare_chat(store, "任意", generate_answers=False, client=object())
    assert result["withMemory"]["retrievalEmpty"] is True
    assert "跳过生成" in result["withoutMemory"]["answer"]

"""自动抽记忆：解析 JSON、写入 remember、重复跳过。"""

from ai_example.samples.long_term_memory import MemoryStore
from ai_example.samples.memory_extract import extract, parse_fact_list


def test_parse_fact_list_plain_json():
    facts = parse_fact_list('["用户叫小明", "用户住在杭州", ""]', max_facts=5)
    assert facts == ["用户叫小明", "用户住在杭州"]


def test_parse_fact_list_markdown_fence():
    raw = '```json\n["喜欢北京烤鸭"]\n```'
    assert parse_fact_list(raw) == ["喜欢北京烤鸭"]


def test_parse_fact_list_invalid_returns_empty():
    assert parse_fact_list("不是 JSON") == []
    assert parse_fact_list(None) == []


def test_extract_calls_remember_and_skips_duplicate(monkeypatch):
    store = MemoryStore()
    vectors = {
        "用户叫小明": [1.0, 0.0],
        "用户住在杭州": [0.0, 1.0],
    }

    def fake_embed(_client, texts):
        return [vectors[text] for text in texts]

    monkeypatch.setattr("ai_example.samples.long_term_memory._embed", fake_embed)

    def fake_chat(_transcript, _max_facts):
        return '["用户叫小明", "用户住在杭州"]'

    first = extract(
        store,
        [{"role": "user", "content": "我叫小明，住杭州"}],
        user_id="demo",
        client=object(),
        chat_fn=fake_chat,
    )
    assert first.facts == ["用户叫小明", "用户住在杭州"]
    assert first.skipped_duplicates == 0
    assert len(store.items) == 2

    second = extract(
        store,
        [{"role": "user", "content": "再说一遍"}],
        user_id="demo",
        client=object(),
        chat_fn=fake_chat,
    )
    assert second.skipped_duplicates == 2
    assert len(store.items) == 2

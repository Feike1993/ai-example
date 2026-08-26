"""上下文预算纯逻辑测试（不调模型）。"""

from ai_example.samples.context_memory import Session, Turn, approx_tokens, plan_summarize, trim


def test_approx_tokens():
    assert approx_tokens(["abcd"]) == 1


def test_trim_drops_old_turns():
    session = Session()
    for i in range(10):
        session.turns.append(Turn("user", f"u{i}-" + ("x" * 40)))
        session.turns.append(Turn("assistant", f"a{i}-" + ("y" * 40)))
    messages, dropped = trim(session, max_messages=6, token_budget=80)
    assert dropped > 0
    assert len(messages) <= 6
    assert messages[0]["role"] == "system"


def test_plan_summarize_needs_summary():
    session = Session()
    for i in range(8):
        session.turns.append(Turn("user", f"fact{i}-" + ("z" * 50)))
        session.turns.append(Turn("assistant", f"ok{i}"))
    old, recent, need = plan_summarize(session, keep_recent=4, max_messages=6, token_budget=50)
    assert need is True
    assert len(old) > 0
    assert len(recent) == 4

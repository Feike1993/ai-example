"""上下文工程对照：trim / summarize 预算策略（进程内列表）。"""

from __future__ import annotations

from dataclasses import dataclass, field


@dataclass
class Turn:
    """一轮用户 / 助手消息。"""

    role: str
    content: str


@dataclass
class Session:
    """简易会话。"""

    system: str = "你是助手。用简体中文回答。"
    turns: list[Turn] = field(default_factory=list)


def approx_tokens(texts: list[str]) -> int:
    """字符数 / 4 启发式。"""
    return sum(len(t) for t in texts) // 4


def trim(session: Session, max_messages: int = 8, token_budget: int = 200) -> tuple[list[dict], int]:
    """保留 system + 最近轮次，直到不超过预算。返回 (messages, dropped)。"""
    turns = list(session.turns)
    dropped = 0
    while True:
        messages = [{"role": "system", "content": session.system}]
        for turn in turns:
            messages.append({"role": turn.role, "content": turn.content})
        texts = [m["content"] for m in messages]
        if len(messages) <= max_messages and approx_tokens(texts) <= token_budget:
            return messages, dropped
        if not turns:
            return messages, dropped
        turns.pop(0)
        dropped += 1


def plan_summarize(
    session: Session, keep_recent: int = 4, max_messages: int = 8, token_budget: int = 200
) -> tuple[list[Turn], list[Turn], bool]:
    """拆出旧轮次与最近轮次；needs_summary 表示是否应调用摘要模型。"""
    texts = [session.system] + [t.content for t in session.turns]
    under_budget = (1 + len(session.turns)) <= max_messages and approx_tokens(texts) <= token_budget
    if under_budget:
        return [], list(session.turns), False
    keep = max(2, keep_recent)
    if len(session.turns) <= keep:
        return [], list(session.turns), False
    split = len(session.turns) - keep
    return list(session.turns[:split]), list(session.turns[split:]), True


def fake_summarize(old: list[Turn]) -> str:
    """单测 / CLI 用的假摘要，避免强制 Key。"""
    facts = [t.content[:40] for t in old if t.role == "user"]
    return "摘要：" + "；".join(facts)


def assemble_with_summary(session: Session, summary: str, recent: list[Turn]) -> list[dict]:
    """system + 摘要旁注 + 最近轮次。"""
    messages = [
        {"role": "system", "content": session.system},
        {"role": "system", "content": f"【历史摘要】\n{summary}"},
    ]
    for turn in recent:
        messages.append({"role": turn.role, "content": turn.content})
    return messages


def main() -> None:
    """演示 trim 与 summarize 拆分。"""
    session = Session()
    for i in range(6):
        session.turns.append(Turn("user", f"事实{i}：" + ("重要。" * 20)))
        session.turns.append(Turn("assistant", f"收到事实{i}。"))

    trimmed, dropped = trim(session, max_messages=6, token_budget=120)
    print(f"trim: sent={len(trimmed)} dropped={dropped} approx={approx_tokens([m['content'] for m in trimmed])}")

    old, recent, need = plan_summarize(session, keep_recent=4, max_messages=6, token_budget=120)
    print(f"summarize plan: need={need} old={len(old)} recent={len(recent)}")
    if need:
        summary = fake_summarize(old)
        window = assemble_with_summary(session, summary, recent)
        print(f"with summary: sent={len(window)} summary={summary[:60]}…")


if __name__ == "__main__":
    main()

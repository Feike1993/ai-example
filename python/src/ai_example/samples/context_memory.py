"""上下文工程对照：trim / summarize；可选 SQLite 持久化会话。"""

from __future__ import annotations

import argparse
import sqlite3
from dataclasses import dataclass, field
from pathlib import Path


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


class SqliteSessionStore:
    """对照 Java JdbcChatSessionStore：SQLite 持久化轮次。"""

    def __init__(self, path: str | Path) -> None:
        self.path = Path(path)
        self.path.parent.mkdir(parents=True, exist_ok=True)
        with self._conn() as conn:
            conn.execute(
                """
                CREATE TABLE IF NOT EXISTS chat_session_message (
                    session_id TEXT NOT NULL,
                    seq INTEGER NOT NULL,
                    role TEXT NOT NULL,
                    content TEXT NOT NULL,
                    PRIMARY KEY (session_id, seq)
                )
                """
            )

    def _conn(self) -> sqlite3.Connection:
        return sqlite3.connect(self.path)

    def load(self, session_id: str) -> Session:
        session = Session()
        with self._conn() as conn:
            rows = conn.execute(
                "SELECT role, content FROM chat_session_message WHERE session_id = ? ORDER BY seq",
                (session_id,),
            ).fetchall()
        for role, content in rows:
            if role == "system" and not session.turns:
                session.system = content
            else:
                session.turns.append(Turn(role=role, content=content))
        return session

    def save(self, session_id: str, session: Session) -> None:
        with self._conn() as conn:
            conn.execute("DELETE FROM chat_session_message WHERE session_id = ?", (session_id,))
            seq = 0
            conn.execute(
                "INSERT INTO chat_session_message (session_id, seq, role, content) VALUES (?, ?, ?, ?)",
                (session_id, seq, "system", session.system),
            )
            seq = 1
            for turn in session.turns:
                conn.execute(
                    "INSERT INTO chat_session_message (session_id, seq, role, content) VALUES (?, ?, ?, ?)",
                    (session_id, seq, turn.role, turn.content),
                )
                seq += 1
            conn.commit()


def main() -> None:
    """演示 trim / summarize；可选 --persist 写 SQLite。"""
    parser = argparse.ArgumentParser(description="上下文工程对照")
    parser.add_argument("--persist", metavar="DB", help="SQLite 路径，演示持久化")
    parser.add_argument("--session-id", default="persist-demo")
    args = parser.parse_args()

    session = Session()
    store: SqliteSessionStore | None = None
    if args.persist:
        store = SqliteSessionStore(args.persist)
        session = store.load(args.session_id)
        print(f"loaded session={args.session_id} turns={len(session.turns)} from {args.persist}")

    if not session.turns:
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

    if store:
        store.save(args.session_id, session)
        print(f"saved session={args.session_id} → {args.persist}")


if __name__ == "__main__":
    main()

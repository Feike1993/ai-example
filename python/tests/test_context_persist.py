"""SQLite 会话持久化单测。"""

from pathlib import Path

from ai_example.samples.context_memory import Session, SqliteSessionStore, Turn


def test_sqlite_session_roundtrip(tmp_path: Path):
    db = tmp_path / "session.db"
    store = SqliteSessionStore(db)
    session = Session(system="sys")
    session.turns = [Turn("user", "你好"), Turn("assistant", "你好呀")]
    store.save("s1", session)

    loaded = store.load("s1")
    assert loaded.system == "sys"
    assert len(loaded.turns) == 2
    assert loaded.turns[0].content == "你好"

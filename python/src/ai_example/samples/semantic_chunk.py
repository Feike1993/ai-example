"""语义分块对照：Markdown 标题/空行切段 vs 固定字符切（无 Embedding）。"""

from __future__ import annotations

import re
from dataclasses import dataclass


@dataclass
class Chunk:
    """内存语义块。"""

    text: str
    source: str
    heading: str | None = None
    chunking: str = "semantic"


_HEADING = re.compile(r"(?m)(?=^#{1,3}\s+)")


def to_segments(text: str) -> list[tuple[str | None, str]]:
    """按标题切开，再按空行切段落。返回 (heading, body)。"""
    segments: list[tuple[str | None, str]] = []
    if not text or not text.strip():
        return segments
    blocks = _HEADING.split(text)
    for block in blocks:
        if not block or not block.strip():
            continue
        trimmed = block.strip()
        heading: str | None = None
        body = trimmed
        if re.match(r"^#{1,3}\s+", trimmed):
            first, _, rest = trimmed.partition("\n")
            heading = re.sub(r"^#{1,3}\s+", "", first).strip()
            body = rest.strip()
            if not body:
                segments.append((heading, first.strip()))
                continue
        for i, para in enumerate(re.split(r"\n{2,}", body)):
            p = para.strip()
            if not p:
                continue
            content = f"## {heading}\n\n{p}" if i == 0 and heading else p
            segments.append((heading, content))
    if not segments:
        segments.append((None, text.strip()))
    return segments


def soft_merge(segments: list[tuple[str | None, str]], target_size: int = 400) -> list[tuple[str | None, str]]:
    """同标题下软合并到目标长度。"""
    target = max(50, target_size)
    merged: list[tuple[str | None, str]] = []
    buf = ""
    current_heading: str | None = segments[0][0] if segments else None
    for heading, piece in segments:
        if len(piece) > target:
            if buf:
                merged.append((current_heading, buf.strip()))
                buf = ""
            merged.append((heading, piece))
            current_heading = heading
            continue
        heading_changed = current_heading != heading
        if heading_changed and buf:
            merged.append((current_heading, buf.strip()))
            buf = ""
        if not buf:
            current_heading = heading
            buf = piece
        elif len(buf) + 2 + len(piece) <= target:
            buf = f"{buf}\n\n{piece}"
        else:
            merged.append((current_heading, buf.strip()))
            current_heading = heading
            buf = piece
    if buf:
        merged.append((current_heading, buf.strip()))
    return merged


def semantic_chunk(text: str, source: str = "demo.md", target_size: int = 400) -> list[Chunk]:
    """结构感知分块。"""
    return [
        Chunk(text=body, source=source, heading=heading, chunking="semantic")
        for heading, body in soft_merge(to_segments(text), target_size)
    ]


def token_chunk(text: str, source: str = "demo.md", max_chars: int = 280) -> list[Chunk]:
    """粗粒度字符切（对照用）。"""
    parts = re.split(r"\n{2,}", text.strip())
    chunks: list[Chunk] = []
    buf = ""
    for part in parts:
        part = part.strip()
        if not part:
            continue
        if not buf:
            buf = part
        elif len(buf) + 2 + len(part) <= max_chars:
            buf = f"{buf}\n\n{part}"
        else:
            chunks.append(Chunk(text=buf, source=source, chunking="token"))
            buf = part
    if buf:
        chunks.append(Chunk(text=buf, source=source, chunking="token"))
    return chunks


def compare_chunking(text: str, source: str = "demo.md") -> dict[str, list[Chunk]]:
    """对照 token vs semantic。"""
    return {
        "token": token_chunk(text, source=source),
        "semantic": semantic_chunk(text, source=source),
    }


def main() -> None:
    sample = """# 导读
第一期介绍 Chat。

## MCP
MCP 是工具协议。

Function Calling 是模型能力。
"""
    result = compare_chunking(sample)
    print(f"token chunks={len(result['token'])} semantic chunks={len(result['semantic'])}")
    for c in result["semantic"]:
        print(f"- heading={c.heading!r} text={c.text[:60]!r}…")


if __name__ == "__main__":
    main()

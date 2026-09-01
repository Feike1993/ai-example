"""语义分块对照：Markdown 标题/空行切段 vs 固定字符切（无 Embedding）。

与 Java SemanticMarkdownSplitter 同构：to_segments → soft_merge。
刻意差异：超长段整段保留、不做 hardSplit（Java 会按句号/换行硬切）。
"""

from __future__ import annotations

import re
from dataclasses import dataclass


@dataclass
class Chunk:
    """内存语义块（对照用，不入库）。"""

    text: str
    source: str
    heading: str | None = None
    chunking: str = "semantic"


# 零宽断言：在 H1–H3 标题行前切开，标题留在下一块开头（与 Java lookahead split 一致）
_HEADING = re.compile(r"(?m)(?=^#{1,3}\s+)")


def to_segments(text: str) -> list[tuple[str | None, str]]:
    """按标题切开，再按空行切段落。返回 (heading, body)。

    同节仅首段注入 ``## heading`` 前缀，便于对照 Embedding 语境。
    """
    segments: list[tuple[str | None, str]] = []
    if not text or not text.strip():
        return segments
    blocks = _HEADING.split(text)
    for block in blocks:
        # 文首无标题时可能空前缀，丢弃
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
                # 仅标题行 / 空节：整段保留为锚点（Java 空节写 "## heading"）
                segments.append((heading, first.strip()))
                continue
        for i, para in enumerate(re.split(r"\n{2,}", body)):
            # \n{2,}：连续空行才切段；单换行留在段内
            p = para.strip()
            if not p:
                continue
            # 仅首段注入标题前缀；同节后续段不重复标题
            content = f"## {heading}\n\n{p}" if i == 0 and heading else p
            segments.append((heading, content))
    # 无标题/空行结构时整文一块，避免吞掉内容
    if not segments:
        segments.append((None, text.strip()))
    return segments


def soft_merge(segments: list[tuple[str | None, str]], target_size: int = 400) -> list[tuple[str | None, str]]:
    """同标题下软合并到目标长度；换标题先 flush。

    与 Java 差异：单段已超 target 时整段保留，不做 hardSplit。
    """
    target = max(50, target_size)
    merged: list[tuple[str | None, str]] = []
    buf = ""
    current_heading: str | None = segments[0][0] if segments else None
    for heading, piece in segments:
        if len(piece) > target:
            # 超长：吐缓冲后整段入库（Java 此处走 hardSplit）
            if buf:
                merged.append((current_heading, buf.strip()))
                buf = ""
            merged.append((heading, piece))
            current_heading = heading
            continue
        # 换节必须断块，防止跨标题语义混装
        heading_changed = current_heading != heading
        if heading_changed and buf:
            merged.append((current_heading, buf.strip()))
            buf = ""
        if not buf:
            current_heading = heading
            buf = piece
        elif len(buf) + 2 + len(piece) <= target:
            # +2 是段间 "\n\n"；能装则软粘
            buf = f"{buf}\n\n{piece}"
        else:
            # 装不下：断在段边界，新开缓冲
            merged.append((current_heading, buf.strip()))
            current_heading = heading
            buf = piece
    if buf:
        merged.append((current_heading, buf.strip()))
    return merged


def semantic_chunk(text: str, source: str = "demo.md", target_size: int = 400) -> list[Chunk]:
    """结构感知分块：to_segments → soft_merge。"""
    return [
        Chunk(text=body, source=source, heading=heading, chunking="semantic")
        for heading, body in soft_merge(to_segments(text), target_size)
    ]


def token_chunk(text: str, source: str = "demo.md", max_chars: int = 280) -> list[Chunk]:
    """粗粒度字符切（对照用）：按空行切段再打包到 max_chars，不看标题。"""
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
    """离线对照 token vs semantic（无向量库）。"""
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

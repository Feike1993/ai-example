"""MCP Streamable HTTP 对照：尝试 list_tools 连 Java mcp-server（8081）。

若当前 MCP Python SDK 对 Streamable HTTP 支持不完整，则打印说明并退出 0，
远端 Client 以 Java 主应用为准（见 docs/samples/13-mcp-remote.md）。
"""

from __future__ import annotations

import asyncio
import os
import sys


async def _list_tools_http(url: str) -> list[str]:
    """用 MCP SDK Streamable HTTP 拉工具名；不可用则抛 ImportError / 连接错误。"""
    try:
        from mcp.client.streamable_http import streamablehttp_client
    except ImportError:
        # 兼容旧包路径命名
        from mcp.client.streamable_http import streamable_http_client as streamablehttp_client  # type: ignore

    from mcp import ClientSession

    async with streamablehttp_client(url) as (read, write, _get_session_id):
        async with ClientSession(read, write) as session:
            await session.initialize()
            listed = await session.list_tools()
            return sorted(t.name for t in listed.tools)


def main() -> None:
    url = os.getenv("MCP_SERVER_URL", "http://localhost:8081/mcp")
    print(f"尝试 Streamable HTTP list_tools → {url}")
    try:
        names = asyncio.run(_list_tools_http(url))
        print("tools:", ", ".join(names) if names else "(empty)")
    except ImportError as ex:
        print(
            "当前 MCP SDK 未提供可用的 Streamable HTTP Client；"
            "对照请用 stdio：python -m ai_example.samples.mcp_client；"
            f"远端 list_tools 以 Java Client 为准。({ex})",
            file=sys.stderr,
        )
        sys.exit(0)
    except Exception as ex:  # noqa: BLE001
        print(
            f"连接失败（请先 cd mcp-server && ./gradlew bootRun）: {ex}",
            file=sys.stderr,
        )
        sys.exit(1)


if __name__ == "__main__":
    main()

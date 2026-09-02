"""MCP Streamable HTTP 对照：尝试 list_tools 连 Java mcp-server（8081）。

若当前 MCP Python SDK 对 Streamable HTTP 支持不完整，则打印说明并退出 0，
远端 Client 以 Java 主应用为准（见 docs/samples/13-mcp-remote.md、19-mcp-bearer.md）。

鉴权：与 mcp-server 共享 MCP_BEARER_TOKEN（默认 dev-mcp-token）。
"""

from __future__ import annotations

import asyncio
import os
import sys


DEFAULT_BEARER = "dev-mcp-token"


def bearer_headers(token: str | None = None) -> dict[str, str]:
    """构造 Authorization Bearer 头；token 空则用环境变量或默认。"""
    value = (token if token is not None else os.getenv("MCP_BEARER_TOKEN", DEFAULT_BEARER)).strip()
    return {"Authorization": f"Bearer {value}"}


async def _list_tools_http(url: str, *, token: str | None = None) -> list[str]:
    """用 MCP SDK Streamable HTTP 拉工具名；不可用则抛 ImportError / 连接错误。"""
    try:
        from mcp.client.streamable_http import streamablehttp_client
    except ImportError:
        # 兼容旧包路径命名
        from mcp.client.streamable_http import streamable_http_client as streamablehttp_client  # type: ignore

    from mcp import ClientSession

    headers = bearer_headers(token)
    try:
        client_cm = streamablehttp_client(url, headers=headers)
    except TypeError:
        # 旧 SDK 无 headers 参数时退回无鉴权调用（会 401，由上层提示）
        client_cm = streamablehttp_client(url)

    async with client_cm as (read, write, _get_session_id):
        async with ClientSession(read, write) as session:
            await session.initialize()
            listed = await session.list_tools()
            return sorted(t.name for t in listed.tools)


def main() -> None:
    url = os.getenv("MCP_SERVER_URL", "http://localhost:8081/mcp")
    token = os.getenv("MCP_BEARER_TOKEN", DEFAULT_BEARER)
    print(f"尝试 Streamable HTTP list_tools → {url}（Bearer 已配置）")
    try:
        names = asyncio.run(_list_tools_http(url, token=token))
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
            f"连接失败（请先 cd mcp-server && ./gradlew bootRun，并确认 MCP_BEARER_TOKEN）: {ex}",
            file=sys.stderr,
        )
        sys.exit(1)


if __name__ == "__main__":
    main()

"""MCP Client 对照：stdio 拉起 Server → 发现工具 → 交给 OpenAI Function Calling 跑一轮。"""

from __future__ import annotations

import asyncio
import json
from contextlib import AsyncExitStack

from mcp import ClientSession, StdioServerParameters
from mcp.client.stdio import stdio_client

from ai_example.core.client import default_model, openai_client


async def _run_once(prompt: str) -> tuple[str, list[str]]:
    """连接本地 mcp_server，列出工具并完成一轮带 tool_calls 的对话。"""
    server = StdioServerParameters(
        command="uv",
        args=["run", "python", "-m", "ai_example.samples.mcp_server"],
    )
    async with AsyncExitStack() as stack:
        read, write = await stack.enter_async_context(stdio_client(server))
        session = await stack.enter_async_context(ClientSession(read, write))
        await session.initialize()
        listed = await session.list_tools()
        tool_names = sorted(t.name for t in listed.tools)

        openai_tools = []
        for tool in listed.tools:
            openai_tools.append(
                {
                    "type": "function",
                    "function": {
                        "name": tool.name,
                        "description": tool.description or "",
                        "parameters": tool.inputSchema
                        if isinstance(tool.inputSchema, dict)
                        else {"type": "object", "properties": {}},
                    },
                }
            )

        client = openai_client()
        messages: list[dict] = [
            {
                "role": "system",
                "content": "你是助手。需要天气或加法时必须调用工具，不要编造。工具来自 MCP Server。",
            },
            {"role": "user", "content": prompt},
        ]

        for _ in range(8):
            response = client.chat.completions.create(
                model=default_model(),
                messages=messages,
                tools=openai_tools or None,
            )
            message = response.choices[0].message
            messages.append(message.model_dump(exclude_unset=True))
            if not message.tool_calls:
                return message.content or "", tool_names
            for call in message.tool_calls:
                args = json.loads(call.function.arguments or "{}")
                result = await session.call_tool(call.function.name, args)
                text_parts = []
                for block in result.content:
                    text = getattr(block, "text", None)
                    if text:
                        text_parts.append(text)
                messages.append(
                    {
                        "role": "tool",
                        "tool_call_id": call.id,
                        "content": "\n".join(text_parts) if text_parts else str(result),
                    }
                )
        return "已达到最大步数，已停止。", tool_names


def main() -> None:
    """命令行入口。"""
    answer, tools = asyncio.run(_run_once("北京天气怎么样？再算 3+5"))
    print("MCP tools:", ", ".join(tools))
    print(answer)


if __name__ == "__main__":
    main()

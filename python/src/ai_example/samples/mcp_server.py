"""最小 MCP Server（stdio）：暴露与 Java DemoTools 对齐的天气 / 加法工具。"""

from mcp.server.mcpserver import MCPServer

mcp = MCPServer("ai-example-demo")


@mcp.tool(description="查询指定城市的天气。演示用，返回模拟数据。")
def get_weather(city: str) -> str:
    """返回写死的演示天气。"""
    mapping = {
        "北京": "北京：晴，25°C，北风 2 级",
        "上海": "上海：多云，28°C，东南风 3 级",
    }
    return mapping.get(city, f"{city}：阴，22°C")


@mcp.tool(description="计算两个数字的和")
def add(a: float, b: float) -> float:
    """两数求和。"""
    return a + b


def main() -> None:
    """以 stdio 传输启动 MCP Server（适合本地子进程）。"""
    mcp.run(transport="stdio")


if __name__ == "__main__":
    main()

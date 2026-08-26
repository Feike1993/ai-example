"""MCP Server 工具注册冒烟（不启动传输）。"""

from ai_example.samples import mcp_server


def test_mcp_server_tool_helpers():
    assert "北京" in mcp_server.get_weather("北京")
    assert mcp_server.add(3, 5) == 8.0

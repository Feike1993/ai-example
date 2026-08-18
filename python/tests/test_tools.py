"""工具循环单测：mock OpenAI 客户端，覆盖执行成功与 max_steps 熔断。"""

import json
from types import SimpleNamespace
from unittest.mock import MagicMock, patch

from ai_example.samples.tools import chat_with_tools


def _message(content=None, tool_calls=None):
    dumped = {"role": "assistant", "content": content}
    if tool_calls:
        dumped["tool_calls"] = [
            {
                "id": call.id,
                "type": "function",
                "function": {"name": call.function.name, "arguments": call.function.arguments},
            }
            for call in tool_calls
        ]
    return SimpleNamespace(
        content=content,
        tool_calls=tool_calls,
        model_dump=lambda exclude_unset=True: dumped,
    )


def test_tool_loop_executes_add() -> None:
    add_call = SimpleNamespace(
        id="1",
        function=SimpleNamespace(name="add", arguments=json.dumps({"a": 3, "b": 5})),
    )
    first = _message(tool_calls=[add_call])
    second = _message(content="结果是 8")

    mock_create = MagicMock()
    mock_create.side_effect = [
        SimpleNamespace(choices=[SimpleNamespace(message=first)]),
        SimpleNamespace(choices=[SimpleNamespace(message=second)]),
    ]

    with patch("ai_example.samples.tools.openai_client") as client_factory:
        client_factory.return_value.chat.completions.create = mock_create
        result = chat_with_tools("3+5")

    assert result == "结果是 8"
    assert mock_create.call_count == 2
    second_messages = mock_create.call_args_list[1].kwargs["messages"]
    tool_msgs = [m for m in second_messages if isinstance(m, dict) and m.get("role") == "tool"]
    assert tool_msgs, second_messages
    assert tool_msgs[-1]["content"] == "8.0"


def test_max_steps_stops_infinite_tool_loop() -> None:
    looping = _message(
        tool_calls=[
            SimpleNamespace(
                id="1",
                function=SimpleNamespace(name="add", arguments=json.dumps({"a": 1, "b": 1})),
            )
        ]
    )
    mock_create = MagicMock(return_value=SimpleNamespace(choices=[SimpleNamespace(message=looping)]))

    with patch("ai_example.samples.tools.openai_client") as client_factory:
        client_factory.return_value.chat.completions.create = mock_create
        result = chat_with_tools("loop", max_steps=2)

    assert "最大步数" in result
    assert mock_create.call_count == 2

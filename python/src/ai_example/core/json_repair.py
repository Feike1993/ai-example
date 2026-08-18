"""修复 LLM 常见 JSON 瑕疵，对应 Java ``JsonRepair``。"""

from __future__ import annotations

import json
import re

from pydantic import BaseModel, ValidationError

FENCE = re.compile(r"^```(?:json)?\s*|\s*```$", re.IGNORECASE | re.MULTILINE)


def strip_markdown_fence(raw: str) -> str:
    """去掉 Markdown 代码围栏；模型常把 JSON 包在 ```json 块里。"""
    text = raw.strip()
    if text.startswith("```"):
        text = text.split("\n", 1)[-1]
        if text.endswith("```"):
            text = text[: text.rfind("```")]
        return text.strip()
    return text


def parse_model(raw: str, model_type: type[BaseModel]) -> BaseModel:
    """先按 JSON 字符串校验，失败再 ``json.loads`` 后校验，兼容轻微格式差异。"""
    cleaned = strip_markdown_fence(raw)
    try:
        return model_type.model_validate_json(cleaned)
    except ValidationError:
        return model_type.model_validate(json.loads(cleaned))

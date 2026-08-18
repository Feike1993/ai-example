"""构造 OpenAI 兼容客户端；base_url 规则与 Java ``ApiPathResolver`` 对齐。"""

from openai import OpenAI

from ai_example.core.env import settings


def openai_client() -> OpenAI:
    """按 .env 创建客户端。api_key 为空时用占位串，以便进程能 import，真实调用仍会失败。"""
    cfg = settings()
    return OpenAI(api_key=cfg["api_key"] or "missing-key", base_url=_versioned(cfg["base_url"]))


def default_model() -> str:
    """当前配置的聊天模型名。"""
    return settings()["model"]


def _versioned(base_url: str) -> str:
    """末尾没有 ``/v1`` 时补上，避免 DashScope 与本地网关路径不一致。"""
    stripped = base_url.rstrip("/")
    if stripped.endswith("/v1") or "/v1/" in stripped:
        return stripped
    return stripped + "/v1"

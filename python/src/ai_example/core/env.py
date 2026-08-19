"""加载仓库根目录 .env，并按 Provider 解析 OpenAI 兼容配置。"""

from pathlib import Path

from dotenv import load_dotenv
import os

_DEFAULTS: dict[str, dict[str, str]] = {
    "deepseek": {
        "base_url": "https://api.deepseek.com",
        "model": "deepseek-v4-flash",
        "key_env": "PROVIDER_DEEPSEEK_API_KEY",
        "model_env": "PROVIDER_DEEPSEEK_MODEL",
        "url_env": "PROVIDER_DEEPSEEK_BASE_URL",
    },
    "dashscope": {
        "base_url": "https://dashscope.aliyuncs.com/compatible-mode",
        "model": "qwen3.5-flash",
        "key_env": "PROVIDER_DASHSCOPE_API_KEY",
        "model_env": "PROVIDER_DASHSCOPE_MODEL",
        "url_env": "PROVIDER_DASHSCOPE_BASE_URL",
    },
    "kimi": {
        "base_url": "https://api.moonshot.cn/v1",
        "model": "kimi-latest",
        "key_env": "PROVIDER_KIMI_API_KEY",
        "model_env": "PROVIDER_KIMI_MODEL",
        "url_env": "PROVIDER_KIMI_BASE_URL",
    },
    "glm": {
        "base_url": "https://open.bigmodel.cn/api/coding/paas/v4",
        "model": "glm-5",
        "key_env": "PROVIDER_GLM_API_KEY",
        "model_env": "PROVIDER_GLM_MODEL",
        "url_env": "PROVIDER_GLM_BASE_URL",
    },
}


def load_env() -> None:
    """从当前文件向上查找第一个 .env 并加载；已存在的环境变量不被覆盖。"""
    here = Path(__file__).resolve()
    for parent in [here, *here.parents]:
        env_file = parent / ".env"
        if env_file.exists():
            load_dotenv(env_file, override=False)
            break


def settings() -> dict[str, str]:
    """返回当前 Provider 的 base_url / api_key / model。默认 DeepSeek。"""
    load_env()
    provider = os.getenv("AI_PROVIDER", "deepseek").strip() or "deepseek"
    spec = _DEFAULTS.get(provider, _DEFAULTS["deepseek"])
    api_key = os.getenv(spec["key_env"]) or os.getenv("AI_API_KEY") or ""
    return {
        "provider": provider if provider in _DEFAULTS else "deepseek",
        "base_url": os.getenv(spec["url_env"], spec["base_url"]),
        "api_key": api_key,
        "model": os.getenv(spec["model_env"], spec["model"]),
    }

"""加载仓库根目录 .env。"""

from pathlib import Path

from dotenv import load_dotenv
import os


def load_env() -> None:
    """从当前文件向上查找第一个 .env 并加载；已存在的环境变量不被覆盖。"""
    here = Path(__file__).resolve()
    for parent in [here, *here.parents]:
        env_file = parent / ".env"
        if env_file.exists():
            load_dotenv(env_file, override=False)
            break


def settings() -> dict[str, str]:
    """返回 OpenAI 兼容调用所需的 base_url / api_key / model。"""
    load_env()
    api_key = os.getenv("AI_API_KEY") or ""
    return {
        "base_url": os.getenv("AI_BASE_URL", "https://dashscope.aliyuncs.com/compatible-mode"),
        "api_key": api_key,
        "model": os.getenv("AI_MODEL", "qwen3.5-flash"),
    }

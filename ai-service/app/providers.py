import os

from .gemini import get_gemini_provider
from .groq import get_groq_provider
from .provider import AiProvider, ProviderError

_PROVIDERS = {"gemini": get_gemini_provider, "groq": get_groq_provider}


def get_provider() -> AiProvider:
    """Chooses the AI provider from AI_PROVIDER, defaulting to Gemini, so business logic never names one."""
    factory = _PROVIDERS.get((os.environ.get("AI_PROVIDER") or "gemini").strip().lower())
    if factory is None:
        raise ProviderError("PROVIDER_ERROR")
    return factory()

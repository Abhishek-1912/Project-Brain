from functools import lru_cache
from typing import Protocol

MODEL_NAME = "BAAI/bge-small-en-v1.5"
DIMENSIONS = 384


class EmbeddingUnavailable(Exception):
    """Carries no detail, because loader errors can include local file paths."""


class Embedder(Protocol):
    def embed(self, texts: list[str]) -> list[list[float]]: ...


class _FastEmbedder:
    def __init__(self) -> None:
        # Imported here so tests and other routes never load the model or its runtime.
        from fastembed import TextEmbedding

        self._model = TextEmbedding(model_name=MODEL_NAME)

    def embed(self, texts: list[str]) -> list[list[float]]:
        return [vector.tolist() for vector in self._model.embed(texts)]


@lru_cache(maxsize=1)
def _load() -> Embedder:
    return _FastEmbedder()


def get_embedder() -> Embedder:
    try:
        return _load()
    except Exception:
        raise EmbeddingUnavailable() from None


def embed_texts(embedder: Embedder, texts: list[str]) -> list[list[float]]:
    try:
        vectors = embedder.embed(texts)
    except Exception:
        raise EmbeddingUnavailable() from None
    if len(vectors) != len(texts) or any(len(vector) != DIMENSIONS for vector in vectors):
        raise EmbeddingUnavailable()
    return vectors

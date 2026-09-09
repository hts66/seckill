"""Retrieval layer for the customer-service knowledge base.

Pluggable embeddings (local sentence-transformers by default, any
OpenAI-compatible embedding API, or a zero-dependency lexical fallback) indexed
with FAISS (inner product over L2-normalized vectors == cosine similarity) and a
NumPy fallback so the service runs even without FAISS installed. Embeddings are
cached on disk keyed by a hash of the knowledge base + embedder, so the model
only runs when the content actually changes.
"""

from __future__ import annotations

import hashlib
import json
import logging
import os
from functools import lru_cache
from pathlib import Path

import numpy as np

from .config import Settings, get_settings

logger = logging.getLogger("ai.rag")


def _l2_normalize(matrix: np.ndarray) -> np.ndarray:
    norms = np.linalg.norm(matrix, axis=1, keepdims=True)
    norms[norms == 0] = 1.0
    return (matrix / norms).astype("float32")


def _char_ngrams(text: str, n: int = 2) -> list[str]:
    text = "".join(text.lower().split())
    if len(text) < n:
        return [text] if text else []
    return [text[i : i + n] for i in range(len(text) - n + 1)]


class LocalEmbedder:
    """sentence-transformers embeddings, default BAAI/bge-small-zh-v1.5."""

    def __init__(self, model_name: str):
        # Help users behind the GFW pull weights from a mirror on first run.
        os.environ.setdefault("HF_ENDPOINT", "https://hf-mirror.com")
        os.environ.setdefault("HF_HUB_DISABLE_SYMLINKS_WARNING", "1")
        from sentence_transformers import SentenceTransformer

        self.model = SentenceTransformer(model_name)
        self.name = f"local:{model_name}"
        # get_embedding_dimension is the current name; fall back for older versions.
        dim_fn = getattr(self.model, "get_embedding_dimension", None) or self.model.get_sentence_embedding_dimension
        self.dim = int(dim_fn())

    def embed(self, texts: list[str]) -> np.ndarray:
        vectors = self.model.encode(texts, normalize_embeddings=True, convert_to_numpy=True)
        return np.asarray(vectors, dtype="float32")


class OpenAIEmbedder:
    """Any OpenAI-compatible embedding endpoint (OpenAI, SiliconFlow, Qwen...)."""

    def __init__(self, settings: Settings):
        from langchain_openai import OpenAIEmbeddings

        self.client = OpenAIEmbeddings(
            api_key=settings.embedding_api_key,
            base_url=settings.embedding_api_base,
            model=settings.embedding_api_model,
        )
        self.name = f"openai:{settings.embedding_api_model}"
        self.dim = 0

    def embed(self, texts: list[str]) -> np.ndarray:
        vectors = _l2_normalize(np.asarray(self.client.embed_documents(texts), dtype="float32"))
        self.dim = vectors.shape[1]
        return vectors


class HashEmbedder:
    """Zero-dependency lexical fallback so the service never hard-fails.

    Character-bigram hashing — keeps things running when no model or API is
    available, but it is lexical, not semantic. Install sentence-transformers or
    set an embedding API for real quality.
    """

    def __init__(self, dim: int = 384):
        self.dim = dim
        self.name = "hash-fallback"

    def embed(self, texts: list[str]) -> np.ndarray:
        out = np.zeros((len(texts), self.dim), dtype="float32")
        for row, text in enumerate(texts):
            for token in _char_ngrams(text):
                bucket = int(hashlib.md5(token.encode("utf-8")).hexdigest(), 16) % self.dim
                out[row, bucket] += 1.0
        return _l2_normalize(out)


def build_embedder(settings: Settings):
    provider = (settings.embedding_provider or "local").lower()
    if provider == "openai" and settings.embedding_api_key:
        try:
            return OpenAIEmbedder(settings)
        except Exception as exc:  # noqa: BLE001 - degrade gracefully
            logger.warning("OpenAI embedder unavailable (%s); trying local model", exc)
    if provider in ("local", "openai"):
        try:
            return LocalEmbedder(settings.embedding_model)
        except Exception as exc:  # noqa: BLE001 - degrade gracefully
            logger.warning("Local embedder unavailable (%s); using hash fallback", exc)
    return HashEmbedder()


class RagEngine:
    def __init__(self, settings: Settings):
        self.settings = settings
        self.embedder = build_embedder(settings)
        self.docs: list[dict] = []
        self._matrix: np.ndarray = np.zeros((0, 0), dtype="float32")
        self._index = None
        self._build()

    @staticmethod
    def _doc_text(doc: dict) -> str:
        parts = [doc.get("question", ""), " ".join(doc.get("keywords", []) or []), doc.get("answer", "")]
        return "\n".join(part for part in parts if part)

    def _load_knowledge(self) -> list[dict]:
        raw = json.loads(Path(self.settings.knowledge_file).read_text(encoding="utf-8"))
        docs = []
        for i, item in enumerate(raw):
            doc = dict(item)
            doc.setdefault("id", f"kb-{i + 1}")
            docs.append(doc)
        return docs

    def _build(self) -> None:
        self.docs = self._load_knowledge()
        texts = [self._doc_text(doc) for doc in self.docs]
        signature = hashlib.sha256(
            (json.dumps(self.docs, ensure_ascii=False, sort_keys=True) + "|" + self.embedder.name).encode("utf-8")
        ).hexdigest()[:16]
        cache_dir = Path(self.settings.index_cache_dir)
        cache_dir.mkdir(parents=True, exist_ok=True)
        vec_path = cache_dir / f"kb-{signature}.npy"
        if vec_path.exists():
            embeddings = np.load(vec_path).astype("float32")
        else:
            embeddings = self.embedder.embed(texts)
            for stale in cache_dir.glob("kb-*.npy"):
                stale.unlink()
            np.save(vec_path, embeddings)
        self._matrix = embeddings
        try:
            import faiss

            index = faiss.IndexFlatIP(embeddings.shape[1])
            index.add(embeddings)
            self._index = index
            backend = "faiss"
        except Exception as exc:  # noqa: BLE001 - numpy is a fine fallback
            logger.info("FAISS unavailable (%s); using numpy cosine search", exc)
            self._index = None
            backend = "numpy"
        logger.info(
            "RAG ready: %d docs, dim=%d, embedder=%s, backend=%s",
            len(self.docs), embeddings.shape[1], self.embedder.name, backend,
        )

    def search(self, query: str, k: int = 3) -> list[dict]:
        if not self.docs:
            return []
        vector = self.embedder.embed([query])
        top = min(k, len(self.docs))
        if self._index is not None:
            scores, idx = self._index.search(vector, top)
            pairs = list(zip(idx[0].tolist(), scores[0].tolist()))
        else:
            sims = self._matrix @ vector[0]
            order = np.argsort(-sims)[:top]
            pairs = [(int(i), float(sims[i])) for i in order]
        hits = []
        for doc_idx, score in pairs:
            if doc_idx < 0:
                continue
            doc = self.docs[doc_idx]
            hits.append(
                {
                    "id": doc.get("id"),
                    "question": doc.get("question"),
                    "answer": doc.get("answer"),
                    "category": doc.get("category"),
                    "score": round(float(score), 4),
                }
            )
        return hits


@lru_cache(maxsize=1)
def get_rag_engine() -> RagEngine:
    return RagEngine(get_settings())

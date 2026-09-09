from functools import lru_cache

from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    model_config = SettingsConfigDict(env_file=".env", extra="ignore")

    # Chat model (DeepSeek, OpenAI-compatible)
    deepseek_api_key: str = ""
    deepseek_base_url: str = "https://api.deepseek.com"
    deepseek_model: str = "deepseek-chat"

    ai_service_port: int = 8200
    internal_token: str = ""
    order_service_url: str = "http://localhost:8104"
    product_service_url: str = "http://localhost:8102"
    activity_service_url: str = "http://localhost:8103"
    redis_url: str = "redis://localhost:6380/2"
    conversation_ttl_seconds: int = 86400
    ai_max_history: int = 12

    # Knowledge base + RAG retrieval
    knowledge_file: str = "app/faq.json"
    index_cache_dir: str = ".rag_cache"
    rag_top_k: int = 3
    rag_min_score: float = 0.45
    # embedding_provider: "local" (sentence-transformers) | "openai" (any OpenAI-compatible endpoint)
    embedding_provider: str = "local"
    embedding_model: str = "BAAI/bge-small-zh-v1.5"
    embedding_api_base: str = "https://api.openai.com/v1"
    embedding_api_key: str = ""
    embedding_api_model: str = "text-embedding-3-small"

    # Observability
    log_level: str = "INFO"
    # LangSmith tracing (optional). When enabled these are exported to the env
    # so LangChain/LangGraph pick them up automatically.
    langchain_tracing_v2: bool = False
    langchain_api_key: str = ""
    langchain_project: str = "seckill-ai-customer-service"
    langchain_endpoint: str = "https://api.smith.langchain.com"


@lru_cache
def get_settings() -> Settings:
    return Settings()

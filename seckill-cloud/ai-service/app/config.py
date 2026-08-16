from functools import lru_cache

from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    model_config = SettingsConfigDict(env_file=".env", extra="ignore")

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
    faq_file: str = "app/faq.json"
    ai_max_history: int = 12


@lru_cache
def get_settings() -> Settings:
    return Settings()

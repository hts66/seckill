import json
import uuid
from dataclasses import dataclass

try:
    from redis.asyncio import Redis
except ImportError:  # Redis is an optional local-development enhancement.
    Redis = None

from .config import Settings


@dataclass
class StoredMessage:
    role: str
    content: str


class ConversationStore:
    def __init__(self, settings: Settings):
        self.settings = settings
        self.redis = Redis.from_url(settings.redis_url, decode_responses=True) if Redis else None
        self.memory: dict[str, list[StoredMessage]] = {}

    async def close(self) -> None:
        if self.redis:
            await self.redis.aclose()

    async def create(self, user_id: int) -> str:
        conversation_id = str(uuid.uuid4())
        await self._save(conversation_id, user_id, [])
        return conversation_id

    async def load(self, conversation_id: str, user_id: int) -> list[StoredMessage]:
        key = self._key(conversation_id, user_id)
        try:
            value = await self.redis.get(key) if self.redis else None
            if value is not None:
                return [StoredMessage(**item) for item in json.loads(value)]
        except Exception:
            pass
        return self.memory.get(key, [])

    async def append(self, conversation_id: str, user_id: int, messages: list[StoredMessage]) -> None:
        current = await self.load(conversation_id, user_id)
        current.extend(messages)
        current = current[-self.settings.ai_max_history:]
        await self._save(conversation_id, user_id, current)

    async def _save(self, conversation_id: str, user_id: int, messages: list[StoredMessage]) -> None:
        key = self._key(conversation_id, user_id)
        self.memory[key] = messages
        try:
            if self.redis:
                await self.redis.setex(
                    key,
                    self.settings.conversation_ttl_seconds,
                    json.dumps([message.__dict__ for message in messages], ensure_ascii=False),
                )
        except Exception:
            pass

    @staticmethod
    def _key(conversation_id: str, user_id: int) -> str:
        return f"ai:conversation:{user_id}:{conversation_id}"

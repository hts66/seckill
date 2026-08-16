from typing import Generic, Literal, TypeVar

from pydantic import BaseModel, ConfigDict, Field


T = TypeVar("T")


class ApiResponse(BaseModel, Generic[T]):
    code: int = 200
    message: str = "success"
    data: T


class UserContext(BaseModel):
    user_id: int
    email: str | None = None
    role: int = 0


class ConversationCreateResponse(BaseModel):
    model_config = ConfigDict(populate_by_name=True)

    conversation_id: str = Field(alias="conversationId")


class ChatRequest(BaseModel):
    message: str = Field(min_length=1, max_length=2000)


class ChatResponse(BaseModel):
    model_config = ConfigDict(populate_by_name=True)

    conversation_id: str = Field(alias="conversationId")
    message_id: str = Field(alias="messageId")
    answer: str


class HealthResponse(BaseModel):
    status: Literal["ok"] = "ok"
    model_configured: bool

import asyncio
import json
from typing import Any

import httpx
from langchain_core.tools import tool

from .config import Settings
from .models import UserContext
from .rag import get_rag_engine


class BusinessTools:
    def __init__(self, settings: Settings, user: UserContext):
        self.settings = settings
        self.user = user
        self.client = httpx.AsyncClient(timeout=8.0)

    async def close(self) -> None:
        await self.client.aclose()

    def _headers(self) -> dict[str, str]:
        return {
            "X-Internal-Token": self.settings.internal_token,
            "X-User-Id": str(self.user.user_id),
            "X-User-Email": self.user.email or "",
            "X-User-Role": str(self.user.role),
        }

    async def _get(self, base_url: str, path: str) -> Any:
        response = await self.client.get(f"{base_url.rstrip('/')}{path}", headers=self._headers())
        response.raise_for_status()
        body = response.json()
        if body.get("code") not in (None, 200):
            raise RuntimeError(body.get("message") or "业务服务返回错误")
        return body.get("data", body)

    def all_tools(self):
        @tool
        async def search_faq(query: str) -> str:
            """语义检索平台规则 FAQ 知识库。优先用于抢购规则、支付、订单、地址、退款和账号问题。"""
            engine = get_rag_engine()
            # Embedding + vector search is sync CPU work; keep the event loop free.
            hits = await asyncio.to_thread(engine.search, query, self.settings.rag_top_k)
            relevant = [hit for hit in hits if hit["score"] >= self.settings.rag_min_score]
            if not relevant:
                return "没有找到匹配的 FAQ。不要编造平台规则，请引导用户到对应页面或人工客服。"
            payload = [
                {
                    "question": hit["question"],
                    "answer": hit["answer"],
                    "category": hit["category"],
                    "score": hit["score"],
                }
                for hit in relevant
            ]
            return json.dumps(payload, ensure_ascii=False)

        @tool
        async def query_my_orders(status: str = "") -> str:
            """查询当前登录用户自己的订单。只能返回当前用户数据。"""
            try:
                data = await self._get(self.settings.order_service_url, "/api/orders")
            except Exception as exc:
                return f"订单服务暂时不可用：{exc}"
            if status:
                data = [item for item in data if str(item.get("status")) == status or status in str(item)]
            safe = []
            for item in data[:10]:
                safe.append({
                    "orderNo": item.get("orderNo"),
                    "status": item.get("status"),
                    "fulfillmentStatus": item.get("fulfillmentStatus"),
                    "amount": item.get("amount"),
                    "createdAt": item.get("createdAt"),
                    "hasAddress": bool(item.get("receiverName")),
                    "shippingTime": item.get("shippingTime"),
                })
            return json.dumps(safe, ensure_ascii=False)

        @tool
        async def query_products(keyword: str = "") -> str:
            """查询公开商品信息和商品库存展示。"""
            try:
                data = await self._get(self.settings.product_service_url, "/api/products")
            except Exception as exc:
                return f"商品服务暂时不可用：{exc}"
            if keyword:
                data = [item for item in data if keyword.lower() in json.dumps(item, ensure_ascii=False).lower()]
            return json.dumps(data[:10], ensure_ascii=False)

        @tool
        async def query_activities(upcoming: bool = False) -> str:
            """查询当前或即将开始的秒杀活动和商品。"""
            path = "/api/seckill/upcoming" if upcoming else "/api/seckill/items"
            try:
                data = await self._get(self.settings.activity_service_url, path)
            except Exception as exc:
                return f"活动服务暂时不可用：{exc}"
            return json.dumps(data[:20], ensure_ascii=False)

        return [search_faq, query_my_orders, query_products, query_activities]

"""Offline evaluation for the customer-service RAG agent.

Two modes:
  retrieval (default) — no LLM, cheap and deterministic. Measures how well the
      vector search surfaces the correct knowledge-base entry: recall@1,
      recall@k, MRR, plus out-of-scope rejection accuracy.
  e2e — needs DEEPSEEK_API_KEY. Runs the full LangGraph agent per question and
      measures tool-routing accuracy, answer keyword coverage, latency, tokens.

Run from the ai-service directory:
    python eval/run_eval.py                 # retrieval mode
    python eval/run_eval.py --mode e2e      # end-to-end (needs API key + services)
    python eval/run_eval.py --report eval/report.json
"""

from __future__ import annotations

import argparse
import asyncio
import json
import sys
import time
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))

from app.config import get_settings  # noqa: E402


def load_dataset(path: Path) -> list[dict]:
    cases = []
    for line in path.read_text(encoding="utf-8").splitlines():
        line = line.strip()
        if line:
            cases.append(json.loads(line))
    return cases


def evaluate_retrieval(cases: list[dict], k: int) -> dict:
    from app.rag import get_rag_engine

    settings = get_settings()
    engine = get_rag_engine()
    min_score = settings.rag_min_score

    graded = []
    hit1 = hitk = rr = 0.0
    retrieval_cases = 0
    no_match_total = no_match_ok = 0
    for case in cases:
        gold = case.get("expected_faq_id")
        expect_no = case.get("expect_no_match", False)
        if gold is None and not expect_no:
            continue  # tool-routing case, not scored in retrieval mode
        hits = engine.search(case["question"], k)
        top = [(h["id"], h["score"]) for h in hits]
        best_score = top[0][1] if top else 0.0
        if expect_no:
            no_match_total += 1
            ok = best_score < min_score
            no_match_ok += int(ok)
            graded.append({"q": case["question"], "type": "no_match", "ok": ok, "best": round(best_score, 3)})
            continue
        retrieval_cases += 1
        ids = [i for i, _ in top]
        rank = ids.index(gold) + 1 if gold in ids else 0
        hit1 += int(rank == 1)
        hitk += int(rank >= 1)
        rr += (1.0 / rank) if rank else 0.0
        graded.append({"q": case["question"], "gold": gold, "rank": rank, "top": ids, "best": round(best_score, 3)})

    n = max(retrieval_cases, 1)
    summary = {
        "mode": "retrieval",
        "embedder": engine.embedder.name,
        "cases": retrieval_cases,
        "recall@1": round(hit1 / n, 3),
        f"recall@{k}": round(hitk / n, 3),
        "mrr": round(rr / n, 3),
        "no_match_cases": no_match_total,
        "no_match_accuracy": round(no_match_ok / max(no_match_total, 1), 3),
    }
    return {"summary": summary, "graded": graded}


async def _run_agent(question: str, timeout: float):
    from app.agent import build_graph, initial_messages
    from app.models import UserContext
    from app.observability import MetricsCallbackHandler

    settings = get_settings()
    user = UserContext(user_id=0, email="eval@example.com", role=0)
    graph, tools = build_graph(settings, user)
    handler = MetricsCallbackHandler()
    started = time.perf_counter()
    answer = ""
    try:
        result = await asyncio.wait_for(
            graph.ainvoke({"messages": initial_messages([], question)}, config={"callbacks": [handler]}),
            timeout=timeout,
        )
        answer = str(result["messages"][-1].content)
    finally:
        await tools.close()
    elapsed = (time.perf_counter() - started) * 1000
    return answer, [t["name"] for t in handler.tool_calls], elapsed, handler.tokens["total"]


def evaluate_e2e(cases: list[dict], timeout: float) -> dict:
    settings = get_settings()
    if not settings.deepseek_api_key:
        print("e2e mode needs DEEPSEEK_API_KEY (set it in .env). Skipping.")
        sys.exit(2)

    graded = []
    tool_total = tool_ok = ans_total = ans_ok = tokens = 0
    latencies = []
    for case in cases:
        answer, tool_names, elapsed, used = asyncio.run(_run_agent(case["question"], timeout))
        latencies.append(elapsed)
        tokens += used
        row = {"q": case["question"], "tools": tool_names, "ms": round(elapsed, 1)}
        expected_tool = case.get("expected_tool")
        if not expected_tool and case.get("expected_faq_id"):
            expected_tool = "search_faq"
        if expected_tool:
            tool_total += 1
            row["expected_tool"] = expected_tool
            row["tool_ok"] = expected_tool in tool_names
            tool_ok += int(row["tool_ok"])
        contains = case.get("answer_contains") or []
        if contains:
            ans_total += 1
            row["answer_ok"] = all(term in answer for term in contains)
            ans_ok += int(row["answer_ok"])
        graded.append(row)

    summary = {
        "mode": "e2e",
        "model": settings.deepseek_model,
        "cases": len(cases),
        "tool_routing_accuracy": round(tool_ok / max(tool_total, 1), 3),
        "answer_coverage": round(ans_ok / max(ans_total, 1), 3),
        "avg_latency_ms": round(sum(latencies) / max(len(latencies), 1), 1),
        "total_tokens": tokens,
    }
    return {"summary": summary, "graded": graded}


def main() -> None:
    parser = argparse.ArgumentParser(description="Evaluate the customer-service RAG agent")
    parser.add_argument("--mode", choices=["retrieval", "e2e"], default="retrieval")
    parser.add_argument("--dataset", default=str(Path(__file__).with_name("dataset.jsonl")))
    parser.add_argument("--k", type=int, default=None, help="top-k for retrieval (default: settings.rag_top_k)")
    parser.add_argument("--timeout", type=float, default=45.0, help="per-question timeout for e2e mode")
    parser.add_argument("--report", default=None, help="optional path to write a JSON report")
    args = parser.parse_args()

    cases = load_dataset(Path(args.dataset))
    k = args.k or get_settings().rag_top_k
    result = evaluate_retrieval(cases, k) if args.mode == "retrieval" else evaluate_e2e(cases, args.timeout)

    print("\n=== summary ===")
    for key, value in result["summary"].items():
        print(f"{key:>22}: {value}")
    print("\n=== per-case ===")
    for row in result["graded"]:
        print(json.dumps(row, ensure_ascii=False))

    if args.report:
        Path(args.report).write_text(json.dumps(result, ensure_ascii=False, indent=2), encoding="utf-8")
        print(f"\nreport written to {args.report}")


if __name__ == "__main__":
    main()

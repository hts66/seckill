from playwright.sync_api import sync_playwright
import sys

FORM = "file:///C:/Users/27036/Desktop/seckill/_diag/pay.html"

with sync_playwright() as p:
    browser = p.chromium.launch(headless=True)
    page = browser.new_page()
    page.goto(FORM)
    # wait for the cross-origin navigation to alipay + any further redirects to settle
    try:
        page.wait_for_url(lambda u: "pay.html" not in u, timeout=8000)
    except Exception:
        pass
    page.wait_for_timeout(2500)
    u = page.url
    verdict = "ERROR(/error)" if "/error" in u else ("CASHIER/LOGIN " + u) if ("appAssign" in u or "login" in u or "cashier" in u) else u
    print("final:", verdict)
    browser.close()

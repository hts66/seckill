from playwright.sync_api import sync_playwright
import time

FORM = "file:///C:/Users/27036/Desktop/seckill/_diag/pay.html"

with sync_playwright() as p:
    browser = p.chromium.launch(headless=True)
    page = browser.new_page()

    nav_chain = []
    page.on("framenavigated", lambda f: nav_chain.append(f.url) if f == page.main_frame else None)

    page.goto(FORM)
    # form auto-submits via document.forms[0].submit(); give it time to bounce through alipay
    for _ in range(20):
        page.wait_for_timeout(700)
        u = page.url
        if "pay.html" not in u:
            # keep waiting a bit for further redirects to settle
            pass
    page.wait_for_timeout(1500)

    print("FINAL URL  :", page.url)
    print("FINAL TITLE:", page.title())
    print("NAV CHAIN  :")
    seen = set()
    for u in nav_chain:
        if u not in seen:
            seen.add(u)
            print("   ->", u)
    body_text = page.evaluate("() => document.body ? document.body.innerText.slice(0,400) : ''")
    print("BODY TEXT  :", " ".join(body_text.split()))
    page.screenshot(path="C:/Users/27036/Desktop/seckill/_diag/pay_result.png", full_page=True)
    print("screenshot -> _diag/pay_result.png")
    browser.close()

from pathlib import Path
from playwright.sync_api import sync_playwright

token = Path('../jmeter/tokens-current.csv').read_text(encoding='utf-8').splitlines()[1]
user = '{"id":6,"email":"test1@test.com","username":"测试用户","role":0}'

with sync_playwright() as p:
    browser = p.chromium.launch(headless=True)
    page = browser.new_page(viewport={"width": 1280, "height": 800})
    errors = []
    page.on('console', lambda msg: errors.append(msg.text) if msg.type == 'error' else None)
    page.goto('http://localhost:5173')
    page.evaluate("([t,u]) => { localStorage.setItem('token',t); localStorage.setItem('user',u) }", [token, user])
    page.goto('http://localhost:5173/addresses')
    page.wait_for_load_state('networkidle')
    assert page.get_by_role('heading', name='收货地址').is_visible()
    assert page.get_by_role('button', name='新增地址').is_visible()
    page.screenshot(path='../tmp/address-page.png', full_page=True)
    page.goto('http://localhost:5173/seckill/6')
    page.wait_for_load_state('networkidle')
    assert page.locator('.address-select-head strong').is_visible()
    assert page.locator('.address-option.selected').is_visible()
    assert page.get_by_role('button', name='立即抢购').is_enabled()
    page.screenshot(path='../tmp/seckill-address-selected.png', full_page=True)
    assert not errors, errors
    browser.close()
print('address UI PASS')

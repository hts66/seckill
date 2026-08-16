from playwright.sync_api import sync_playwright


with sync_playwright() as p:
    browser = p.chromium.launch(headless=True)
    page = browser.new_page(viewport={"width": 1280, "height": 900})
    page.add_init_script("""
      localStorage.setItem('token', 'test-token')
      localStorage.setItem('user', JSON.stringify({id: 1, role: 1, username: 'tester'}))
    """)

    def mock(route):
        url = route.request.url
        if url.endswith('/api/seckill/path/1'):
            route.fulfill(json={"code": 200, "data": {"path": "path-key"}})
        elif '/api/seckill/result/1' in url:
            route.fulfill(json={"code": 200, "data": {"status": 0, "message": "排队中"}})
        elif '/api/seckill/execute/' in url:
            route.fulfill(json={"code": 200, "data": {"status": 1, "orderNo": "order-1"}})
        elif url.endswith('/api/orders'):
            route.fulfill(json={"code": 200, "data": [{"id": 1, "orderNo": "order-1", "status": 1, "fulfillmentStatus": 0, "amount": 1}]})
        elif url.endswith('/api/addresses'):
            route.fulfill(json={"code": 200, "data": []})
        else:
            route.continue_()

    page.route('**/api/**', mock)
    page.goto('http://127.0.0.1:5173/seckill/1')
    page.wait_for_load_state('networkidle')
    page.wait_for_timeout(3500)
    assert page.get_by_role('heading', name='恭喜抢到').is_visible()
    assert page.get_by_text('请前往订单页支付并填写收货地址').is_visible()
    assert not page.get_by_text('请先选择收货地址').count()
    page.screenshot(path='tmp/address-after-seckill.png', full_page=True)
    page.goto('http://127.0.0.1:5173/orders')
    page.wait_for_load_state('networkidle')
    assert page.get_by_text('待填写收货地址，填写后商家才能发货').is_visible()
    assert page.get_by_role('button', name='填写地址').is_visible()
    browser.close()

print('address flow UI PASS')

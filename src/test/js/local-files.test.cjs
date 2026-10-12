const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const path = require('node:path');
const script = fs.readFileSync(path.join(__dirname, '../../main/resources/local-files/app.js'), 'utf8');

for (const escaped of [false, true]) {
  test(`open page submits original signed query with escaped separators=${escaped}`, async () => {
    const query = '?workspace=%2Ftmp&path=%2Ftmp%2Fa%26amp%3Bb.kt&line=47&column=2&editor=trae&signature=test';
    const elements = new Map();
    const calls = [];
    vm.runInNewContext(script, {
      location: { search: escaped ? query.replaceAll('&', '&amp;') : query },
      URLSearchParams,
      AbortSignal,
      window: { close() {}, addEventListener() {} },
      setInterval: () => 1,
      clearInterval() {},
      document: { getElementById(id) {
        if (!elements.has(id)) elements.set(id, { addEventListener() {} });
        return elements.get(id);
      } },
      fetch: async (url, options) => { calls.push({ url, options }); return { status: 200 }; }
    });
    await new Promise(resolve => setImmediate(resolve));
    assert.equal(calls.length, 1);
    assert.equal(calls[0].url, '/api/open' + query);
    assert.equal(calls[0].options.method, 'POST');
    assert.equal(elements.get('path').textContent, '/tmp/a&amp;b.kt');
    assert.match(elements.get('status').textContent, /已请求 Trae CN/);
  });
}

for (const scenario of ['success', 'cancel', 'failure', 'retry', 'pagehide']) {
  test(`auto-close countdown handles ${scenario}`, async () => {
    const elements = new Map();
    const timers = new Map();
    const windowHandlers = new Map();
    let now = 0;
    let closes = 0;
    let nextTimer = 0;
    vm.runInNewContext(script, {
      location: { search: '?workspace=%2Ftmp&path=%2Ftmp%2Fa.kt&editor=trae&signature=test' },
      URLSearchParams, AbortSignal,
      Date: { now: () => now },
      window: {
        close() { closes++; },
        addEventListener(name, handler) { windowHandlers.set(name, handler); }
      },
      setInterval(handler) { timers.set(++nextTimer, handler); return nextTimer; },
      clearInterval(timer) { timers.delete(timer); },
      document: { getElementById(id) {
        if (!elements.has(id)) elements.set(id, {
          handlers: new Map(),
          addEventListener(name, handler) { this.handlers.set(name, handler); }
        });
        return elements.get(id);
      } },
      fetch: async () => ({ status: scenario === 'failure' ? 404 : 200 })
    });
    await new Promise(resolve => setImmediate(resolve));
    if (scenario === 'failure') {
      assert.equal(timers.size, 0);
      assert.equal(elements.get('cancel-close').disabled, true);
      return;
    }
    assert.match(elements.get('countdown').textContent, /10 秒/);
    now = 3000;
    for (const handler of [...timers.values()]) handler();
    assert.match(elements.get('countdown').textContent, /7 秒/);
    if (scenario === 'cancel' || scenario === 'retry') {
      elements.get('cancel-close').handlers.get('click')();
      assert.equal(timers.size, 0);
      assert.match(elements.get('countdown').textContent, /已取消/);
      if (scenario === 'retry') await elements.get('retry').handlers.get('click')();
      assert.equal(timers.size, 0);
    } else if (scenario === 'pagehide') {
      windowHandlers.get('pagehide')();
      assert.equal(timers.size, 0);
    } else {
      now = 10000;
      for (const handler of [...timers.values()]) handler();
      assert.equal(closes, 1);
      assert.equal(timers.size, 0);
      assert.match(elements.get('countdown').textContent, /手动关闭/);
    }
  });
}

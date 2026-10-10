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

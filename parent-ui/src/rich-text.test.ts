import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import vm from 'node:vm';

const elements: any[] = [];
const mockWindow: any = {
  renderMathInElement: (el: any) => {
    elements.push(el);
  },
};
const mockDoc: any = {
  documentElement: { contains: () => true },
  head: { appendChild: () => {} },
  createElement: () => ({}),
};

const code = readFileSync(new URL('../../shared/math.js', import.meta.url), 'utf8');
vm.runInNewContext(code, { window: mockWindow, document: mockDoc });

// 1. XSS escaping + rich text formatting
const el = { innerHTML: '', textContent: '' };
mockWindow.SOTV_RICH(el, '<script>alert(1)</script> **加粗** *斜体* __下划线__ ==重点== `a<b`');
assert.ok(!el.innerHTML.includes('<script>'), 'script tag must be escaped');
assert.ok(el.innerHTML.includes('&lt;script&gt;alert(1)&lt;/script&gt;'));
assert.ok(el.innerHTML.includes('<strong>加粗</strong>'));
assert.ok(el.innerHTML.includes('<em>斜体</em>'));
assert.ok(el.innerHTML.includes('<u>下划线</u>'));
assert.ok(el.innerHTML.includes('<mark class="sotv-mark">重点</mark>'));
assert.ok(el.innerHTML.includes('<code class="sotv-code">a&lt;b</code>'));

// 2. Math spans with * or _ must NOT be mangled by bold/italic/underline
const mathEl = { innerHTML: '', textContent: '' };
mockWindow.SOTV_RICH(mathEl, '计算 $a * b + x_1 + y_2$ 的值，注意 **关键**');
assert.ok(mathEl.innerHTML.includes('$a * b + x_1 + y_2$'), 'math span must stay intact');
assert.ok(mathEl.innerHTML.includes('<strong>关键</strong>'));

// 3. Bullet list lines
const listEl = { innerHTML: '', textContent: '' };
mockWindow.SOTV_RICH(listEl, '步骤：\n- 第一步\n- 第二步');
assert.ok(listEl.innerHTML.includes('<span class="sotv-li">• 第一步</span>'));
assert.ok(listEl.innerHTML.includes('<span class="sotv-li">• 第二步</span>'));

// 4. Plain text stripping for TTS and table truncation
assert.equal(
  mockWindow.SOTV_PLAIN('**重点**：求 $x^2$ 的==最小值==\n- 提示 `ok`'),
  '重点：求 x^2 的最小值\n提示 ok',
);

console.log('rich-text.test.ts passed');

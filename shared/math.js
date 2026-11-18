(function () {
  'use strict';
  var queue = [], loading = false;
  function render(element) {
    if (!element || !document.documentElement.contains(element)) return;
    window.renderMathInElement(element, {
      delimiters: [
        { left: '$$', right: '$$', display: true },
        { left: '\\[', right: '\\]', display: true },
        { left: '\\(', right: '\\)', display: false },
        { left: '$', right: '$', display: false }
      ],
      throwOnError: false, trust: false, strict: 'ignore', maxExpand: 200,
      ignoredTags: ['script', 'noscript', 'style', 'textarea', 'pre', 'code', 'option', 'input']
    });
  }
  function script(src, done) {
    var element = document.createElement('script');
    element.src = src; element.onload = done;
    element.onerror = function () { loading = false; };
    document.head.appendChild(element);
  }
  function escapeHtml(text) {
    return String(text == null ? '' : text)
      .replace(/&/g, '&amp;')
      .replace(/</g, '&lt;')
      .replace(/>/g, '&gt;')
      .replace(/"/g, '&quot;')
      .replace(/'/g, '&#39;');
  }
  function richToHtml(raw) {
    var text = String(raw == null ? '' : raw);
    if (!text) return '';
    var tokens = [];
    // Protect math spans and inline code before formatting so * / _ inside LaTeX or code stay intact.
    var protectedText = text.replace(/\$\$[\s\S]*?\$\$|\\\[[\s\S]*?\\\]|\\\([\s\S]*?\\\)|\$(?:\\.|[^$\n])+\$|`[^`\n]+`/g, function (match) {
      var idx = tokens.length;
      if (match.charAt(0) === '`') {
        tokens.push('<code class="sotv-code">' + escapeHtml(match.slice(1, -1)) + '</code>');
      } else {
        tokens.push(escapeHtml(match));
      }
      return '\u0000SOTV' + idx + '\u0000';
    });
    var html = escapeHtml(protectedText);
    // Bullet lines: "- item" or "* item" at start of a line
    html = html.replace(/(^|\n)[-*]\s+([^\n]+)/g, '$1<span class="sotv-li">• $2</span>');
    // Rich text inline styles
    html = html.replace(/\*\*([^\n*](?:[\s\S]*?[^\n*])?)\*\*/g, '<strong>$1</strong>');
    html = html.replace(/__([^\n_](?:[\s\S]*?[^\n_])?)__/g, '<u>$1</u>');
    html = html.replace(/==([^\n=](?:[\s\S]*?[^\n=])?)==/g, '<mark class="sotv-mark">$1</mark>');
    html = html.replace(/(^|[^\*])\*([^\n*](?:[^\n]*?[^\n*])?)\*(?!\*)/g, '$1<em>$2</em>');
    html = html.replace(/\u0000SOTV(\d+)\u0000/g, function (_, idx) {
      return tokens[Number(idx)] || '';
    });
    return html;
  }
  function toPlain(raw) {
    return String(raw == null ? '' : raw)
      .replace(/!\[[^\]]*\]\([^)]*\)/g, '')
      .replace(/(^|\n)[-*]\s+/g, '$1')
      .replace(/\*\*([\s\S]*?)\*\*/g, '$1')
      .replace(/__([\s\S]*?)__/g, '$1')
      .replace(/==([\s\S]*?)==/g, '$1')
      .replace(/`([^`\n]+)`/g, '$1')
      .replace(/(^|[^\*])\*([^\n*]+)\*(?!\*)/g, '$1$2')
      .replace(/\$\$?/g, '')
      .replace(/\\\[|\\\]|\\\(|\\\)/g, '')
      .trim();
  }
  window.SOTV_MATH = function (element) {
    if (!element) return;
    if (window.renderMathInElement) { render(element); return; }
    if (!/\$|\\[\[(]/.test(element.textContent || '')) return;
    if (queue.indexOf(element) < 0) queue.push(element);
    if (loading) return;
    loading = true;
    var style = document.createElement('link'); style.rel = 'stylesheet'; style.href = 'assets/vendor/katex/katex.min.css'; document.head.appendChild(style);
    script('assets/vendor/katex/katex.min.js', function () {
      script('assets/vendor/katex/contrib/auto-render.min.js', function () {
        loading = false;
        var pending = queue.slice(); queue = [];
        pending.forEach(render);
      });
    });
  };
  window.SOTV_RICH = function (element, rawText) {
    if (!element) return;
    element.innerHTML = richToHtml(rawText);
    window.SOTV_MATH(element);
  };
  window.SOTV_PLAIN = toPlain;
})();

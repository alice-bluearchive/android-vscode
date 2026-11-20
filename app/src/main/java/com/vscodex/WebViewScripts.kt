package com.vscodex

/**
 * 注入到 code-server 页面里的脚本。
 *
 * 背景：VSCode Web 的侧边栏宽度由一条很窄的 sash（分隔条）控制，它依赖 PointerEvent。
 * 在手机 WebView 上手指很难命中，而且浏览器默认的 touch-action 会把横向滑动
 * 当成页面手势吃掉，导致拖不动。
 *
 * 这里做三件事：
 *  1. 给 sash 设置 touch-action: none，让 Chromium 把触摸转成 PointerEvent 交给 VSCode；
 *  2. 用伪元素把 sash 的触摸命中区向两侧扩展，但不改变 sash 自身的几何中心
 *     （直接改 width/right 会让 VSCode 计算宽度时基准点偏移）；
 *  3. 保证 Activity Bar / 侧边栏可见，并用 MutationObserver 持续兜底。
 */
object WebViewScripts {

    val SIDEBAR_TOUCH: String = """
(function () {
  var RETRY_MS = 400;
  var MAX_RETRY = 60;

  function apply(attempt) {
    var wb = document.querySelector('.monaco-workbench');
    if (!wb) {
      if (attempt < MAX_RETRY) setTimeout(function () { apply(attempt + 1); }, RETRY_MS);
      return;
    }

    // 1) 关闭浏览器手势劫持；用伪元素扩展 sash 命中区，sash 本体几何不变
    var style = document.getElementById('vscodex-touch-style');
    if (!style) {
      style = document.createElement('style');
      style.id = 'vscodex-touch-style';
      style.textContent = [
        '.monaco-workbench .sash { touch-action: none !important; }',
        '.monaco-workbench .sash.vertical::after {',
        '  content: "";',
        '  position: absolute;',
        '  top: 0; bottom: 0;',
        '  left: -11px; right: -11px;',
        '  z-index: 1;',
        '}',
        '.monaco-workbench .part.sidebar { min-width: 0 !important; }',
        '.monaco-workbench .part.activitybar { display: flex !important; }'
      ].join('\n');
      (document.head || wb).appendChild(style);
    }

    // 2) 兜底：给每个 sash 补上 inline touch-action，并监听后续新增的 sash
    function patchSashes() {
      var list = document.querySelectorAll('.monaco-workbench .sash');
      for (var i = 0; i < list.length; i++) {
        var s = list[i];
        if (s.__vscodexTouch) continue;
        s.__vscodexTouch = '1';
        s.style.touchAction = 'none';
      }
    }
    patchSashes();
    try {
      var mo = new MutationObserver(patchSashes);
      mo.observe(wb, { childList: true, subtree: true });
    } catch (e) {}
  }

  if (document.readyState === 'loading') {
    window.addEventListener('DOMContentLoaded', function () { apply(0); });
  } else {
    apply(0);
  }
})();
""".trimIndent()
}

// 绮梦影库桌面壳 · 主窗口无边框注入（B 站客户端同款形态）
//
// 作用域：仅主窗口（open_main_window 的 initialization_script）；设置窗保持系统边框。
// 原则：web/ 零改动 —— 顶栏与 .win-btn 三按钮是 Web UI 既有装饰件（浏览器环境无行为，
// 见 web/src/components/shell/TopBar.tsx），本脚本在运行时给它们接上真实窗口行为：
//   ① 顶栏三按钮 → 最小化/最大化/关闭（以中文 title 为稳定标识）
//   ② 顶栏空白处按住拖动 = 移动窗口，双击 = 最大化/还原
// 权限依赖（capabilities/default.json）：allow-minimize / allow-toggle-maximize /
// allow-close / allow-start-dragging。
// 参考：https://v2.tauri.app/learn/window-customization/（手动 startDragging 实现）
(function () {
  'use strict';
  // SPA 路由切换不重载页面、本脚本不会重跑；整页刷新后监听器随页面销毁、重新注入
  if (window.__QIMENG_TITLEBAR__) return;
  window.__QIMENG_TITLEBAR__ = true;

  // 惰性解析窗口句柄：初始化脚本可能先于 Tauri 内部 __TAURI__ 注入执行
  // （此前脚本顶部直接读 __TAURI__，取不到就整体退出 → 三按钮永远无反应）。
  // 点击/拖拽必然发生在页面加载完成之后，届时再取即可。
  function getAppWindow() {
    var t = window.__TAURI__ && window.__TAURI__.window;
    return t && t.getCurrentWindow ? t.getCurrentWindow() : null;
  }

  // —— ① 顶栏三按钮：装饰件转真按钮 ——
  // 用捕获阶段监听，冒泡前就接管；目标按钮本身无 React 行为，无事件冲突
  document.addEventListener(
    'click',
    function (e) {
      var btn = e.target && e.target.closest && e.target.closest('button.win-btn');
      if (!btn) return;
      var aw = getAppWindow();
      if (!aw) return;
      var t = btn.getAttribute('title');
      if (t === '最小化') aw.minimize();
      else if (t === '最大化') aw.toggleMaximize();
      else if (t === '关闭') aw.close();
    },
    true
  );

  // —— ② 顶栏拖动 / 双击最大化（官方文档的手动实现形态）——
  // 命中规则：按下点在 <header> 内，且不是可交互元素 —— 交互元素照常点击，空白处即拖动
  var INTERACTIVE_SELECTOR =
    'button,a,input,textarea,select,label,[role="button"],[contenteditable="true"]';
  document.addEventListener(
    'mousedown',
    function (e) {
      if (e.buttons !== 1 || document.fullscreenElement) return; // 全屏看视频时不接管
      var target = e.target;
      if (!target || !target.closest) return;
      if (!target.closest('header')) return;
      if (target.closest(INTERACTIVE_SELECTOR)) return;
      var aw = getAppWindow();
      if (!aw) return;
      e.preventDefault(); // 阻止拖动时选中文字
      if (e.detail === 2) aw.toggleMaximize();
      else aw.startDragging();
    },
    true
  );
})();

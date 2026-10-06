// 绮梦影库桌面壳 · 主窗口无边框注入（B 站客户端同款形态）
//
// 作用域：仅主窗口（open_main_window 的 initialization_script）；设置窗保持系统边框。
// 原则：web/ 零改动 —— 顶栏与 .win-btn 三按钮是 Web UI 既有装饰件（浏览器环境无行为，
// 见 web/src/components/shell/TopBar.tsx），本脚本在运行时给它们接上真实窗口行为：
//   ① 顶栏三按钮 → 最小化/最大化/关闭（以中文 title 为稳定标识）
//   ② 顶栏空白处按住拖动 = 移动窗口，严格判定双击 = 最大化/还原
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

  // —— 双击最大化状态机与防穿透冷却 ——
  // 杜绝依赖浏览器全局 e.detail（连续点击计数在元素销毁/穿透后会误带到底层 header）：
  // 必须首击与次击均落在合法 header 空白拖拽区、且时间在 350ms 内、位移 <= 5px 才判定为双击。
  var DOUBLE_CLICK_TIME_MS = 350;
  var DOUBLE_CLICK_DIST_PX = 5;
  var COOLDOWN_MS = 400;
  var blockMaximizeUntil = 0;
  var lastHeaderDown = null; // { time: number, x: number, y: number }

  function resetDoubleTap(cooldownMs) {
    lastHeaderDown = null;
    if (cooldownMs) {
      blockMaximizeUntil = Math.max(blockMaximizeUntil, Date.now() + cooldownMs);
    }
  }

  // 暴露防穿透/防误触接口供浮层/查看器卸载时主动调用
  window.__QIMENG_BLOCK_WINDOW_MAXIMIZE__ = function (ms) {
    resetDoubleTap(ms || COOLDOWN_MS);
  };

  // —— ① 顶栏三按钮：装饰件转真按钮 ——
  // 用捕获阶段监听，冒泡前接管；阻断冒泡并重置双击状态，加设冷却杜绝二次误触穿透
  document.addEventListener(
    'click',
    function (e) {
      var btn = e.target && e.target.closest && e.target.closest('button.win-btn');
      if (!btn) return;
      e.preventDefault();
      e.stopPropagation();
      e.stopImmediatePropagation();
      resetDoubleTap(COOLDOWN_MS);
      var aw = getAppWindow();
      if (!aw) return;
      var t = btn.getAttribute('title');
      if (t === '最小化') aw.minimize();
      else if (t === '最大化') aw.toggleMaximize();
      else if (t === '关闭') aw.close();
    },
    true
  );

  // —— ② 顶栏拖动 / 双击最大化（严格双击拖动区判定）——
  // 命中规则：按下点在 <header> 内，且不是可交互元素 —— 交互元素照常点击，空白处即拖动
  var INTERACTIVE_SELECTOR =
    'button,a,input,textarea,select,label,[role="button"],[contenteditable="true"]';

  document.addEventListener(
    'mousedown',
    function (e) {
      if (e.buttons !== 1 || document.fullscreenElement) {
        resetDoubleTap();
        return;
      }
      var target = e.target;
      if (!target || !target.closest) {
        resetDoubleTap();
        return;
      }

      // 1. 点击落在 header 外部（如全屏查看器、弹窗、内容卡片等）
      var header = target.closest('header');
      if (!header) {
        if (target.closest('.img-viewer__close') || target.closest('[data-no-maximize]')) {
          resetDoubleTap(COOLDOWN_MS);
        } else {
          resetDoubleTap();
        }
        return;
      }

      // 2. 点击落在 header 内的交互元素（按钮、搜索框、Tab 等）
      if (target.closest(INTERACTIVE_SELECTOR)) {
        if (target.closest('button.win-btn')) {
          resetDoubleTap(COOLDOWN_MS);
        } else {
          resetDoubleTap();
        }
        return;
      }

      // 3. 确认为 header 空白拖拽区
      var aw = getAppWindow();
      if (!aw) {
        resetDoubleTap();
        return;
      }

      e.preventDefault(); // 阻止拖动时选中文本

      var now = Date.now();
      var isDoubleClick = false;

      if (now >= blockMaximizeUntil && lastHeaderDown) {
        var dt = now - lastHeaderDown.time;
        var dx = Math.abs(e.clientX - lastHeaderDown.x);
        var dy = Math.abs(e.clientY - lastHeaderDown.y);
        if (dt >= 40 && dt <= DOUBLE_CLICK_TIME_MS && Math.hypot(dx, dy) <= DOUBLE_CLICK_DIST_PX) {
          isDoubleClick = true;
        }
      }

      if (isDoubleClick) {
        resetDoubleTap(COOLDOWN_MS);
        aw.toggleMaximize();
      } else {
        lastHeaderDown = { time: now, x: e.clientX, y: e.clientY };
        aw.startDragging();
      }
    },
    true
  );

  // 移动超过阈值视为拖动，取消双击判定候选
  document.addEventListener(
    'mousemove',
    function (e) {
      if (lastHeaderDown && e.buttons === 1) {
        var dx = Math.abs(e.clientX - lastHeaderDown.x);
        var dy = Math.abs(e.clientY - lastHeaderDown.y);
        if (Math.hypot(dx, dy) > DOUBLE_CLICK_DIST_PX) {
          lastHeaderDown = null;
        }
      }
    },
    true
  );

  // 鼠标抬起超过 300ms 视为长按拖动结束，重置首击候选
  document.addEventListener(
    'mouseup',
    function () {
      if (lastHeaderDown && Date.now() - lastHeaderDown.time > 300) {
        lastHeaderDown = null;
      }
    },
    true
  );
})();

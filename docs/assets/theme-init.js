/* 首屏前定主题与动效偏好,避免闪白/闪黑与动画跳变。外置以便 CSP 使用 script-src 'self'。
   动效优先级:用户显式选择(localStorage) > 系统 prefers-reduced-motion。 */
(function () {
  var el = document.documentElement;
  try {
    var t = localStorage.getItem("xincode-theme");
    if (t === "dark" || t === "light") el.setAttribute("data-theme", t);
    var m = localStorage.getItem("xincode-motion");
    var reduce = window.matchMedia && window.matchMedia("(prefers-reduced-motion: reduce)").matches;
    var on = m === "on" ? true : (m === "off" ? false : !reduce);
    el.setAttribute("data-motion", on ? "on" : "off");
  } catch (e) {
    el.setAttribute("data-motion", "on");
  }
})();

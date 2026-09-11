/* 首屏前定主题与动效偏好,避免闪白/闪黑与动画跳变。外置以便 CSP 使用 script-src 'self'。
   动效默认【开】:用户显式关闭过(localStorage=xincode-motion=off)才关;
   关掉后仍可用顶栏开关打开,选择会记住。 */
(function () {
  var el = document.documentElement;
  try {
    var t = localStorage.getItem("xincode-theme");
    if (t === "dark" || t === "light") el.setAttribute("data-theme", t);
    var m = localStorage.getItem("xincode-motion");
    el.setAttribute("data-motion", m === "off" ? "off" : "on");
  } catch (e) {
    el.setAttribute("data-motion", "on");
  }
})();

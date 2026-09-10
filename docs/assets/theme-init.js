/* 首屏前定主题,避免闪白/闪黑。外置以便 CSP 使用 script-src 'self'。 */
(function () {
  try {
    var t = localStorage.getItem("xincode-theme");
    if (t === "dark" || t === "light") document.documentElement.setAttribute("data-theme", t);
  } catch (e) {}
})();

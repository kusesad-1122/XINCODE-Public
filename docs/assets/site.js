/* XINCODE 站点脚本 · 无依赖、渐进增强
   所有能力都建立在已有 HTML 之上:JS 失败时页面内容、导航与下载链接依然可用。 */
(function () {
  "use strict";

  var $ = function (sel, scope) { return (scope || document).querySelector(sel); };
  var $$ = function (sel, scope) { return Array.prototype.slice.call((scope || document).querySelectorAll(sel)); };
  var reduceMotion = window.matchMedia && window.matchMedia("(prefers-reduced-motion: reduce)").matches;
  window.__xin = window.__xin || {};
  // 动效是否开启:由首屏脚本写入的 data-motion 决定(默认取系统偏好,用户可覆盖)
  function motionEnabled() { return document.documentElement.getAttribute("data-motion") !== "off"; }
  window.__xin.motionEnabled = motionEnabled;

  /* ---------- 1. 主题切换(亮/暗) ---------- */
  (function () {
    var root = document.documentElement;
    function current() {
      var set = root.getAttribute("data-theme");
      if (set === "dark" || set === "light") return set;
      return window.matchMedia && window.matchMedia("(prefers-color-scheme: dark)").matches ? "dark" : "light";
    }
    function sync() {
      var isDark = current() === "dark";
      $$("[data-theme-toggle]").forEach(function (btn) {
        btn.setAttribute("aria-pressed", String(isDark));
        btn.setAttribute("aria-label", isDark ? "切换到浅色主题" : "切换到深色主题");
        var t = $("[data-theme-label]", btn);
        if (t) t.textContent = isDark ? "浅色" : "深色";
      });
    }
    $$("[data-theme-toggle]").forEach(function (btn) {
      btn.addEventListener("click", function () {
        var next = current() === "dark" ? "light" : "dark";
        root.setAttribute("data-theme", next);
        try { localStorage.setItem("xincode-theme", next); } catch (e) {}
        sync();
      });
    });
    sync();
  })();

  /* ---------- 2. 移动端菜单(可键盘操作、Esc 关闭、焦点还回) ---------- */
  (function () {
    var btn = $("[data-menu-toggle]");
    var menu = $("#site-menu");
    if (!btn || !menu) return;
    function setOpen(open) {
      menu.hidden = !open;
      btn.setAttribute("aria-expanded", String(open));
      btn.setAttribute("aria-label", open ? "关闭导航菜单" : "打开导航菜单");
    }
    btn.addEventListener("click", function () { setOpen(menu.hidden); });
    menu.addEventListener("click", function (e) { if (e.target.closest("a")) setOpen(false); });
    document.addEventListener("keydown", function (e) {
      if (e.key === "Escape" && !menu.hidden) { setOpen(false); btn.focus(); }
    });
    document.addEventListener("click", function (e) {
      if (menu.hidden) return;
      if (!menu.contains(e.target) && !btn.contains(e.target)) setOpen(false);
    });
    setOpen(false);
  })();

  /* ---------- 3. 插件目录:分类筛选 + 关键词搜索 + 云端增量 ---------- */
  (function () {
    var stage = $("[data-plugin-root]");
    if (!stage) return;
    var search = $("[data-plugin-search]");
    var chips = $$("[data-filter]", stage);
    var status = $("[data-plugin-status]");
    var empty = $("[data-plugin-empty]");
    var cards = $$("[data-plugin-card]", stage);

    function text(el) { return (el.getAttribute("data-keywords") || "") + " " + el.textContent; }
    function apply() {
      var q = (search && search.value || "").trim().toLowerCase();
      var cat = (chips.filter(function (c) { return c.getAttribute("aria-pressed") === "true"; })[0] || {}).getAttribute
        ? chips.filter(function (c) { return c.getAttribute("aria-pressed") === "true"; })[0].getAttribute("data-filter") : "all";
      var shown = 0;
      cards.forEach(function (card) {
        var okCat = cat === "all" || card.getAttribute("data-category") === cat;
        var okQ = !q || text(card).toLowerCase().indexOf(q) !== -1;
        var show = okCat && okQ;
        card.hidden = !show;
        if (show) shown++;
      });
      if (empty) empty.hidden = shown !== 0;
      if (status) {
        status.textContent = shown === cards.length
          ? "共 " + cards.length + " 个在线插件,全部显示。"
          : "筛选出 " + shown + " / " + cards.length + " 个在线插件。";
      }
    }
    chips.forEach(function (chip) {
      chip.addEventListener("click", function () {
        chips.forEach(function (c) { c.setAttribute("aria-pressed", String(c === chip)); });
        apply();
      });
    });
    if (search) {
      var timer;
      search.addEventListener("input", function () {
        window.clearTimeout(timer);
        timer = window.setTimeout(apply, 120);
      });
      search.addEventListener("keydown", function (e) { if (e.key === "Escape") { search.value = ""; apply(); } });
    }
    apply();

    // 云端目录增量:docs/plugins/registry.json 出现新插件时补进列表(同源,失败静默)
    fetch("plugins/registry.json", { cache: "no-cache" })
      .then(function (r) { return r.ok ? r.json() : null; })
      .then(function (data) {
        if (!data || !Array.isArray(data.plugins)) return;
        var have = {};
        cards.forEach(function (c) { have[c.getAttribute("data-plugin-id")] = true; });
        var grid = $("[data-plugin-grid]", stage);
        if (!grid) return;
        var added = 0;
        data.plugins.forEach(function (p) {
          if (!p || !p.id || have[p.id]) return;
          var art = document.createElement("article");
          art.className = "plugin";
          art.setAttribute("data-plugin-card", "");
          art.setAttribute("data-plugin-id", p.id);
          art.setAttribute("data-category", p.category || "其他");
          var h3 = document.createElement("h3");
          h3.textContent = p.name || p.id;
          var desc = document.createElement("p");
          desc.textContent = p.description || "";
          var row = document.createElement("div");
          row.className = "badge-row";
          var b1 = document.createElement("span");
          b1.className = "badge badge-brand";
          b1.textContent = p.category || "其他";
          var b2 = document.createElement("span");
          b2.className = "badge";
          b2.textContent = p.auth_type === "api_key" ? "需 Key" : "免密钥";
          row.appendChild(b1); row.appendChild(b2);
          art.appendChild(h3); art.appendChild(desc); art.appendChild(row);
          grid.appendChild(art);
          cards.push(art);
          added++;
        });
        if (added) apply();
      })
      .catch(function () {});
  })();

  /* ---------- 4. GitHub Release 实时数据(失败保留静态兜底) ---------- */
  (function () {
    var API = "https://api.github.com/repos/kusesad-1122/XINCODE-Public";
    var hooks = {
      version: $$("[data-version]"),
      note: $$("[data-version-note]"),
      size: $$("[data-apk-size]"),
      date: $$("[data-apk-date]"),
      list: $$("[data-release-list]")
    };
    var wanted = hooks.version.length || hooks.note.length || hooks.size.length || hooks.date.length || hooks.list.length;
    if (!wanted) return;

    function setAll(nodes, value) { nodes.forEach(function (n) { n.textContent = value; }); }
    function fmtSize(bytes) { return bytes > 0 ? "约 " + (bytes / 1048576).toFixed(1) + " MB" : ""; }
    function day(iso) { return (iso || "").slice(0, 10); }

    // Release 说明(markdown 子集)渲染成安全 DOM:标题/列表/粗体/段落
    function renderBody(body, mount, cap) {
      var lines = String(body || "").split(/\r?\n/);
      var seenHeading = false, ul = null;
      var maxHeadings = cap.headings, maxItems = cap.items, headings = 0, items = 0;
      lines.forEach(function (raw) {
        var t = raw.trim();
        if (!t || /^-{3,}$/.test(t) || t.charAt(0) === ">") return;
        if (t.indexOf("## ") === 0 || t.indexOf("### ") === 0) {
          if (headings >= maxHeadings) { ul = null; return; }
          var h = document.createElement("h3");
          h.textContent = t.replace(/^#+\s*/, "").replace(/\*\*/g, "");
          mount.appendChild(h);
          seenHeading = true; ul = null; headings++;
          return;
        }
        if (t.indexOf("- ") === 0) {
          if (items >= maxItems) return;
          if (!ul) { ul = document.createElement("ul"); mount.appendChild(ul); }
          items++;
          var li = document.createElement("li");
          var parts = t.slice(2).split(/\*\*(.+?)\*\*/g);
          parts.forEach(function (part, i) {
            if (!part) return;
            if (i % 2 === 1) { var b = document.createElement("strong"); b.textContent = part; li.appendChild(b); }
            else { li.appendChild(document.createTextNode(part)); }
          });
          ul.appendChild(li);
          return;
        }
        if (!seenHeading && /^XINCODE/.test(t)) { seenHeading = true; return; }
        seenHeading = true; ul = null;
        var p = document.createElement("p");
        p.textContent = t.replace(/\*\*/g, "");
        mount.appendChild(p);
      });
    }

    fetch(API + "/releases?per_page=8", { headers: { Accept: "application/vnd.github+json" } })
      .then(function (r) { return r.ok ? r.json() : null; })
      .then(function (list) {
        if (!list || !list.length) return;
        var latest = list[0];
        setAll(hooks.version, latest.tag_name);
        setAll(hooks.note, "发布于 " + day(latest.published_at) + " · 签名版 APK 随 Release 发布");
        setAll(hooks.date, day(latest.published_at));
        var asset = (latest.assets || []).filter(function (a) { return a.name === "app-release.apk"; })[0];
        if (asset) setAll(hooks.size, fmtSize(asset.size));
        hooks.list.forEach(function (mount) {
          var limit = parseInt(mount.getAttribute("data-release-list"), 10) || 6;
          var cap = {
            headings: parseInt(mount.getAttribute("data-cap-headings"), 10) || 3,
            items: parseInt(mount.getAttribute("data-cap-items"), 10) || 4
          };
          mount.textContent = "";
          list.slice(0, limit).forEach(function (rel, i) {
            var box = document.createElement("article");
            box.className = "release";
            var head = document.createElement("div");
            head.className = "v";
            var tag = document.createElement("b");
            tag.textContent = rel.tag_name;
            var tm = document.createElement("time");
            tm.dateTime = day(rel.published_at);
            tm.textContent = day(rel.published_at);
            head.appendChild(tag); head.appendChild(tm);
            if (i === 0) {
              var badge = document.createElement("span");
              badge.className = "badge badge-green";
              badge.textContent = "最新";
              head.appendChild(badge);
            }
            box.appendChild(head);
            var body = document.createElement("div");
            renderBody(rel.body, body, cap);
            if (!body.childNodes.length) {
              var skip = document.createElement("p");
              skip.className = "meta";
              skip.textContent = "该版本未附更新说明。";
              body.appendChild(skip);
            }
            box.appendChild(body);
            mount.appendChild(box);
          });
        });
      })
      .catch(function () { /* 保留静态兜底 */ });
  })();

  /* ---------- 5. 目录 / 章节轨道高亮(随滚动实时计算) ---------- */
  (function () {
    var groups = $$("[data-toc], [data-rail]").map(function (toc) {
      var items = $$("a[href^='#']", toc).map(function (a) {
        return { link: a, target: document.getElementById(a.getAttribute("href").slice(1)) };
      }).filter(function (it) { return !!it.target; });
      return { links: items.map(function (it) { return it.link; }), items: items };
    }).filter(function (g) { return g.items.length; });
    if (!groups.length) return;

    function update() {
      var line = 148; // 阅读线:顶部导航之下
      groups.forEach(function (g) {
        var active = null, best = -Infinity, fallback = null;
        g.items.forEach(function (it) {
          var top = it.target.getBoundingClientRect().top;
          if (top <= line && top > best) { best = top; active = it.link; }
          if (top <= line + 40) fallback = it.link;
        });
        if (!active) {
          // 还没滚到第一节时,高亮第一节
          active = fallback || g.items[0].link;
        }
        g.links.forEach(function (l) { l.removeAttribute("aria-current"); });
        if (active) active.setAttribute("aria-current", "true");
      });
    }

    var ticking = false;
    function onScroll() {
      if (ticking) return;
      ticking = true;
      window.requestAnimationFrame(function () { ticking = false; update(); });
    }
    window.addEventListener("scroll", onScroll, { passive: true });
    window.addEventListener("resize", onScroll, { passive: true });
    window.addEventListener("hashchange", onScroll);
    update();
  })();

  /* ---------- 6. 滚动浮现:可重复播放,方向随进入方向镜像 ----------
     向下翻:元素从下沿升起;往回退:元素从上沿落下(位移取镜像)。
     离开视口即复位,所以同一元素每次进入都会重新播一遍。 */
  (function () {
    function initReveal() {
    var els = $$("[data-reveal], .card, .plugin, .release, .section-head, .download-card, .panel, .room, .page-head, .prose > section, .notfound");
    if (window.__xin.revealIO) { window.__xin.revealIO.disconnect(); window.__xin.revealIO = null; }
    if (!els.length || !("IntersectionObserver" in window)) return;
    if (!motionEnabled()) {
      // 动效关闭:元素保持可见(不加 .reveal),不注册观察器
      els.forEach(function (el) { el.classList.remove("reveal", "reveal-prep", "is-in", "rv-from-top"); });
      return;
    }

    // 嵌套目标会让几何互相干扰(父元素位移改变子元素 rect,子元素就会反复进出观察带),
    // 只保留最外层的那一个。
    els = els.filter(function (el) {
      return !els.some(function (other) { return other !== el && other.contains(el); });
    });

    function setFrom(el, fromTop) {
      if (fromTop) el.classList.add("rv-from-top");
      else el.classList.remove("rv-from-top");
    }

    // 瞬时显示(不播动画),用于首屏已可见的元素,避免"先显示再隐藏"的闪动
    function showInstant(el, fromTop) {
      setFrom(el, fromTop);
      el.classList.add("reveal-prep");
      el.classList.add("reveal", "is-in");
      void el.offsetHeight;
      el.classList.remove("reveal-prep");
    }

    function show(el, fromTop) {
      if (el.classList.contains("is-in")) return;
      setFrom(el, fromTop);
      el.classList.add("reveal", "is-in");
    }

    // 首屏:可见的直接显示;其余瞬时藏好,等滚到再播
    var vh = window.innerHeight || 800;
    var toPrepare = [];
    els.forEach(function (el) {
      var rect = el.getBoundingClientRect();
      if (rect.top < vh * 0.92 && rect.bottom > 0) {
        showInstant(el, false);
      } else {
        el.classList.add("reveal", "reveal-prep");
        setFrom(el, rect.bottom <= 0);
        toPrepare.push(el);
      }
    });
    if (toPrepare.length) {
      void document.body.offsetHeight;
      toPrepare.forEach(function (el) { el.classList.remove("reveal-prep"); });
    }

    var io = new IntersectionObserver(function (entries) {
      var toReset = [];
      entries.forEach(function (entry) {
        var el = entry.target;
        if (entry.isIntersecting) {
          // 元素顶边已在视口上方 → 说明是从上沿(往回退)进入的,方向取镜像
          show(el, entry.boundingClientRect.top < 0);
        } else if (el.classList.contains("is-in")) {
          // 只有"完全离开视口"才复位:观察带底部留了 8%,若在此直接复位,
          // 元素会在屏幕底部还剩一截时就瞬间消失(往回退时能看见闪一下)。
          var rect = el.getBoundingClientRect();
          var fullyOut = rect.bottom <= 0 || rect.top >= (window.innerHeight || 800);
          if (fullyOut) toReset.push(el);
        }
      });
      if (toReset.length) {
        // 复位:瞬时回到隐藏态(元素此时在视口外,用户看不到这一步)
        toReset.forEach(function (el) {
          el.classList.add("reveal-prep");
          el.classList.remove("is-in");
          setFrom(el, false);
        });
        void document.body.offsetHeight;
        toReset.forEach(function (el) { el.classList.remove("reveal-prep"); });
      }
    // rootMargin 上方多留 32px(> 位移量 24px):隐藏态带 transform,元素贴在上沿时
    // 若观察带刚好卡在 0,会出现"显示 → 复位 → 显示"的抖动;扩一点即可稳定。
    }, { threshold: 0, rootMargin: "32px 0px -8% 0px" });

    els.forEach(function (el) { io.observe(el); });
    window.__xin.revealIO = io;
    }
    window.__xin.initReveal = initReveal;
    initReveal();
  })();

  /* ---------- 7. 阅读进度条 ---------- */
  (function () {
    var bar = $("[data-progress]");
    if (!bar) return;
    var ticking = false;
    function update() {
      ticking = false;
      var doc = document.documentElement;
      var max = doc.scrollHeight - window.innerHeight;
      var top = window.pageYOffset || doc.scrollTop || 0;
      var pct = max > 0 ? Math.min(100, Math.max(0, (top / max) * 100)) : 0;
      bar.style.width = pct.toFixed(2) + "%";
    }
    function onScroll() { if (!ticking) { ticking = true; window.requestAnimationFrame(update); } }
    window.addEventListener("scroll", onScroll, { passive: true });
    window.addEventListener("resize", onScroll, { passive: true });
    update();
  })();

  /* ---------- 8. 指标数字滚动(减弱动效/无脚本时保留最终值) ---------- */
  (function () {
    var nums = $$("[data-count-to]");
    if (!nums.length) return;
    function targetOf(el) { var v = parseFloat(el.getAttribute("data-count-to")); return isNaN(v) ? null : v; }
    function render(el, value) {
      var t = targetOf(el);
      el.textContent = t !== null && t % 1 === 0 ? String(Math.round(value)) : value.toFixed(1);
    }
    nums.forEach(function (el) { var t = targetOf(el); if (t !== null) render(el, t); });
    if (!("IntersectionObserver" in window) || !motionEnabled()) return;
    var io = new IntersectionObserver(function (entries) {
      entries.forEach(function (entry) {
        var el = entry.target;
        if (!entry.isIntersecting) return;
        if (el.dataset.counting === "1") return;   // 正在滚动计数就不打断
        var target = targetOf(el);
        if (target === null) return;
        el.dataset.counting = "1";
        var started = null;
        function step(now) {
          if (started === null) started = now;
          var p = Math.min(1, (now - started) / 900);
          render(el, target * (1 - Math.pow(1 - p, 3)));
          if (p < 1) window.requestAnimationFrame(step);
          else el.dataset.counting = "0";
        }
        render(el, 0);
        window.requestAnimationFrame(step);
      });
    }, { threshold: 0.4 });
    nums.forEach(function (el) { io.observe(el); });
  })();

  /* ---------- 9. 代码块复制 ---------- */
  (function () {
    var pres = $$(".prose pre");
    if (!pres.length || !navigator.clipboard) return;
    pres.forEach(function (pre) {
      pre.style.position = "relative";
      var btn = document.createElement("button");
      btn.type = "button";
      btn.className = "btn btn-sm btn-ghost";
      btn.textContent = "复制";
      btn.style.cssText = "position:absolute;top:8px;right:8px;min-height:32px;padding:0 12px;font-size:12.5px";
      btn.addEventListener("click", function () {
        navigator.clipboard.writeText(pre.innerText).then(function () {
          btn.textContent = "已复制";
          window.setTimeout(function () { btn.textContent = "复制"; }, 1800);
        }).catch(function () { btn.textContent = "复制失败"; });
      });
      pre.appendChild(btn);
    });
  })();


  /* ---------- 10.5 动效开关(系统偏好只是默认值,用户可覆盖) ---------- */
  (function () {
    var root = document.documentElement;
    var btns = $$("[data-motion-toggle]");
    if (!btns.length) return;

    function sync() {
      var on = motionEnabled();
      btns.forEach(function (btn) {
        btn.setAttribute("aria-pressed", String(on));
        btn.setAttribute("aria-label", on ? "关闭动效" : "开启动效");
      });
    }

    function reinit() {
      // 清掉既有浮现状态,再按新设置重新初始化一次
      $$("[data-reveal], .card, .plugin, .release, .section-head, .download-card, .panel, .room, .page-head, .prose > section, .notfound")
        .forEach(function (el) { el.classList.remove("reveal", "reveal-prep", "is-in", "rv-from-top"); });
      if (window.__xin && window.__xin.initReveal) window.__xin.initReveal();
    }

    btns.forEach(function (btn) {
      btn.addEventListener("click", function () {
        var next = motionEnabled() ? "off" : "on";
        try { localStorage.setItem("xincode-motion", next); } catch (e) {}
        root.setAttribute("data-motion", next);
        sync();
        reinit();
      });
    });
    sync();
  })();

  /* ---------- 10. 页脚年份 ---------- */
  $$("[data-year]").forEach(function (el) { el.textContent = String(new Date().getFullYear()); });

  /* ---------- 11. 防框架嵌套(clickjacking) ----------
     GitHub Pages 无法下发 X-Frame-Options / frame-ancestors 响应头,
     所以用脚本兜一层:被别的站点用 iframe 套壳时,把顶层窗口拽回真实地址。 */
  (function () {
    try {
      if (window.top !== window.self) window.top.location = window.self.location.href;
    } catch (e) { /* 跨域受限时无法改写,交由浏览器与 CSP 兜底 */ }
  })();
})();

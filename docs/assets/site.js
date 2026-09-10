/* XINCODE 站点脚本 · 无依赖、渐进增强
   所有能力都建立在已有 HTML 之上:JS 失败时页面内容、导航与下载链接依然可用。 */
(function () {
  "use strict";

  var $ = function (sel, scope) { return (scope || document).querySelector(sel); };
  var $$ = function (sel, scope) { return Array.prototype.slice.call((scope || document).querySelectorAll(sel)); };
  var reduceMotion = window.matchMedia && window.matchMedia("(prefers-reduced-motion: reduce)").matches;

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

  /* ---------- 5. 目录高亮(滚动位置 → aria-current) ---------- */
  (function () {
    var toc = $("[data-toc]");
    if (!toc || !("IntersectionObserver" in window)) return;
    var links = $$("a[href^='#']", toc);
    var map = {};
    var targets = [];
    links.forEach(function (a) {
      var el = document.getElementById(a.getAttribute("href").slice(1));
      if (el) { map[el.id] = a; targets.push(el); }
    });
    if (!targets.length) return;
    var visible = {};
    var io = new IntersectionObserver(function (entries) {
      entries.forEach(function (entry) { visible[entry.target.id] = entry.isIntersecting; });
      var activeId = null;
      targets.forEach(function (t) { if (visible[t.id] && !activeId) activeId = t.id; });
      links.forEach(function (a) { a.removeAttribute("aria-current"); });
      if (activeId && map[activeId]) map[activeId].setAttribute("aria-current", "true");
    }, { rootMargin: "-96px 0px -60% 0px", threshold: 0 });
    targets.forEach(function (t) { io.observe(t); });
  })();

  /* ---------- 6. 进入视口渐显(尊重减弱动效) ---------- */
  (function () {
    var els = $$(".card, .plugin, .release, .section-head, .download-card, .panel, .room");
    if (!("IntersectionObserver" in window) || reduceMotion) return;
    var io = new IntersectionObserver(function (entries) {
      entries.forEach(function (e) {
        if (e.isIntersecting) { e.target.classList.add("is-in"); io.unobserve(e.target); }
      });
    }, { threshold: 0.1 });
    els.forEach(function (el) { el.classList.add("reveal"); io.observe(el); });
    // 兜底:无论是否滚动到,2.5 秒后全部显示,避免打印/截图/异常环境下内容不可见
    window.setTimeout(function () {
      els.forEach(function (el) { el.classList.add("is-in"); });
    }, 2500);
  })();

  /* ---------- 7. 代码块复制 ---------- */
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

  /* ---------- 8. 页脚年份 ---------- */
  $$("[data-year]").forEach(function (el) { el.textContent = String(new Date().getFullYear()); });
})();

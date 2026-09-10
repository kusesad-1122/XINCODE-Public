#!/usr/bin/env node
/**
 * XINCODE 文档站静态校验(零依赖,供 CI 与本地使用)
 *
 * 检查项:
 *   1. 每个页面都带防护 meta:CSP / referrer / nosniff / robots
 *   2. 没有内联 <script>(CSP 要求脚本全部外置)
 *   3. 每页恰好一个 h1、图片都有 alt、链接与按钮都有可读名称
 *   4. 内部链接与锚点全部可解析
 *   5. 站外链接一律带 rel="noopener"
 *   6. 站点 JS 能通过语法检查,且没有已知的 $().forEach 误用
 *   7. sitemap 覆盖所有页面
 *
 * 用法:node .github/scripts/site-check.mjs [docsDir]
 */
import fs from 'node:fs';
import path from 'node:path';
import { spawnSync } from 'node:child_process';

const docsDir = path.resolve(process.argv[2] || 'docs');
const problems = [];
const notes = [];
const fail = (msg) => problems.push(msg);

// claude-ui-preview.html 是仓库里既有的内部设计预览页(引用 Google Fonts、非官网页面),
// 已用 meta noindex + robots 排除索引,因此不参与官网结构校验。
const NON_SITE_PAGES = new Set(['claude-ui-preview.html']);
const htmlFiles = fs
  .readdirSync(docsDir)
  .filter((f) => f.endsWith('.html') && !NON_SITE_PAGES.has(f))
  .sort();
if (!htmlFiles.length) fail('docs 目录下没有找到任何 HTML 页面');

const idsByFile = {};
const read = (f) => fs.readFileSync(path.join(docsDir, f), 'utf8');
const pages = htmlFiles.map((f) => ({ file: f, html: read(f) }));

// 收集各页 id 供锚点校验
for (const { file, html } of pages) {
  idsByFile[file] = new Set([...html.matchAll(/\sid="([^"]+)"/g)].map((m) => m[1]));
}

const REQUIRED_META = [
  ['Content-Security-Policy', /http-equiv="Content-Security-Policy"/],
  ['referrer', /name="referrer"/],
  ['nosniff', /http-equiv="X-Content-Type-Options"/],
  ['robots', /name="robots"/],
];

for (const { file, html } of pages) {
  const label = file;

  // 1. 防护 meta
  for (const [name, re] of REQUIRED_META) {
    if (!re.test(html)) fail(label + ': 缺少防护 meta —— ' + name);
  }
  if (!/script-src 'self'/.test(html)) fail(label + ': CSP 未限制 script-src \'self\'');

  // 2. 内联脚本
  const inlineScripts = [...html.matchAll(/<script(?![^>]*\bsrc=)[^>]*>[\s\S]*?<\/script>/g)];
  if (inlineScripts.length) fail(label + ': 存在 ' + inlineScripts.length + ' 段内联 <script>,与 CSP 冲突');

  // 3. 结构与命名
  const h1 = [...html.matchAll(/<h1[ >]/g)].length;
  if (h1 !== 1) fail(label + ': h1 数量为 ' + h1 + ',应为 1');
  const imgsWithoutAlt = [...html.matchAll(/<img (?![^>]*\balt=)[^>]*>/g)];
  if (imgsWithoutAlt.length) fail(label + ': 有 ' + imgsWithoutAlt.length + ' 张图片缺少 alt');
  const namelessLinks = [...html.matchAll(/<a\b[^>]*>([\s\S]*?)<\/a>/g)].filter((m) => {
    const inner = m[1].replace(/<[^>]+>/g, '').trim();
    return !inner && !/aria-label=/.test(m[0]);
  });
  if (namelessLinks.length) fail(label + ': 有 ' + namelessLinks.length + ' 个链接缺少可读名称');
  const namelessButtons = [...html.matchAll(/<button\b([^>]*)>([\s\S]*?)<\/button>/g)].filter((m) => {
    return !m[2].replace(/<[^>]+>/g, '').trim() && !/aria-label=/.test(m[1]);
  });
  if (namelessButtons.length) fail(label + ': 有 ' + namelessButtons.length + ' 个按钮缺少可读名称');

  // 4 + 5. 链接
  for (const m of html.matchAll(/href="([^"]+)"/g)) {
    const url = m[1];
    if (url.startsWith('#')) {
      if (url.length > 1 && !idsByFile[file].has(url.slice(1))) fail(label + ': 锚点不存在 ' + url);
      continue;
    }
    if (/^(mailto:|tel:|data:)/.test(url)) continue;
    if (/^https?:/.test(url)) {
      const tag = html.slice(Math.max(0, m.index - 260), m.index + url.length + 260);
      if (/target="_blank"/.test(tag) && !/rel="[^"]*noopener/.test(tag)) {
        fail(label + ': 站外新窗口链接缺少 rel="noopener" → ' + url);
      }
      continue;
    }
    const [target, frag] = url.split('#');
    if (target && !fs.existsSync(path.join(docsDir, decodeURIComponent(target)))) {
      fail(label + ': 内部链接指向不存在的文件 → ' + url);
      continue;
    }
    if (frag) {
      const key = target || file;
      if (idsByFile[key] && !idsByFile[key].has(frag)) fail(label + ': 锚点不存在 → ' + url);
    }
  }

  // 资源引用
  for (const m of html.matchAll(/(?:href|src)="([^"]+)"/g)) {
    const url = m[1];
    if (/^(https?:|mailto:|tel:|#|data:)/.test(url)) continue;
    const clean = decodeURIComponent(url.split('#')[0]);
    if (clean && !fs.existsSync(path.join(docsDir, clean))) fail(label + ': 资源不存在 → ' + url);
  }
}

// 6. 站点脚本
const jsDir = path.join(docsDir, 'assets');
if (fs.existsSync(jsDir)) {
  for (const f of fs.readdirSync(jsDir).filter((f) => f.endsWith('.js'))) {
    const p = path.join(jsDir, f);
    const res = spawnSync(process.execPath, ['--check', p], { encoding: 'utf8' });
    if (res.status !== 0) fail('assets/' + f + ': 语法检查未通过 → ' + (res.stderr || '').split('\n')[0]);
    const src = fs.readFileSync(p, 'utf8');
    const misuse = [...src.matchAll(/(?<!\$)\$\([^)]*\)\.forEach/g)];
    if (misuse.length) fail('assets/' + f + ': 出现 $().forEach 误用(应为 $$)');
    if (/\beval\s*\(|innerHTML\s*=/.test(src)) fail('assets/' + f + ': 使用了 eval 或 innerHTML,与严格 CSP 策略不符');
  }
} else {
  fail('缺少 assets 目录');
}

// 7. sitemap 覆盖
const sitemapPath = path.join(docsDir, 'sitemap.xml');
if (!fs.existsSync(sitemapPath)) {
  fail('缺少 sitemap.xml');
} else {
  const sitemap = fs.readFileSync(sitemapPath, 'utf8');
  for (const f of htmlFiles) {
    if (f === '404.html') continue;
    if (!sitemap.includes(f)) fail('sitemap.xml 未覆盖 ' + f);
  }
}

// 附加:必要发布文件
for (const f of ['robots.txt', 'manifest.webmanifest', 'assets/theme-init.js']) {
  if (!fs.existsSync(path.join(docsDir, f))) fail('缺少 ' + f);
}

notes.push('检查页面 ' + htmlFiles.length + ' 个:' + htmlFiles.join(', '));
if (problems.length) {
  console.error('\n站点校验未通过,共 ' + problems.length + ' 项:\n');
  for (const p of problems) console.error('  ✗ ' + p);
  console.error('\n' + notes.join('\n'));
  process.exit(1);
}
console.log('✓ 站点校验通过(' + htmlFiles.length + ' 个页面)');
for (const n of notes) console.log('  ' + n);

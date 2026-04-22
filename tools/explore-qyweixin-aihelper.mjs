/**
 * 企业微信 AI Helper 工作流页面探索脚本
 * 模拟自然人浏览行为，避免反爬检测
 */
import { chromium } from 'playwright';
import fs from 'fs';
import path from 'path';
import { fileURLToPath } from 'url';

const __dirname = path.dirname(fileURLToPath(import.meta.url));

// ─── 配置 ────────────────────────────────────────────────
const LOGIN_STATE = path.resolve(__dirname, '..', 'WxFbsir-engine', 'data', 'playwright', 'qyweixin', 'user-1', 'login-state.json');
const OUTPUT_DIR  = path.resolve(__dirname, '..', 'WxFbsir-engine', 'data', 'playwright', 'qyweixin', 'aihelper-explore');
const TARGET_URL  = 'https://work.weixin.qq.com/wework_admin/frame#/aiHelper/list?from=manage_tools';

// ─── 工具函数 ────────────────────────────────────────────
/** 随机整数 [min, max] */
const randInt = (min, max) => Math.floor(Math.random() * (max - min + 1)) + min;

/** 随机等待，模拟人类思考/阅读停顿 */
const humanDelay = (min = 800, max = 2500) =>
  new Promise(r => setTimeout(r, randInt(min, max)));

/** 模拟人类鼠标移动到元素（非直线，带抖动） */
async function humanMove(page, selector, options = {}) {
  const el = await page.locator(selector).first();
  const box = await el.boundingBox();
  if (!box) return;

  // 当前鼠标位置（随机起点）
  const startX = randInt(100, 800);
  const startY = randInt(100, 600);
  const endX   = box.x + box.width  / 2 + randInt(-5, 5);
  const endY   = box.y + box.height / 2 + randInt(-3, 3);

  // 贝塞尔曲线中间控制点
  const cpX = (startX + endX) / 2 + randInt(-80, 80);
  const cpY = (startY + endY) / 2 + randInt(-60, 60);

  const steps = randInt(18, 35);
  for (let i = 0; i <= steps; i++) {
    const t  = i / steps;
    const x  = Math.round((1-t)*(1-t)*startX + 2*(1-t)*t*cpX + t*t*endX);
    const y  = Math.round((1-t)*(1-t)*startY + 2*(1-t)*t*cpY + t*t*endY);
    await page.mouse.move(x, y);
    await new Promise(r => setTimeout(r, randInt(8, 25)));
  }
}

/** 人类滚动 */
async function humanScroll(page, distance = 300) {
  const steps = randInt(4, 8);
  for (let i = 0; i < steps; i++) {
    await page.mouse.wheel(0, Math.floor(distance / steps) + randInt(-20, 20));
    await new Promise(r => setTimeout(r, randInt(80, 200)));
  }
}

/** 截图并保存 */
async function shot(page, name) {
  if (!fs.existsSync(OUTPUT_DIR)) fs.mkdirSync(OUTPUT_DIR, { recursive: true });
  const file = path.join(OUTPUT_DIR, `${name}.png`);
  await page.screenshot({ path: file, fullPage: false });
  console.log(`[截图] ${file}`);
  return file;
}

/** 保存文本 */
function saveText(name, content) {
  if (!fs.existsSync(OUTPUT_DIR)) fs.mkdirSync(OUTPUT_DIR, { recursive: true });
  const file = path.join(OUTPUT_DIR, `${name}.txt`);
  fs.writeFileSync(file, content, 'utf-8');
  console.log(`[保存] ${file}`);
}

/** 保存 JSON */
function saveJson(name, data) {
  if (!fs.existsSync(OUTPUT_DIR)) fs.mkdirSync(OUTPUT_DIR, { recursive: true });
  const file = path.join(OUTPUT_DIR, `${name}.json`);
  fs.writeFileSync(file, JSON.stringify(data, null, 2), 'utf-8');
  console.log(`[保存] ${file}`);
}

// ─── 主流程 ─────────────────────────────────────────────
async function main() {
  console.log('='.repeat(60));
  console.log('[企微 AI Helper 探索] 启动');
  console.log('='.repeat(60));

  // 检查登录态文件
  if (!fs.existsSync(LOGIN_STATE)) {
    console.error(`[错误] 登录态文件不存在: ${LOGIN_STATE}`);
    process.exit(1);
  }

  const browser = await chromium.launch({
    headless: false,          // 有界面，更像真人
    slowMo: randInt(30, 60),  // 全局操作延迟
    args: [
      '--disable-blink-features=AutomationControlled',
      '--no-sandbox',
      '--disable-dev-shm-usage',
      '--disable-infobars',
      '--window-size=1440,900',
    ],
  });

  const context = await browser.newContext({
    storageState: LOGIN_STATE,
    viewport: { width: 1440, height: 900 },
    userAgent: 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36',
    locale: 'zh-CN',
    timezoneId: 'Asia/Shanghai',
    // 隐藏 webdriver 特征
    extraHTTPHeaders: {
      'Accept-Language': 'zh-CN,zh;q=0.9',
    },
  });

  // 注入反检测脚本
  await context.addInitScript(() => {
    Object.defineProperty(navigator, 'webdriver', { get: () => undefined });
    Object.defineProperty(navigator, 'plugins',   { get: () => [1, 2, 3, 4, 5] });
    Object.defineProperty(navigator, 'languages', { get: () => ['zh-CN', 'zh'] });
    window.chrome = { runtime: {} };
  });

  const page = await context.newPage();

  // 拦截并记录 XHR/Fetch 请求（发现内部 API）
  const apiCalls = [];
  page.on('request', req => {
    const url = req.url();
    if (
      (url.includes('qyapi.weixin.qq.com') || url.includes('wework_admin')) &&
      ['POST','GET'].includes(req.method())
    ) {
      apiCalls.push({
        method : req.method(),
        url,
        postData: req.postData() || null,
        time: new Date().toISOString(),
      });
    }
  });

  try {
    // ── Step 1: 先访问管理后台首页，让 session 热身 ──
    console.log('\n[Step 1] 访问管理后台首页...');
    await page.goto('https://work.weixin.qq.com/wework_admin/frame', {
      waitUntil: 'domcontentloaded',
      timeout: 30000,
    });
    await humanDelay(2000, 4000);
    await shot(page, '01-admin-home');

    // 检查是否已登录（未登录会跳转到登录页）
    const currentUrl = page.url();
    console.log(`[当前URL] ${currentUrl}`);
    if (currentUrl.includes('login') || currentUrl.includes('signin')) {
      console.warn('[警告] 检测到登录态失效，已跳转到登录页');
      await shot(page, '01-login-required');
      console.log('[提示] 请重新执行企微扫码登录后再运行此脚本');
      await browser.close();
      process.exit(1);
    }

    // ── Step 2: 随机移动鼠标模拟阅读 ──
    console.log('\n[Step 2] 模拟浏览首页...');
    await page.mouse.move(randInt(200, 600), randInt(200, 500));
    await humanDelay(1000, 2000);
    await humanScroll(page, randInt(100, 300));
    await humanDelay(800, 1500);

    // ── Step 3: 导航到 AI Helper ──
    console.log('\n[Step 3] 导航到 AI Helper 工作流页面...');
    await page.goto(TARGET_URL, {
      waitUntil: 'domcontentloaded',
      timeout: 30000,
    });
    await humanDelay(3000, 5000);
    await shot(page, '02-aihelper-list');

    console.log(`[当前URL] ${page.url()}`);

    // ── Step 4: 等待 iframe 内容加载（企微后台是 iframe 架构） ──
    console.log('\n[Step 4] 分析页面结构...');

    // 获取所有 iframe
    const frames = page.frames();
    console.log(`[发现 ${frames.length} 个 frame]`);
    frames.forEach((f, i) => console.log(`  frame[${i}]: ${f.url()}`));

    // 找到主内容 iframe
    let contentFrame = page.mainFrame();
    for (const frame of frames) {
      const url = frame.url();
      if (url.includes('aiHelper') || url.includes('wework_admin') && !url.includes('frame#')) {
        contentFrame = frame;
        console.log(`[使用 frame] ${url}`);
        break;
      }
    }

    // 等待页面内容渲染
    await humanDelay(2000, 3000);
    await shot(page, '03-aihelper-loaded');

    // ── Step 5: 抓取页面文本和关键元素 ──
    console.log('\n[Step 5] 抓取页面内容...');

    const pageText = await page.evaluate(() => document.body.innerText);
    saveText('page-text', pageText);

    // 抓取所有按钮
    const buttons = await page.evaluate(() => {
      const els = document.querySelectorAll('button, [role="button"], .js_btn, [class*="btn"]');
      return Array.from(els).map(el => ({
        text   : el.innerText?.trim(),
        class  : el.className,
        id     : el.id,
        href   : el.href || null,
        visible: el.offsetParent !== null,
      })).filter(b => b.text);
    });
    saveJson('buttons', buttons);
    console.log(`[按钮] 发现 ${buttons.length} 个`);
    buttons.slice(0, 20).forEach(b => console.log(`  - "${b.text}" [${b.class?.slice(0,40)}]`));

    // 抓取导航菜单
    const navItems = await page.evaluate(() => {
      const els = document.querySelectorAll('nav a, .menu_item, [class*="nav"] a, [class*="menu"] a, li a');
      return Array.from(els).map(el => ({
        text : el.innerText?.trim(),
        href : el.href,
      })).filter(n => n.text && n.text.length < 20);
    });
    saveJson('nav-items', navItems.slice(0, 50));

    // ── Step 6: 尝试找「新建工作流」或「创建」按钮 ──
    console.log('\n[Step 6] 查找创建入口...');
    await humanDelay(1500, 2500);

    const createSelectors = [
      'button:has-text("新建")',
      'button:has-text("创建")',
      'button:has-text("添加")',
      '[class*="create"]',
      '[class*="add-btn"]',
      'a:has-text("新建工作流")',
      'a:has-text("新建智能助手")',
    ];

    let createBtn = null;
    for (const sel of createSelectors) {
      try {
        const count = await page.locator(sel).count();
        if (count > 0) {
          console.log(`[找到创建按钮] selector: ${sel}, count: ${count}`);
          createBtn = sel;
          break;
        }
      } catch (_) {}
    }

    if (createBtn) {
      await humanMove(page, createBtn);
      await humanDelay(500, 1000);
      await shot(page, '04-create-btn-hover');

      // 点击创建按钮
      await page.locator(createBtn).first().click();
      await humanDelay(2000, 3500);
      await shot(page, '05-create-dialog');

      // 抓取弹窗/新页面内容
      const dialogText = await page.evaluate(() => document.body.innerText);
      saveText('create-dialog-text', dialogText);

      // 抓取表单字段
      const formFields = await page.evaluate(() => {
        const inputs = document.querySelectorAll('input, textarea, select');
        return Array.from(inputs).map(el => ({
          type        : el.type || el.tagName,
          name        : el.name,
          placeholder : el.placeholder,
          label       : el.closest('label')?.innerText || el.id,
        }));
      });
      saveJson('create-form-fields', formFields);
      console.log(`[表单字段] 发现 ${formFields.length} 个`);
      formFields.forEach(f => console.log(`  - [${f.type}] ${f.placeholder || f.name || f.label}`));
    } else {
      console.log('[未找到创建按钮] 可能需要进一步导航');
      await shot(page, '04-no-create-btn');
    }

    // ── Step 7: 滚动浏览列表，截图 ──
    console.log('\n[Step 7] 滚动浏览列表...');
    await humanScroll(page, 400);
    await humanDelay(1500, 2500);
    await shot(page, '06-scrolled-list');

    // ── Step 8: 保存捕获的 API 调用 ──
    console.log('\n[Step 8] 保存 API 调用记录...');
    saveJson('api-calls', apiCalls);
    console.log(`[API 调用] 共捕获 ${apiCalls.length} 条`);
    apiCalls.forEach(a => console.log(`  [${a.method}] ${a.url}`));

    // ── Step 9: 抓取完整 HTML 结构（用于离线分析） ──
    console.log('\n[Step 9] 保存页面 HTML...');
    const html = await page.content();
    saveText('page-html', html);

    console.log('\n' + '='.repeat(60));
    console.log('[完成] 所有探索数据已保存到:');
    console.log(`  ${OUTPUT_DIR}`);
    console.log('='.repeat(60));

    // 停留片刻再关闭（自然退出）
    await humanDelay(2000, 3000);

  } catch (err) {
    console.error('[错误]', err.message);
    await shot(page, 'error-state').catch(() => {});
  } finally {
    await browser.close();
  }
}

main().catch(err => {
  console.error('[致命错误]', err);
  process.exit(1);
});

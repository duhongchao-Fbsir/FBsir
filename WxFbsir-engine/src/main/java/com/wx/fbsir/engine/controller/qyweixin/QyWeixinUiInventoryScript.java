package com.wx.fbsir.engine.controller.qyweixin;

/**
 * 企微 AI 助手页 {@code page.evaluate} 脚本：单一来源，供 EXPLORE_UI_MAP 与未来单次快照复用。
 * 输出尽量结构化，便于项目侧做「后台控件 ↔ Engine 能力」映射；版本号用于前后端对比。
 */
public final class QyWeixinUiInventoryScript {

    public static final String SCHEMA_VERSION = "2";

    private QyWeixinUiInventoryScript() {
    }

    /**
     * 执行后返回 Map：含 tabs、buttons、稳定属性线索、iframe 摘要等。
     */
    public static String fineInventoryExpression() {
        return "() => {\n"
            + "  const clip = (s, n) => (s == null ? '' : String(s).trim().replace(/\\s+/g, ' ').substring(0, n));\n"
            + "  const vis = (el) => {\n"
            + "    try {\n"
            + "      const st = window.getComputedStyle(el);\n"
            + "      if (st.display === 'none' || st.visibility === 'hidden' || Number(st.opacity) === 0) return false;\n"
            + "      const r = el.getBoundingClientRect();\n"
            + "      return r.width > 0 && r.height > 0;\n"
            + "    } catch (e) { return false; }\n"
            + "  };\n"
            + "  const dataAttrs = (el) => {\n"
            + "    const out = {};\n"
            + "    if (!el || !el.attributes) return out;\n"
            + "    for (const a of el.attributes) {\n"
            + "      if (a.name.startsWith('data-')) out[a.name] = clip(a.value, 80);\n"
            + "    }\n"
            + "    return out;\n"
            + "  };\n"
            + "  const pickStable = (el) => ({\n"
            + "    id: el.id ? clip(el.id, 64) : null,\n"
            + "    testId: el.getAttribute('data-testid') || el.getAttribute('data-test-id'),\n"
            + "    dataAttrs: dataAttrs(el)\n"
            + "  });\n"
            + "  const tabs = [...document.querySelectorAll('[role=\"tab\"], [role=\"tablist\"] [role=\"tab\"]')]\n"
            + "    .filter(vis).slice(0, 40).map((el, i) => Object.assign({\n"
            + "      i, text: clip(el.innerText || el.textContent, 80),\n"
            + "      ariaSelected: el.getAttribute('aria-selected'),\n"
            + "      cls: clip(el.className, 96)\n"
            + "    }, pickStable(el)));\n"
            + "  const tabLike = [...document.querySelectorAll('[class*=\"tab\"][class*=\"item\"], .menu_item, "
            + "[class*=\"sidebar\"][class*=\"item\"]')]\n"
            + "    .filter(vis).slice(0, 48).map((el, i) => Object.assign({ i, "
            + "text: clip(el.innerText || el.textContent, 80), cls: clip(el.className, 96) }, pickStable(el)));\n"
            + "  const buttons = [...document.querySelectorAll('button, [role=\"button\"], input[type=\"button\"], "
            + "input[type=\"submit\"]')]\n"
            + "    .filter(vis).filter((el) => clip(el.innerText || el.textContent, 2).length > 0)\n"
            + "    .slice(0, 100).map((el, i) => Object.assign({\n"
            + "      i, text: clip(el.innerText || el.textContent, 56),\n"
            + "      aria: clip(el.getAttribute('aria-label'), 48),\n"
            + "      cls: clip(el.className, 96)\n"
            + "    }, pickStable(el)));\n"
            + "  const hashLinks = [...document.querySelectorAll('a[href*=\"#\"]')]\n"
            + "    .filter(vis).slice(0, 56).map((el) => Object.assign({\n"
            + "      text: clip(el.innerText || el.textContent, 64),\n"
            + "      href: clip(el.getAttribute('href'), 200)\n"
            + "    }, pickStable(el)));\n"
            + "  const ths = [...document.querySelectorAll('table thead th, thead th')]\n"
            + "    .map((el) => clip(el.innerText || el.textContent, 48)).filter(Boolean).slice(0, 36);\n"
            + "  const tbodyTr = document.querySelectorAll('tbody tr').length;\n"
            + "  const inputs = [...document.querySelectorAll('input:not([type=\"hidden\"])')]\n"
            + "    .filter(vis).slice(0, 48).map((el) => Object.assign({\n"
            + "      type: el.type, ph: clip(el.placeholder, 48), name: clip(el.name, 40), cls: clip(el.className, 72)\n"
            + "    }, pickStable(el)));\n"
            + "  const landmarks = [...document.querySelectorAll('[role=\"navigation\"],[role=\"main\"],[role=\"banner\"]')]\n"
            + "    .filter(vis).map((el) => ({ role: el.getAttribute('role'), text: clip(el.innerText, 120), "
            + "cls: clip(el.className, 80) }));\n"
            + "  const iframes = [...document.querySelectorAll('iframe')].slice(0, 16).map((f) => ({\n"
            + "    src: clip(f.src, 200), title: clip(f.title, 80)\n"
            + "  }));\n"
            + "  return {\n"
            + "    schemaVersion: '" + SCHEMA_VERSION + "',\n"
            + "    hash: location.hash,\n"
            + "    path: location.pathname,\n"
            + "    title: clip(document.title, 160),\n"
            + "    tabs, tabLikeSidebarOrMenu: tabLike, buttons, hashLinks, tableHeaders: ths, tbodyRowCount: tbodyTr, "
            + "inputs, landmarks, iframes\n"
            + "  };\n"
            + "}";
    }
}

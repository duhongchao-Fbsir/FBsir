package com.wx.fbsir.engine.controller.qyweixin.support;

/**
 * 企微工作流编辑器：<b>完整克隆</b>所需的 DOM 级采样脚本（浏览器内 {@code page.evaluate}）。
 * <p>
 * 与「列表复制」无关：通过对画布节点逐个点选，采集配置面板内的表单字段快照，供目标工作流回放。
 */
public final class QyWeixinWorkflowFullPanelSampler {

    private QyWeixinWorkflowFullPanelSampler() {
    }

    /**
     * 列出画布上带 {@code data-node-id} 的节点，按视觉顺序（先 y 后 x）排序。
     */
    /**
     * 画布级分支/循环边数量启发（不遍历分支语义，仅作学习信号与策略提示）。
     */
    public static String jsCanvasGraphHints() {
        return "() => {\n"
            + "  const br = document.querySelectorAll('g.branch-paths path').length;\n"
            + "  const lp = document.querySelectorAll('g.loop-paths path').length;\n"
            + "  return { branchPathCount: br, loopPathCount: lp, nonLinear: (br + lp) > 0 };\n"
            + "}";
    }

    public static String jsListOrderedCanvasNodes() {
        return "() => {\n"
            + "  const norm = (s) => (s || '').replace(/\\s+/g, ' ').trim();\n"
            + "  const els = [...document.querySelectorAll('[data-node-id]')];\n"
            + "  const rows = els.map((el, idx) => {\n"
            + "    const r = el.getBoundingClientRect();\n"
            + "    const full = norm(el.textContent || '');\n"
            + "    const first = norm((el.textContent || '').split(/\\n|\\r/)[0] || '');\n"
            + "    return {\n"
            + "      order: idx,\n"
            + "      nodeId: el.getAttribute('data-node-id') || '',\n"
            + "      x: Math.round(r.left), y: Math.round(r.top),\n"
            + "      width: Math.round(r.width), height: Math.round(r.height),\n"
            + "      labelLine: first.substring(0, 120),\n"
            + "      cardText: full.substring(0, 400)\n"
            + "    };\n"
            + "  }).filter(n => n.width >= 40 && n.height >= 30);\n"
            + "  rows.sort((a,b) => (a.y - b.y) || (a.x - b.x));\n"
            + "  return rows;\n"
            + "}";
    }

    /**
     * 采集当前最顶层可见配置区（抽屉/对话框）内可编辑字段与可见文本摘要。
     */
    public static String jsExtractTopConfigPanelSnapshot() {
        return "() => {\n"
            + "  const norm = (s) => (s || '').replace(/\\s+/g, ' ').trim();\n"
            + "  const candidates = [...document.querySelectorAll("
            + "'[role=\"dialog\"], .t-dialog, .t-drawer__content, .t-drawer, "
            + "[class*=\"drawer\"], [class*=\"Drawer\"], [class*=\"modal\"], [class*=\"Modal\"]')];\n"
            + "  let best = null;\n"
            + "  let bestArea = 0;\n"
            + "  for (const el of candidates) {\n"
            + "    const cs = window.getComputedStyle(el);\n"
            + "    if (cs.display === 'none' || cs.visibility === 'hidden' || cs.opacity === '0') continue;\n"
            + "    const r = el.getBoundingClientRect();\n"
            + "    if (r.width < 120 || r.height < 80) continue;\n"
            + "    const area = r.width * r.height;\n"
            + "    if (area > bestArea) { bestArea = area; best = el; }\n"
            + "  }\n"
            + "  const root = best || document.body;\n"
            + "  const titleLike = root.querySelector("
            + "'[class*=\"header\"] [class*=\"title\"], .t-dialog__header, .t-drawer__header, h1, h2, h3, h4');\n"
            + "  const panelTitle = titleLike ? norm(titleLike.textContent).substring(0, 120) : '';\n"
            + "  const fields = [];\n"
            + "  const inputs = root.querySelectorAll("
            + "'input:not([type=\"hidden\"]):not([type=\"checkbox\"]):not([type=\"radio\"]), textarea, "
            + "[contenteditable=\"true\"]');\n"
            + "  let fi = 0;\n"
            + "  for (const inp of inputs) {\n"
            + "    if (!inp) continue;\n"
            + "    const cs = window.getComputedStyle(inp);\n"
            + "    if (cs.display === 'none' || cs.visibility === 'hidden') continue;\n"
            + "    const r = inp.getBoundingClientRect();\n"
            + "    if (r.width < 2 || r.height < 2) continue;\n"
            + "    const tag = (inp.tagName || '').toLowerCase();\n"
            + "    const type = inp.getAttribute('type') || '';\n"
            + "    let value = '';\n"
            + "    try {\n"
            + "      value = inp.value != null ? String(inp.value) : '';\n"
            + "    } catch (e) { value = ''; }\n"
            + "    if (tag === 'div' && inp.isContentEditable) {\n"
            + "      value = norm(inp.innerText || inp.textContent || '').substring(0, 8000);\n"
            + "    }\n"
            + "    let label = '';\n"
            + "    try {\n"
            + "      if (inp.id) {\n"
            + "        const lb = root.querySelector(`label[for=\"${inp.id}\"]`);\n"
            + "        if (lb) label = norm(lb.textContent).substring(0, 120);\n"
            + "      }\n"
            + "    } catch (e) {}\n"
            + "    if (!label) {\n"
            + "      const par = inp.parentElement;\n"
            + "      if (par) {\n"
            + "        const sibs = [...par.querySelectorAll('span,label,div')];\n"
            + "        for (const s of sibs) {\n"
            + "          const tx = norm(s.textContent || '');\n"
            + "          if (tx && tx.length < 80 && tx.length > 0) { label = tx.substring(0, 80); break; }\n"
            + "        }\n"
            + "      }\n"
            + "    }\n"
            + "    fields.push({\n"
            + "      index: fi++,\n"
            + "      tag,\n"
            + "      type: type || tag,\n"
            + "      name: inp.getAttribute('name') || '',\n"
            + "      placeholder: inp.getAttribute('placeholder') || '',\n"
            + "      label,\n"
            + "      value: value.substring(0, 8000)\n"
            + "    });\n"
            + "  }\n"
            + "  return {\n"
            + "    panelTitle,\n"
            + "    fieldCount: fields.length,\n"
            + "    fields,\n"
            + "    panelTextDigest: norm(root.innerText || '').substring(0, 1200)\n"
            + "  };\n"
            + "}";
    }

    /**
     * 将值按序号写回当前可见配置面板中的输入控件（顺序与导出时一致）。用于克隆回放。
     */
    public static String jsReplayFieldValuesByOrder() {
        // 参数：fields: Array<{ value: string }>
        return "(fields) => {\n"
            + "  const norm = (s) => (s || '').replace(/\\s+/g, ' ').trim();\n"
            + "  const roots = [...document.querySelectorAll("
            + "'[role=\"dialog\"], .t-dialog, .t-drawer__content, .t-drawer, "
            + "[class*=\"drawer\"], [class*=\"Drawer\"], [class*=\"modal\"], [class*=\"Modal\"]')];\n"
            + "  let best = null;\n"
            + "  let bestArea = 0;\n"
            + "  for (const el of roots) {\n"
            + "    const cs = window.getComputedStyle(el);\n"
            + "    if (cs.display === 'none' || cs.visibility === 'hidden') continue;\n"
            + "    const r = el.getBoundingClientRect();\n"
            + "    if (r.width < 120 || r.height < 80) continue;\n"
            + "    const area = r.width * r.height;\n"
            + "    if (area > bestArea) { bestArea = area; best = el; }\n"
            + "  }\n"
            + "  const root = best || document.body;\n"
            + "  const inputs = [...root.querySelectorAll("
            + "'input:not([type=\"hidden\"]):not([type=\"checkbox\"]):not([type=\"radio\"]), textarea, "
            + "[contenteditable=\"true\"]')]"
            + ".filter(inp => {\n"
            + "    const cs = window.getComputedStyle(inp);\n"
            + "    if (cs.display === 'none' || cs.visibility === 'hidden') return false;\n"
            + "    const r = inp.getBoundingClientRect();\n"
            + "    return r.width >= 2 && r.height >= 2;\n"
            + "  });\n"
            + "  const list = Array.isArray(fields) ? fields : [];\n"
            + "  let n = 0;\n"
            + "  for (let i = 0; i < inputs.length && i < list.length; i++) {\n"
            + "    const inp = inputs[i];\n"
            + "    const val = list[i] && list[i].value != null ? String(list[i].value) : '';\n"
            + "    const tag = (inp.tagName || '').toLowerCase();\n"
            + "    try {\n"
            + "      inp.focus();\n"
            + "      if (tag === 'textarea' || (tag === 'input' && (inp.type === 'text' || inp.type === '' || !inp.type))) {\n"
            + "        const desc = Object.getOwnPropertyDescriptor(window.HTMLTextAreaElement.prototype, 'value')\n"
            + "          || Object.getOwnPropertyDescriptor(window.HTMLInputElement.prototype, 'value');\n"
            + "        if (desc && desc.set) desc.set.call(inp, val); else inp.value = val;\n"
            + "        inp.dispatchEvent(new Event('input', { bubbles: true }));\n"
            + "        inp.dispatchEvent(new Event('change', { bubbles: true }));\n"
            + "      } else if (inp.isContentEditable) {\n"
            + "        inp.textContent = val;\n"
            + "        inp.dispatchEvent(new Event('input', { bubbles: true }));\n"
            + "      }\n"
            + "      n++;\n"
            + "    } catch (e) {}\n"
            + "  }\n"
            + "  return { replayed: n, editors: inputs.length, payload: list.length };\n"
            + "}";
    }
}

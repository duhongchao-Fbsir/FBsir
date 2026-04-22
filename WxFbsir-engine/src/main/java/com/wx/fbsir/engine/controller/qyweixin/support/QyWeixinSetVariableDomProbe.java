package com.wx.fbsir.engine.controller.qyweixin.support;

/**
 * 企微「设置变量」弹层：纯浏览器内 DOM 探测与赋值（不依赖 Playwright 固定定位 — 布局会变）。
 * <p>
 * 通过 {@link com.microsoft.playwright.Page#evaluate(String, Object)} 注入执行。
 */
public final class QyWeixinSetVariableDomProbe {

    private QyWeixinSetVariableDomProbe() {
    }

    /**
     * @return 是否找到「设置变量」弹层并对其中「+添加」类控件执行了一次 click
     */
    public static String jsClickAddInSetVariableModal() {
        return """
                () => {
                  const visible = (el) => {
                    if (!el || !el.getBoundingClientRect) return false;
                    const st = window.getComputedStyle(el);
                    if (st.display === 'none' || st.visibility === 'hidden' || Number(st.opacity) === 0) return false;
                    const r = el.getBoundingClientRect();
                    if (r.width < 1 || r.height < 1) return false;
                    return r.bottom > 0 && r.top < window.innerHeight;
                  };
                  const findModal = () => {
                    const dialogs = document.querySelectorAll('[role="dialog"], [role="alertdialog"]');
                    for (const d of dialogs) {
                      const t = d.innerText || '';
                      if (t.includes('设置变量') && (t.includes('输入变量') || t.includes('变量列表'))) return d;
                    }
                    const candidates = document.querySelectorAll('div');
                    let best = null;
                    let bestLen = 1e9;
                    for (const d of candidates) {
                      const t = d.innerText || '';
                      if (!t.includes('设置变量')) continue;
                      if (!t.includes('输入变量') && !t.includes('变量列表')) continue;
                      if (t.length > 25000) continue;
                      if (t.length < bestLen) { best = d; bestLen = t.length; }
                    }
                    return best;
                  };
                  const root = findModal();
                  if (!root) return false;
                  const nodes = root.querySelectorAll('button, [role="button"], [role="menuitem"], a, span, div');
                  for (const el of nodes) {
                    if (!visible(el)) continue;
                    let tx = (el.innerText || el.textContent || '').replace(/\\s+/g, ' ').trim();
                    if (!tx.includes('添加')) continue;
                    if (tx.length > 24) continue;
                    const hasPlus = tx.includes('+') || tx.includes('＋');
                    if (!hasPlus && tx !== '添加') continue;
                    try {
                      el.click();
                      return true;
                    } catch (e) { }
                  }
                  return false;
                }
                """;
    }

    /**
     * 在「设置变量」弹层内给变量名输入赋占位值；优先选「最后一个空」的可编辑位（新行常追加在末尾）。
     *
     * @param varName 注入为第一参 name
     */
    public static String jsFillFirstVariableNameField() {
        return """
                (name) => {
                  const setReactInput = (el, val) => {
                    if (!el) return false;
                    const tag = (el.tagName || '').toLowerCase();
                    if (el.isContentEditable) {
                      el.focus();
                      el.textContent = val;
                      el.dispatchEvent(new InputEvent('input', { bubbles: true, data: val }));
                      return true;
                    }
                    if (tag === 'textarea') {
                      const desc = Object.getOwnPropertyDescriptor(window.HTMLTextAreaElement.prototype, 'value');
                      if (desc && desc.set) desc.set.call(el, val); else el.value = val;
                      el.dispatchEvent(new Event('input', { bubbles: true }));
                      el.dispatchEvent(new Event('change', { bubbles: true }));
                      return true;
                    }
                    if (tag === 'input') {
                      const tp = (el.type || '').toLowerCase();
                      if (tp === 'hidden' || tp === 'file' || tp === 'checkbox' || tp === 'radio' || tp === 'button')
                        return false;
                      const desc = Object.getOwnPropertyDescriptor(window.HTMLInputElement.prototype, 'value');
                      if (desc && desc.set) desc.set.call(el, val); else el.value = val;
                      el.dispatchEvent(new Event('input', { bubbles: true }));
                      el.dispatchEvent(new Event('change', { bubbles: true }));
                      return true;
                    }
                    return false;
                  };
                  const visible = (el) => {
                    if (!el || !el.getBoundingClientRect) return false;
                    const st = window.getComputedStyle(el);
                    if (st.display === 'none' || st.visibility === 'hidden') return false;
                    const r = el.getBoundingClientRect();
                    if (r.width < 2 || r.height < 2) return false;
                    return r.bottom > 0 && r.top < window.innerHeight;
                  };
                  const findModal = () => {
                    const dialogs = document.querySelectorAll('[role="dialog"], [role="alertdialog"]');
                    for (const d of dialogs) {
                      const t = d.innerText || '';
                      if (t.includes('设置变量') && (t.includes('输入变量') || t.includes('变量列表'))) return d;
                    }
                    let best = null;
                    let bestLen = 1e9;
                    for (const d of document.querySelectorAll('div')) {
                      const t = d.innerText || '';
                      if (!t.includes('设置变量')) continue;
                      if (!t.includes('输入变量') && !t.includes('变量列表')) continue;
                      if (t.length > 25000) continue;
                      if (t.length < bestLen) { best = d; bestLen = t.length; }
                    }
                    return best;
                  };
                  const root = findModal();
                  if (!root) return false;
                  const collected = [];
                  root.querySelectorAll('input, textarea, [contenteditable="true"]').forEach(el => {
                    if (!visible(el)) return;
                    const tp = (el.type || '').toLowerCase();
                    if (tp === 'hidden' || tp === 'file' || tp === 'checkbox' || tp === 'radio' || tp === 'button') return;
                    collected.push(el);
                  });
                  if (collected.length === 0) return false;
                  let target = null;
                  for (let i = collected.length - 1; i >= 0; i--) {
                    const el = collected[i];
                    const cur = el.value !== undefined ? el.value : (el.innerText || '');
                    if (!String(cur).trim()) { target = el; break; }
                  }
                  if (!target) target = collected[collected.length - 1];
                  return setReactInput(target, String(name || 'skill_import_var'));
                }
                """;
    }
}

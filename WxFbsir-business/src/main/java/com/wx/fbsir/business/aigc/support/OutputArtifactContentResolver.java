package com.wx.fbsir.business.aigc.support;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 从会话 data JSON（合并后的 Map）解析可用于生成输出物的正文摘要。
 * 优先使用多 AI 的 {@code results[]}，兼容历史单路 {@code data.answer}。
 */
public final class OutputArtifactContentResolver {

    private OutputArtifactContentResolver() {
    }

    public static final class Resolution {
        private final boolean ok;
        private final String message;
        private final String summaryText;

        private Resolution(boolean ok, String message, String summaryText) {
            this.ok = ok;
            this.message = message;
            this.summaryText = summaryText;
        }

        public static Resolution success(String summaryText) {
            return new Resolution(true, null, summaryText);
        }

        public static Resolution failure(String message) {
            return new Resolution(false, message, null);
        }

        public boolean isOk() {
            return ok;
        }

        public String getMessage() {
            return message;
        }

        public String getSummaryText() {
            return summaryText;
        }
    }

    /**
     * @param dataMap 解析后的 wc_chat_history.data JSON 根对象
     */
    public static Resolution resolve(Map<String, Object> dataMap) {
        return resolve(dataMap, null);
    }

    /**
     * @param dataMap 解析后的 wc_chat_history.data JSON 根对象
     * @param includedAiTypes 需要纳入输出物的 aiType 列表；为空时表示全部
     */
    public static Resolution resolve(Map<String, Object> dataMap, List<String> includedAiTypes) {
        if (dataMap == null || dataMap.isEmpty()) {
            return Resolution.failure("会话数据为空");
        }
        Set<String> includeSet = includedAiTypes == null ? Set.of() : includedAiTypes.stream()
            .filter(s -> s != null && !s.isBlank())
            .map(s -> s.trim().toLowerCase())
            .collect(Collectors.toSet());
        Object resObj = dataMap.get("results");
        if (resObj instanceof List<?> list && !list.isEmpty()) {
            StringBuilder sb = new StringBuilder();
            int withText = 0;
            for (Object o : list) {
                if (!(o instanceof Map<?, ?> m)) {
                    continue;
                }
                Object aiRaw = m.get("aiType");
                String ai = aiRaw != null ? String.valueOf(aiRaw) : "unknown";
                if (!includeSet.isEmpty() && !includeSet.contains(ai.toLowerCase())) {
                    continue;
                }
                String a = stringVal(m.get("answer"));
                String t = stringVal(m.get("textContent"));
                String text = firstNonBlank(a, t);
                if (text != null && !text.isBlank()) {
                    withText++;
                    sb.append("【").append(ai).append("】\n").append(text.trim()).append("\n\n");
                }
            }
            if (withText == 0) {
                if (!includeSet.isEmpty()) {
                    return Resolution.failure("所选AI暂无可用文本结果，请调整勾选后重试");
                }
                Resolution fb = fallbackNestedOrRootAnswer(dataMap);
                if (fb != null) {
                    return fb;
                }
                return Resolution.failure("各 AI 尚无可用文本结果，请待对话完成后再生成输出物");
            }
            return Resolution.success(sb.toString().trim());
        }
        Object nested = dataMap.get("data");
        if (nested instanceof Map<?, ?> inner) {
            String answer = stringVal(inner.get("answer"));
            if (answer != null && !answer.isBlank()) {
                return Resolution.success(answer.trim());
            }
        }
        String rootAns = stringVal(dataMap.get("answer"));
        if (rootAns != null && !rootAns.isBlank()) {
            return Resolution.success(rootAns.trim());
        }
        return Resolution.failure("请先完成对话后再生成输出物");
    }

    /**
     * {@code results[]} 存在但条目无正文时，回退嵌套 {@code data.answer} 与根级 {@code answer}（兼容脏/中间态数据）。
     */
    private static Resolution fallbackNestedOrRootAnswer(Map<String, Object> dataMap) {
        Object nested = dataMap.get("data");
        if (nested instanceof Map<?, ?> inner) {
            String answer = stringVal(inner.get("answer"));
            if (answer != null && !answer.isBlank()) {
                return Resolution.success(answer.trim());
            }
        }
        String rootAns = stringVal(dataMap.get("answer"));
        if (rootAns != null && !rootAns.isBlank()) {
            return Resolution.success(rootAns.trim());
        }
        return null;
    }

    private static String stringVal(Object o) {
        return o == null ? null : String.valueOf(o);
    }

    private static String firstNonBlank(String a, String b) {
        if (a != null && !a.isBlank()) {
            return a;
        }
        if (b != null && !b.isBlank()) {
            return b;
        }
        return null;
    }
}

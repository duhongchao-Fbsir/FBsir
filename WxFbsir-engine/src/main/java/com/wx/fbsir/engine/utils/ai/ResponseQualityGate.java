package com.wx.fbsir.engine.utils.ai;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.HashMap;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 结果质量门禁：用于识别“流程成功但结果可疑”的场景。
 */
public final class ResponseQualityGate {

    private static final Pattern TOKEN_PATTERN = Pattern.compile("[\\u4e00-\\u9fa5]{2,}|[A-Za-z][A-Za-z0-9_-]{2,}");
    private static final Pattern TRAILING_TS = Pattern.compile("_\\d{8,}[A-Za-z0-9]*$");

    private static final Set<String> STOP_WORDS = Set.of(
        "这里", "什么", "内容", "图片", "图中", "这个", "请", "帮我", "一下",
        "identify", "image", "content", "please", "what", "this", "strictly", "visible", "text"
    );

    private static final List<String> PLACEHOLDER_HINTS = Arrays.asList(
        "无法查看图片", "无法直接查看", "不能查看图片", "请提供图片文字",
        "我无法直接查看", "无法识别图片", "超时未返回可读正文", "未找到输入框或发送失败"
    );

    private ResponseQualityGate() {
    }

    public static Map<String, Object> evaluate(
        String query,
        String answer,
        boolean uploadAttempted,
        boolean uploadEffective,
        String uploadedFileUrl
    ) {
        Map<String, Object> out = new HashMap<>();
        List<String> reasonCodes = new ArrayList<>();

        Set<String> anchors = new LinkedHashSet<>();
        anchors.addAll(extractUsefulTokens(query));
        anchors.addAll(extractUsefulTokens(extractFileName(uploadedFileUrl)));

        String normalizedAnswer = answer == null ? "" : answer;
        List<String> hitAnchors = new ArrayList<>();
        for (String token : anchors) {
            if (containsToken(normalizedAnswer, token)) {
                hitAnchors.add(token);
            }
        }

        if (uploadAttempted && !uploadEffective) {
            reasonCodes.add("UPLOAD_NOT_EFFECTIVE");
        }
        if (!anchors.isEmpty() && hitAnchors.isEmpty()) {
            reasonCodes.add("LOW_RELEVANCE");
        }
        if (containsPlaceholderHint(normalizedAnswer)) {
            reasonCodes.add("PLACEHOLDER_OR_REFUSAL");
        }

        String status = reasonCodes.isEmpty() ? "trusted" : "suspect";
        out.put("status", status);
        out.put("uploadAttempted", uploadAttempted);
        out.put("uploadEffective", uploadEffective);
        out.put("anchors", new ArrayList<>(anchors));
        out.put("anchorHits", hitAnchors);
        out.put("reasonCodes", reasonCodes);
        out.put("summary", buildSummary(status, reasonCodes));
        return out;
    }

    private static String buildSummary(String status, List<String> reasonCodes) {
        if ("trusted".equals(status)) {
            return "结果通过基础门禁";
        }
        return "结果可疑: " + String.join(", ", reasonCodes);
    }

    private static boolean containsPlaceholderHint(String answer) {
        if (answer == null || answer.isBlank()) {
            return true;
        }
        for (String hint : PLACEHOLDER_HINTS) {
            if (answer.contains(hint)) {
                return true;
            }
        }
        return false;
    }

    private static boolean containsToken(String text, String token) {
        if (text == null || token == null || token.isBlank()) {
            return false;
        }
        if (isAsciiWord(token)) {
            return text.toLowerCase(Locale.ROOT).contains(token.toLowerCase(Locale.ROOT));
        }
        return text.contains(token);
    }

    private static boolean isAsciiWord(String token) {
        for (int i = 0; i < token.length(); i++) {
            if (token.charAt(i) > 127) {
                return false;
            }
        }
        return true;
    }

    private static List<String> extractUsefulTokens(String text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        Matcher m = TOKEN_PATTERN.matcher(text);
        while (m.find()) {
            String token = m.group().trim();
            if (token.length() < 2) {
                continue;
            }
            String lower = token.toLowerCase(Locale.ROOT);
            if (STOP_WORDS.contains(token) || STOP_WORDS.contains(lower)) {
                continue;
            }
            out.add(token);
        }
        return out;
    }

    private static String extractFileName(String uploadedFileUrl) {
        if (uploadedFileUrl == null || uploadedFileUrl.isBlank()) {
            return "";
        }
        String raw = uploadedFileUrl;
        int idx = raw.lastIndexOf('/');
        if (idx >= 0 && idx + 1 < raw.length()) {
            raw = raw.substring(idx + 1);
        }
        try {
            raw = URLDecoder.decode(raw, StandardCharsets.UTF_8);
        } catch (Exception ignore) {
            // ignore decode issue
        }
        int dotIdx = raw.lastIndexOf('.');
        if (dotIdx > 0) {
            raw = raw.substring(0, dotIdx);
        }
        return TRAILING_TS.matcher(raw).replaceAll("");
    }
}

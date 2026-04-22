package com.wx.fbsir.engine.controller.qyweixin.learning;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.wx.fbsir.engine.config.EngineProperties;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 企微工作流「等价克隆」过程中的<strong>增量学习记忆</strong>。
 * <p>
 * 分支/循环的视觉样式与 DOM 结构无法穷举遍历，本组件不试图覆盖全部分支类型，而是：
 * <ul>
 *   <li>用面板标题 + 内容摘要生成稳定签名，对「节点类型菜单」选择做<strong>频率加权</strong>；</li>
 *   <li>在每次回放步骤后根据成功/失败更新票数；</li>
 *   <li>支持显式纠偏（{@link #mergeFeedback}），单次纠偏给予更高权重；</li>
 *   <li>累计「分支/子图较复杂」的运行次数，供上层调整策略（更保守的延时等）。</li>
 * </ul>
 */
@Component
public class QyWeixinWorkflowCloneLearning {

    private static final Logger log = LoggerFactory.getLogger(QyWeixinWorkflowCloneLearning.class);

    private static final int VERSION = 1;
    private static final int FEEDBACK_WEIGHT = 10;

    private final EngineProperties engineProperties;

    private final Object lock = new Object();
    private Path resolvedFile;
    /** 签名 -> 菜单文案 -> 累计成功票数 */
    private final Map<String, Map<String, AtomicInteger>> sigToMenuVotes = new LinkedHashMap<>();
    private volatile long branchHeavyRuns;

    public QyWeixinWorkflowCloneLearning(EngineProperties engineProperties) {
        this.engineProperties = engineProperties;
    }

    @PostConstruct
    public void init() {
        try {
            EngineProperties.QyWeixinConfig cfg = engineProperties.getQyweixin();
            String rel = cfg != null && cfg.getCloneLearningFile() != null && !cfg.getCloneLearningFile().isBlank()
                    ? cfg.getCloneLearningFile()
                    : "logs/qywx-clone-learning.json";
            resolvedFile = Paths.get(rel).toAbsolutePath().normalize();
            Path parent = resolvedFile.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            loadFromDisk();
            log.info("[QywxCloneLearning] 记忆文件: {}, 热签名≈{}条", resolvedFile, sigToMenuVotes.size());
        } catch (Exception e) {
            log.warn("[QywxCloneLearning] 初始化失败，将仅内存运行: {}", e.getMessage());
            resolvedFile = null;
        }
    }

    public Map<String, Object> memoryStats() {
        synchronized (lock) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("file", resolvedFile != null ? resolvedFile.toString() : "");
            m.put("signatures", sigToMenuVotes.size());
            m.put("branchHeavyRuns", branchHeavyRuns);
            return m;
        }
    }

    /**
     * 在规则推断之后调用：若有历史票数，则选出与当前签名最契合的菜单项。
     */
    public String biasMenuChoice(String panelTitle, String panelDigest, String ruleBasedMenu) {
        if (!learningEnabled()) {
            return ruleBasedMenu;
        }
        String sig = stableSignature(panelTitle, panelDigest);
        synchronized (lock) {
            Map<String, AtomicInteger> votes = sigToMenuVotes.get(sig);
            if (votes == null || votes.isEmpty()) {
                return ruleBasedMenu;
            }
            String best = votes.entrySet().stream()
                    .max(Comparator.comparingInt(e -> e.getValue().get()))
                    .map(Map.Entry::getKey)
                    .orElse(ruleBasedMenu);
            if (best != null && !best.equals(ruleBasedMenu)) {
                log.debug("[QywxCloneLearning] 签名={} 规则={} -> 记忆偏向={}", abbreviated(sig), ruleBasedMenu, best);
            }
            return best != null ? best : ruleBasedMenu;
        }
    }

    /**
     * 回放单步结束后更新（成功 +1 对应菜单）。
     */
    public void recordReplayStep(String panelTitle, String panelDigest, String chosenMenu, boolean ok) {
        if (!learningEnabled() || chosenMenu == null || chosenMenu.isBlank()) {
            return;
        }
        if (!ok) {
            return;
        }
        String sig = stableSignature(panelTitle, panelDigest);
        synchronized (lock) {
            sigToMenuVotes.computeIfAbsent(sig, s -> new LinkedHashMap<>())
                    .computeIfAbsent(chosenMenu, m -> new AtomicInteger(0))
                    .incrementAndGet();
            persistLocked();
        }
    }

    /**
     * 整图维度：若画布上存在较多分支/循环边，累计一次「复杂图」计数，避免误以为是线性可遍历。
     */
    public void observeGraphHints(int branchPathCount, int loopPathCount) {
        if (!learningEnabled()) {
            return;
        }
        if (branchPathCount > 0 || loopPathCount > 0) {
            synchronized (lock) {
                branchHeavyRuns++;
                persistLocked();
            }
        }
    }

    /**
     * 人工纠偏：payload 示例 {@code [{ "panelTitle":"...", "panelDigestPrefix":"...", "correctMenu":"HTTP请求" }]}
     */
    public void mergeFeedback(List<Map<String, Object>> corrections) {
        if (!learningEnabled() || corrections == null || corrections.isEmpty()) {
            return;
        }
        synchronized (lock) {
            for (Map<String, Object> row : corrections) {
                if (row == null) {
                    continue;
                }
                String title = String.valueOf(row.getOrDefault("panelTitle", ""));
                String dig = String.valueOf(row.getOrDefault("panelDigest", row.getOrDefault("panelDigestPrefix", "")));
                String menu = String.valueOf(row.getOrDefault("correctMenu", ""));
                if (menu.isBlank()) {
                    continue;
                }
                String sig = stableSignature(title, dig);
                Map<String, AtomicInteger> votes = sigToMenuVotes.computeIfAbsent(sig, s -> new LinkedHashMap<>());
                votes.computeIfAbsent(menu, m -> new AtomicInteger(0)).addAndGet(FEEDBACK_WEIGHT);
            }
            persistLocked();
        }
    }

    public long getBranchHeavyRuns() {
        return branchHeavyRuns;
    }

    private boolean learningEnabled() {
        EngineProperties.QyWeixinConfig cfg = engineProperties.getQyweixin();
        return cfg == null || cfg.isCloneLearningEnabled();
    }

    private void loadFromDisk() {
        if (resolvedFile == null || !Files.isRegularFile(resolvedFile)) {
            return;
        }
        try {
            String raw = Files.readString(resolvedFile, StandardCharsets.UTF_8);
            if (raw.isBlank()) {
                return;
            }
            JSONObject root = JSON.parseObject(raw);
            if (root == null) {
                return;
            }
            branchHeavyRuns = root.getLongValue("branchHeavyRuns");
            JSONObject votesObj = root.getJSONObject("sigToMenuVotes");
            if (votesObj != null) {
                for (String sig : votesObj.keySet()) {
                    JSONObject menus = votesObj.getJSONObject(sig);
                    if (menus == null) {
                        continue;
                    }
                    Map<String, AtomicInteger> inner = new LinkedHashMap<>();
                    for (String m : menus.keySet()) {
                        inner.put(m, new AtomicInteger(menus.getIntValue(m)));
                    }
                    sigToMenuVotes.put(sig, inner);
                }
            }
        } catch (Exception e) {
            log.warn("[QywxCloneLearning] 读取失败: {}", e.getMessage());
        }
    }

    private void persistLocked() {
        if (resolvedFile == null) {
            return;
        }
        try {
            JSONObject root = new JSONObject();
            root.put("version", VERSION);
            root.put("branchHeavyRuns", branchHeavyRuns);
            root.put("updatedAt", System.currentTimeMillis());
            JSONObject votesRoot = new JSONObject();
            for (Map.Entry<String, Map<String, AtomicInteger>> e : sigToMenuVotes.entrySet()) {
                JSONObject inner = new JSONObject();
                for (Map.Entry<String, AtomicInteger> v : e.getValue().entrySet()) {
                    inner.put(v.getKey(), v.getValue().get());
                }
                votesRoot.put(e.getKey(), inner);
            }
            root.put("sigToMenuVotes", votesRoot);
            Files.writeString(resolvedFile, JSON.toJSONString(root),
                    StandardCharsets.UTF_8);
        } catch (IOException e) {
            log.warn("[QywxCloneLearning] 落盘失败: {}", e.getMessage());
        }
    }

    static String stableSignature(String panelTitle, String panelDigest) {
        String t = panelTitle == null ? "" : panelTitle.replace('\n', ' ').trim().toLowerCase();
        if (t.length() > 160) {
            t = t.substring(0, 160);
        }
        String d = panelDigest == null ? "" : panelDigest.replace('\n', ' ').trim().toLowerCase();
        if (d.length() > 96) {
            d = d.substring(0, 96);
        }
        return t + "::" + d;
    }

    private static String abbreviated(String s) {
        if (s == null) {
            return "";
        }
        return s.length() > 56 ? s.substring(0, 56) + "…" : s;
    }
}

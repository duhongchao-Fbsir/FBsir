package com.wx.fbsir.engine.playwright.util;

import com.alibaba.fastjson2.JSON;
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
import java.nio.file.StandardOpenOption;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 企微工作流自动化：将单次执行结果以一行 JSON 追加落盘（JSON Lines），
 * 便于本地直接用脚本 <code>tail -f</code> / jq 分析，无需从 Admin 复制返回值。
 */
@Component
public class QyWeixinAutomationRunRecorder {

    private static final Logger log = LoggerFactory.getLogger(QyWeixinAutomationRunRecorder.class);

    private final EngineProperties engineProperties;

    private volatile Path resolvedFile;
    private final Object writeLock = new Object();

    public QyWeixinAutomationRunRecorder(EngineProperties engineProperties) {
        this.engineProperties = engineProperties;
    }

    @PostConstruct
    public void init() {
        try {
            String p = engineProperties.getQyweixin() != null
                    ? engineProperties.getQyweixin().getAutomationLogFile()
                    : "logs/qyweixin-automation.jsonl";
            resolvedFile = Paths.get(p).toAbsolutePath().normalize();
            Path parent = resolvedFile.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            log.info("[QywxAutomationLog] 落盘路径: {}", resolvedFile);
        } catch (Exception e) {
            log.warn("[QywxAutomationLog] 初始化路径失败，将跳过落盘: {}", e.getMessage());
            resolvedFile = null;
        }
    }

    /**
     * 追加一条 JSON 记录（线程安全）。
     *
     * @param capabilityType {@code QYWEIXIN_WORKFLOW_IMPORT_DRAFT} 等
     * @param userId         用户
     * @param requestId      请求 id（无则为 null）
     * @param data           与返回给 Admin 一致或为其子集
     */
    public void append(String capabilityType, String userId, String requestId, Map<String, Object> data) {
        EngineProperties.QyWeixinConfig cfg = engineProperties.getQyweixin();
        if (cfg == null || !cfg.isAutomationLogEnabled()) {
            return;
        }
        Path f = resolvedFile;
        if (f == null) {
            return;
        }
        Map<String, Object> line = new LinkedHashMap<>();
        line.put("ts", System.currentTimeMillis());
        line.put("engineHostId", engineProperties.getHostId());
        line.put("engineVersion", engineProperties.getVersion());
        line.put("type", capabilityType);
        line.put("userId", userId);
        line.put("requestId", requestId);
        line.put("data", data != null ? data : Map.of());

        String json = JSON.toJSONString(line);
        synchronized (writeLock) {
            try {
                Files.writeString(
                        f,
                        json + System.lineSeparator(),
                        StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE,
                        StandardOpenOption.APPEND
                );
            } catch (IOException e) {
                log.warn("[QywxAutomationLog] 写入失败: {}", e.getMessage());
            }
        }
    }
}

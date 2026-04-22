package com.wx.fbsir.engine.capability;

import com.alibaba.fastjson2.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * AI能力规约器（兼容层）
 * <p>
 * 兼容两类协议：
 * 1) 旧版扁平字段（enableXxx / uploadedFileUrl / repositoryName）
 * 2) 规范化字段（capabilities / conversationProfile / providerOptions）
 */
public final class CapabilityNormalizer {

    private static final Set<String> DEEPSEEK_OPTION_IDS = Set.of(
        "enableDeepThinking", "enableWebSearch", "enableFileUpload", "enableFastMode", "enableExpertMode"
    );
    private static final Set<String> GITEE_OPTION_IDS = Set.of(
        "openSourceExploration", "repositoryQA", "helpCenter", "enableFileUpload"
    );
    private static final Set<String> DOUBAO_OPTION_IDS = Set.of(
        "enableDeepThinking", "enableFastMode", "enableExpertMode", "enableFileUpload"
    );
    private static final Set<String> FILE_ONLY_OPTION_IDS = Set.of("enableFileUpload");

    private CapabilityNormalizer() {
    }

    public static NormalizedCapabilities normalize(String aiType, JSONObject payload) {
        String normalizedAiType = aiType == null ? "" : aiType.trim().toLowerCase(Locale.ROOT);
        JSONObject capabilities = payload != null ? payload.getJSONObject("capabilities") : null;
        JSONObject providerOptions = payload != null ? payload.getJSONObject("providerOptions") : null;
        JSONObject fileUpload = capabilities != null ? capabilities.getJSONObject("fileUpload") : null;

        boolean reasoning = readBool(
            capabilities, "reasoning",
            payload != null && payload.getBooleanValue("enableDeepThinking", false)
        );
        boolean webSearch = readBool(
            capabilities, "webSearch",
            payload != null && payload.getBooleanValue("enableWebSearch", false)
        );
        boolean uploadEnabled = readBool(
            fileUpload, "enabled",
            payload != null && payload.getBooleanValue("enableFileUpload", false)
        );
        String uploadUrl = readString(
            fileUpload, "url",
            payload != null ? payload.getString("uploadedFileUrl") : null
        );
        String conversationProfile = payload != null ? payload.getString("conversationProfile") : null;
        if (conversationProfile == null || conversationProfile.isBlank()) {
            conversationProfile = providerOptions != null ? providerOptions.getString("conversationProfile") : null;
        }

        boolean fastMode = payload != null && payload.getBooleanValue("enableFastMode", false);
        boolean expertMode = payload != null && payload.getBooleanValue("enableExpertMode", false);

        boolean openSourceExploration = payload != null && payload.getBooleanValue("openSourceExploration", false);
        boolean repositoryQa = payload != null && payload.getBooleanValue("repositoryQA", false);
        boolean helpCenter = payload != null && payload.getBooleanValue("helpCenter", false);
        String repositoryName = payload != null ? payload.getString("repositoryName") : null;
        if ((repositoryName == null || repositoryName.isBlank()) && providerOptions != null) {
            repositoryName = providerOptions.getString("repositoryName");
        }

        if ("deepseek".equals(normalizedAiType) && conversationProfile != null && !conversationProfile.isBlank()) {
            switch (conversationProfile) {
                case "fast":
                    fastMode = true;
                    expertMode = false;
                    break;
                case "expert":
                    expertMode = true;
                    fastMode = false;
                    break;
                case "deepThinking+webSearch":
                    reasoning = true;
                    webSearch = true;
                    break;
                case "deepThinking":
                    reasoning = true;
                    break;
                case "webSearch":
                    webSearch = true;
                    break;
                default:
                    break;
            }
        }
        // 豆包前端不提供联网；profile 中若含 webSearch 仅映射为深度思考，避免 mode 字符串出现未执行的联网语义
        if ("doubao".equals(normalizedAiType) && conversationProfile != null && !conversationProfile.isBlank()) {
            switch (conversationProfile) {
                case "fast":
                    fastMode = true;
                    expertMode = false;
                    break;
                case "expert":
                    expertMode = true;
                    fastMode = false;
                    break;
                case "deepThinking":
                case "deepThinking+webSearch":
                    reasoning = true;
                    break;
                case "webSearch":
                    // 无对应 UI，忽略联网标志
                    break;
                default:
                    break;
            }
        }
        if ("gitee".equals(normalizedAiType) && conversationProfile != null && !conversationProfile.isBlank()) {
            openSourceExploration = "openSourceExploration".equals(conversationProfile);
            repositoryQa = "repositoryQA".equals(conversationProfile);
            helpCenter = "helpCenter".equals(conversationProfile);
        }

        if (fastMode && expertMode) {
            fastMode = false;
        }

        String deepseekMode = "normal";
        if (fastMode) {
            deepseekMode = "fast";
        } else if (expertMode) {
            deepseekMode = "expert";
        } else if (reasoning && webSearch) {
            deepseekMode = "deepThinking+webSearch";
        } else if (reasoning) {
            deepseekMode = "deepThinking";
        } else if (webSearch) {
            deepseekMode = "webSearch";
        }

        String giteeMode = "normal";
        if (openSourceExploration) {
            giteeMode = "openSourceExploration";
        } else if (repositoryQa) {
            giteeMode = "repositoryQA";
        } else if (helpCenter) {
            giteeMode = "helpCenter";
        }

        List<String> unsupportedOptionIds = collectUnsupportedOptionIds(normalizedAiType, payload);
        return new NormalizedCapabilities(
            reasoning,
            webSearch,
            uploadEnabled,
            uploadUrl,
            fastMode,
            expertMode,
            deepseekMode,
            openSourceExploration,
            repositoryQa,
            helpCenter,
            giteeMode,
            repositoryName,
            conversationProfile,
            unsupportedOptionIds
        );
    }

    private static List<String> collectUnsupportedOptionIds(String aiType, JSONObject payload) {
        List<String> unsupported = new ArrayList<>();
        if (payload == null) {
            return unsupported;
        }
        Set<String> supported;
        if ("deepseek".equals(aiType)) {
            supported = DEEPSEEK_OPTION_IDS;
        } else if ("doubao".equals(aiType)) {
            supported = DOUBAO_OPTION_IDS;
        } else if ("gitee".equals(aiType)) {
            supported = GITEE_OPTION_IDS;
        } else {
            supported = FILE_ONLY_OPTION_IDS;
        }
        for (String key : payload.keySet()) {
            if (!isOptionKey(key) || supported.contains(key)) {
                continue;
            }
            if (payload.getBooleanValue(key, false)) {
                unsupported.add(key);
            }
        }
        return unsupported;
    }

    private static boolean isOptionKey(String key) {
        return "enableDeepThinking".equals(key)
            || "enableWebSearch".equals(key)
            || "enableFileUpload".equals(key)
            || "enableFastMode".equals(key)
            || "enableExpertMode".equals(key)
            || "openSourceExploration".equals(key)
            || "repositoryQA".equals(key)
            || "helpCenter".equals(key);
    }

    private static boolean readBool(JSONObject source, String key, boolean fallback) {
        if (source == null) {
            return fallback;
        }
        Object value = source.get(key);
        if (value == null) {
            return fallback;
        }
        if (value instanceof Boolean) {
            return (Boolean) value;
        }
        return source.getBooleanValue(key, fallback);
    }

    private static String readString(JSONObject source, String key, String fallback) {
        if (source == null) {
            return fallback;
        }
        String value = source.getString(key);
        return value != null ? value : fallback;
    }

    public static final class NormalizedCapabilities {
        private final boolean reasoning;
        private final boolean webSearch;
        private final boolean fileUploadEnabled;
        private final String fileUploadUrl;
        private final boolean fastMode;
        private final boolean expertMode;
        private final String deepseekMode;
        private final boolean openSourceExploration;
        private final boolean repositoryQa;
        private final boolean helpCenter;
        private final String giteeMode;
        private final String repositoryName;
        private final String conversationProfile;
        private final List<String> unsupportedOptionIds;

        private NormalizedCapabilities(
            boolean reasoning,
            boolean webSearch,
            boolean fileUploadEnabled,
            String fileUploadUrl,
            boolean fastMode,
            boolean expertMode,
            String deepseekMode,
            boolean openSourceExploration,
            boolean repositoryQa,
            boolean helpCenter,
            String giteeMode,
            String repositoryName,
            String conversationProfile,
            List<String> unsupportedOptionIds
        ) {
            this.reasoning = reasoning;
            this.webSearch = webSearch;
            this.fileUploadEnabled = fileUploadEnabled;
            this.fileUploadUrl = fileUploadUrl;
            this.fastMode = fastMode;
            this.expertMode = expertMode;
            this.deepseekMode = deepseekMode;
            this.openSourceExploration = openSourceExploration;
            this.repositoryQa = repositoryQa;
            this.helpCenter = helpCenter;
            this.giteeMode = giteeMode;
            this.repositoryName = repositoryName;
            this.conversationProfile = conversationProfile;
            this.unsupportedOptionIds = unsupportedOptionIds;
        }

        public boolean isReasoning() {
            return reasoning;
        }

        public boolean isWebSearch() {
            return webSearch;
        }

        public boolean isFileUploadEnabled() {
            return fileUploadEnabled;
        }

        public String getFileUploadUrl() {
            return fileUploadUrl;
        }

        public boolean isFastMode() {
            return fastMode;
        }

        public boolean isExpertMode() {
            return expertMode;
        }

        public String getDeepseekMode() {
            return deepseekMode;
        }

        public boolean isOpenSourceExploration() {
            return openSourceExploration;
        }

        public boolean isRepositoryQa() {
            return repositoryQa;
        }

        public boolean isHelpCenter() {
            return helpCenter;
        }

        public String getGiteeMode() {
            return giteeMode;
        }

        public String getRepositoryName() {
            return repositoryName;
        }

        public String getConversationProfile() {
            return conversationProfile;
        }

        public List<String> getUnsupportedOptionIds() {
            return unsupportedOptionIds;
        }
    }
}

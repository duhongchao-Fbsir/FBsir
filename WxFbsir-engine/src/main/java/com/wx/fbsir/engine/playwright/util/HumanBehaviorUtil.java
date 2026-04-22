package com.wx.fbsir.engine.playwright.util;

import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.options.BoundingBox;
import com.microsoft.playwright.options.WaitForSelectorState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Random;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 自然人浏览行为工具类
 *
 * ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
 * 设计目标
 * ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
 *
 * 模拟真实用户的浏览行为，规避企业微信等平台的自动化检测机制：
 *
 * 1. 随机延迟          — 所有操作间插入符合正态分布的随机等待
 * 2. 贝塞尔曲线鼠标移动  — 鼠标沿曲线轨迹移动，而非直线跳转
 * 3. 偏移点击          — 点击目标元素内随机偏移位置
 * 4. 人工化滚动        — 分段滚动，每段带随机速度和停顿
 * 5. 逐字输入          — 带随机间隔的字符级键盘输入（支持中文）
 * 6. 悬停预热          — 点击前先悬停片刻，模拟人眼定位
 *
 * ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
 * 使用方式
 * ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
 *
 * <pre>
 * {@code
 * @Autowired
 * private HumanBehaviorUtil human;
 *
 * // 自然点击某个元素
 * human.naturalClick(page, page.locator(".create_btn"));
 *
 * // 自然点击并等待
 * human.naturalClick(page, page.locator(".tab-item"), 800);
 *
 * // 逐字输入
 * human.humanType(page, page.locator("input"), "你好世界");
 *
 * // 随机滚动
 * human.naturalScroll(page, 300, 800);
 *
 * // 随机思考停顿（模拟用户阅读）
 * human.think(page, 1000, 2000);
 * }
 * </pre>
 *
 * @author wxfbsir
 */
@Component
public class HumanBehaviorUtil {

    private static final Logger log = LoggerFactory.getLogger(HumanBehaviorUtil.class);

    // =========================================================================
    // 常量：延迟范围（ms）
    // =========================================================================

    /** 极短停顿：操作之间的最小间隙 */
    private static final int MICRO_MIN = 80;
    private static final int MICRO_MAX = 200;

    /** 短停顿：元素定位、轻点 */
    private static final int SHORT_MIN = 200;
    private static final int SHORT_MAX = 600;

    /** 中停顿：页面切换后等待渲染 */
    private static final int MEDIUM_MIN = 600;
    private static final int MEDIUM_MAX = 1400;

    /** 长停顿：模拟用户阅读内容 */
    private static final int LONG_MIN = 1200;
    private static final int LONG_MAX = 3000;

    /** 鼠标移动每步间隔（ms） */
    private static final int MOUSE_STEP_DELAY = 12;

    /** 鼠标移动步数（越多越平滑） */
    private static final int MOUSE_STEPS = 20;

    // =========================================================================
    // 随机延迟
    // =========================================================================

    /**
     * 极短停顿（80~200ms）：紧密操作之间的最小间隙
     */
    public void micro(Page page) {
        sleep(page, rand(MICRO_MIN, MICRO_MAX));
    }

    /**
     * 短停顿（200~600ms）：轻点、菜单展开后等待
     */
    public void shortPause(Page page) {
        sleep(page, rand(SHORT_MIN, SHORT_MAX));
    }

    /**
     * 中停顿（600~1400ms）：页面切换/内容加载后等待
     */
    public void mediumPause(Page page) {
        sleep(page, rand(MEDIUM_MIN, MEDIUM_MAX));
    }

    /**
     * 长停顿（1200~3000ms）：模拟用户阅读内容、思考
     */
    public void think(Page page) {
        sleep(page, rand(LONG_MIN, LONG_MAX));
    }

    /**
     * 自定义范围停顿
     *
     * @param minMs 最小毫秒
     * @param maxMs 最大毫秒
     */
    public void think(Page page, int minMs, int maxMs) {
        sleep(page, rand(minMs, maxMs));
    }

    // =========================================================================
    // 鼠标移动（贝塞尔曲线轨迹）
    // =========================================================================

    /**
     * 将鼠标沿贝塞尔曲线轨迹移动到目标坐标
     * 相比直线移动更接近真实人类操作
     *
     * @param page   页面
     * @param targetX 目标 X
     * @param targetY 目标 Y
     */
    public void moveMouse(Page page, double targetX, double targetY) {
        try {
            // 获取当前鼠标位置（近似为页面中心，避免跨度过大）
            double startX = targetX + randDouble(-150, 150);
            double startY = targetY + randDouble(-100, 100);

            // 贝塞尔曲线控制点（随机偏移制造弧度）
            double cp1x = startX + (targetX - startX) * 0.3 + randDouble(-60, 60);
            double cp1y = startY + (targetY - startY) * 0.3 + randDouble(-60, 60);
            double cp2x = startX + (targetX - startX) * 0.7 + randDouble(-40, 40);
            double cp2y = startY + (targetY - startY) * 0.7 + randDouble(-40, 40);

            for (int i = 0; i <= MOUSE_STEPS; i++) {
                double t = (double) i / MOUSE_STEPS;
                double x = bezier(startX, cp1x, cp2x, targetX, t);
                double y = bezier(startY, cp1y, cp2y, targetY, t);
                page.mouse().move(x, y);
                // 每步间隔带微小随机抖动
                page.waitForTimeout(MOUSE_STEP_DELAY + rand(0, 8));
            }
        } catch (Exception e) {
            log.debug("[HumanBehavior] 鼠标移动异常: {}", e.getMessage());
            // 降级：直接移动
            try {
                page.mouse().move(targetX, targetY);
            } catch (Exception ignored) {}
        }
    }

    // =========================================================================
    // 点击（带悬停预热 + 随机偏移）
    // =========================================================================

    /**
     * 自然点击元素（贝塞尔移动 → 悬停 → 点击随机偏移位置）
     *
     * @param page    页面
     * @param locator 目标元素
     */
    public boolean naturalClick(Page page, Locator locator) {
        return naturalClick(page, locator, 0);
    }

    /**
     * 自然点击元素，点击后等待指定毫秒
     *
     * @param page         页面
     * @param locator      目标元素
     * @param afterDelayMs 点击后额外等待（0=不等待）
     */
    public boolean naturalClick(Page page, Locator locator, int afterDelayMs) {
        try {
            // 等待元素可见
            locator.first().waitFor(new Locator.WaitForOptions()
                    .setState(WaitForSelectorState.VISIBLE)
                    .setTimeout(8000));

            BoundingBox box = locator.first().boundingBox();
            if (box == null) {
                log.warn("[HumanBehavior] 元素无 BoundingBox，降级直接点击");
                locator.first().click();
                return true;
            }

            // 随机点击位置（在元素内随机偏移，避免总点中心）
            double offsetX = randDouble(box.width * 0.2, box.width * 0.8);
            double offsetY = randDouble(box.height * 0.2, box.height * 0.8);
            double clickX = box.x + offsetX;
            double clickY = box.y + offsetY;

            // 贝塞尔移动鼠标到元素附近
            moveMouse(page, clickX, clickY);

            // 悬停预热（50~180ms，模拟眼睛定位）
            page.waitForTimeout(rand(50, 180));

            // 点击
            page.mouse().click(clickX, clickY);
            log.debug("[HumanBehavior] 自然点击 ({:.0f}, {:.0f})", clickX, clickY);

            if (afterDelayMs > 0) {
                sleep(page, afterDelayMs);
            }
            return true;

        } catch (Exception e) {
            log.warn("[HumanBehavior] 自然点击失败，降级: {}", e.getMessage());
            try {
                locator.first().click();
                return true;
            } catch (Exception e2) {
                log.error("[HumanBehavior] 降级点击也失败: {}", e2.getMessage());
                return false;
            }
        }
    }

    /**
     * 通过文本自然点击页面元素
     *
     * @param page         页面
     * @param text         目标文本（精确匹配）
     * @param description  操作描述（用于日志）
     * @param afterDelayMs 点击后等待 ms
     * @return 是否成功
     */
    public boolean naturalClickByText(Page page, String text, String description, int afterDelayMs) {
        try {
            log.info("[HumanBehavior] 通过文本点击: {} → '{}'", description, text);
            Locator el = page.getByText(text, new Page.GetByTextOptions().setExact(true));
            if (el.count() == 0) {
                log.warn("[HumanBehavior] 未找到文本: '{}'", text);
                return false;
            }
            return naturalClick(page, el, afterDelayMs);
        } catch (Exception e) {
            log.error("[HumanBehavior] 文本点击失败 '{}': {}", text, e.getMessage());
            return false;
        }
    }

    /**
     * 通过 CSS 选择器自然点击
     *
     * @param page         页面
     * @param selector     CSS 选择器
     * @param description  操作描述（用于日志）
     * @param afterDelayMs 点击后等待 ms
     * @return 是否成功
     */
    public boolean naturalClickBySelector(Page page, String selector, String description, int afterDelayMs) {
        try {
            log.info("[HumanBehavior] 通过选择器点击: {} → '{}'", description, selector);
            Locator el = page.locator(selector);
            if (el.count() == 0) {
                log.warn("[HumanBehavior] 选择器无匹配: '{}'", selector);
                return false;
            }
            return naturalClick(page, el.first(), afterDelayMs);
        } catch (Exception e) {
            log.error("[HumanBehavior] 选择器点击失败 '{}': {}", selector, e.getMessage());
            return false;
        }
    }

    // =========================================================================
    // 滚动（分段式，随机速度）
    // =========================================================================

    /**
     * 自然滚动页面（分多段，每段带随机速度和停顿）
     *
     * @param page         页面
     * @param totalPx      总滚动像素（正向下，负向上）
     * @param durationMs   总时长目标（ms）
     */
    public void naturalScroll(Page page, double totalPx, int durationMs) {
        try {
            int segments = 5 + rand(0, 5);  // 5~10 段
            double perStep = totalPx / segments;
            int perDelay = durationMs / segments;

            for (int i = 0; i < segments; i++) {
                // 每段滚动量加随机扰动
                double stepPx = perStep * (0.7 + randDouble(0, 0.6));
                page.mouse().wheel(0, stepPx);
                // 每段停顿带随机
                page.waitForTimeout(perDelay + rand(-50, 100));
            }
            log.debug("[HumanBehavior] 自然滚动完成: {}px / {}ms", totalPx, durationMs);
        } catch (Exception e) {
            log.debug("[HumanBehavior] 滚动异常: {}", e.getMessage());
        }
    }

    /**
     * 将指定元素滚动入视图（模拟人向下翻找到目标）
     */
    public void scrollIntoView(Page page, Locator locator) {
        try {
            locator.first().scrollIntoViewIfNeeded();
            shortPause(page);
        } catch (Exception e) {
            log.debug("[HumanBehavior] scrollIntoView 失败: {}", e.getMessage());
        }
    }

    // =========================================================================
    // 输入（逐字 + 随机间隔）
    // =========================================================================

    /**
     * 模拟人工逐字输入（含中文）
     * 先清空输入框，再逐字输入，每字之间随机间隔 40~150ms
     *
     * @param page    页面
     * @param locator 输入框
     * @param text    要输入的文本
     */
    public void humanType(Page page, Locator locator, String text) {
        try {
            naturalClick(page, locator, 100);
            locator.first().clear();
            micro(page);

            for (int i = 0; i < text.length(); i++) {
                char c = text.charAt(i);
                // 中文及特殊字符走 fill 追加方式（直接 type 中文可能乱码）
                String current = text.substring(0, i + 1);
                locator.first().fill(current);
                // 逐字间隔：40~150ms，偶尔长停顿模拟思考
                int delay = rand(40, 150);
                if (i > 0 && rand(0, 10) == 0) {
                    delay += rand(300, 800);  // 约 10% 概率出现停顿
                }
                page.waitForTimeout(delay);
            }

            log.debug("[HumanBehavior] 逐字输入完成，长度={}", text.length());
        } catch (Exception e) {
            log.warn("[HumanBehavior] 逐字输入失败，降级 fill: {}", e.getMessage());
            try {
                locator.first().fill(text);
            } catch (Exception ignored) {}
        }
    }

    // =========================================================================
    // 页面预热（页面加载后的自然浏览行为）
    // =========================================================================

    /**
     * 页面加载后的自然预热：随机滚动 + 鼠标漫游 + 停顿
     * 模拟用户打开页面后浏览内容的行为
     *
     * @param page       页面
     * @param intensity  强度：LOW（轻预热）/ MEDIUM / HIGH（深度模拟）
     */
    public void warmUp(Page page, Intensity intensity) {
        try {
            switch (intensity) {
                case LOW -> {
                    // 仅停顿 + 小滚动
                    think(page, 500, 1200);
                    naturalScroll(page, rand(100, 300), 800);
                    think(page, 300, 700);
                }
                case MEDIUM -> {
                    // 停顿 + 滚动 + 鼠标漫游
                    think(page, 800, 1800);
                    naturalScroll(page, rand(200, 500), 1000);
                    int w = 1280, h = 800;
                    moveMouse(page, randDouble(200, w - 200), randDouble(200, h - 200));
                    think(page, 400, 1000);
                    moveMouse(page, randDouble(100, w - 100), randDouble(100, h - 100));
                    shortPause(page);
                }
                case HIGH -> {
                    // 完整模拟：多次滚动 + 多次鼠标移动 + 思考停顿
                    think(page, 1000, 2500);
                    naturalScroll(page, rand(200, 400), 1000);
                    think(page, 500, 1200);
                    int w = 1280, h = 800;
                    moveMouse(page, randDouble(300, w - 300), randDouble(200, h - 200));
                    micro(page);
                    moveMouse(page, randDouble(100, w / 2), randDouble(100, h / 2));
                    naturalScroll(page, rand(-200, -50), 600);
                    think(page, 600, 1500);
                }
            }
        } catch (Exception e) {
            log.debug("[HumanBehavior] warmUp 异常: {}", e.getMessage());
        }
    }

    // =========================================================================
    // 导航辅助
    // =========================================================================

    /**
     * 自然导航到 URL（navigate 后执行 warmUp）
     *
     * @param page      页面
     * @param url       目标 URL
     * @param intensity 预热强度
     */
    public void naturalNavigate(Page page, String url, Intensity intensity) {
        try {
            log.info("[HumanBehavior] 导航到: {}", url);
            page.navigate(url);
            page.waitForLoadState();
            warmUp(page, intensity);
        } catch (Exception e) {
            log.error("[HumanBehavior] 导航失败: {}", e.getMessage());
            throw e;
        }
    }

    /**
     * 等待并自然点击 Tab 菜单项（用于企业微信侧边栏 tab 切换）
     *
     * @param page       页面
     * @param tabText    tab 文字
     * @param afterWait  点击后等待 ms（等待内容加载）
     * @return 是否成功
     */
    public boolean clickTab(Page page, String tabText, int afterWait) {
        log.info("[HumanBehavior] 点击 Tab: '{}'", tabText);
        // 先让鼠标漫游到左侧区域，模拟视线移动
        try {
            moveMouse(page, randDouble(100, 250), randDouble(200, 500));
            micro(page);
        } catch (Exception ignored) {}

        boolean clicked = naturalClickByText(page, tabText, "Tab-" + tabText, afterWait);
        if (clicked) {
            log.info("[HumanBehavior] Tab '{}' 点击成功，等待 {}ms", tabText, afterWait);
        }
        return clicked;
    }

    // =========================================================================
    // 预热强度枚举
    // =========================================================================

    public enum Intensity {
        /** 轻预热：快速跑过，适合后台脚本 */
        LOW,
        /** 中度预热：带滚动和鼠标移动，适合一般场景 */
        MEDIUM,
        /** 深度预热：完整模拟阅读行为，适合反检测要求高的场景 */
        HIGH
    }

    // =========================================================================
    // 私有工具方法
    // =========================================================================

    private void sleep(Page page, long ms) {
        try {
            page.waitForTimeout(ms);
        } catch (Exception e) {
            // page 可能已关闭，静默处理
            log.trace("[HumanBehavior] sleep 异常: {}", e.getMessage());
        }
    }

    private int rand(int min, int max) {
        return ThreadLocalRandom.current().nextInt(min, max + 1);
    }

    private double randDouble(double min, double max) {
        return min + ThreadLocalRandom.current().nextDouble() * (max - min);
    }

    /**
     * 三次贝塞尔曲线插值
     * B(t) = (1-t)³P0 + 3(1-t)²tP1 + 3(1-t)t²P2 + t³P3
     */
    private double bezier(double p0, double p1, double p2, double p3, double t) {
        double mt = 1 - t;
        return mt * mt * mt * p0
             + 3 * mt * mt * t * p1
             + 3 * mt * t * t * p2
             + t * t * t * p3;
    }
}

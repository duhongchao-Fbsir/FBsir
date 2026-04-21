-- =========================
-- 输出物模块权限
-- 说明：
-- 1. 权限采用 business:output:xxx 三段式命名
-- 2. menu_type = 'F' 表示按钮权限
-- 3. parent_id：挂到「AI助手」菜单（wxfbsir.sql 中 menu_id=129），与 AIGC 输出物能力一致
-- =========================

-- 生成输出物（order_num 与基线 1097/1098 的 1、2 错开，使用 3～7）
insert into sys_menu values('1526', '生成输出物', '129', '3', '', '', '', '', 1, 0, 'F', '0', '0', 'business:output:generate', '#', 'admin', sysdate(), '', null, '生成输出物权限');

-- 导出Markdown
insert into sys_menu values('1527', '导出Markdown', '129', '4', '', '', '', '', 1, 0, 'F', '0', '0', 'business:output:exportMarkdown', '#', 'admin', sysdate(), '', null, '导出Markdown权限');

-- 导出JSON
insert into sys_menu values('1528', '导出JSON', '129', '5', '', '', '', '', 1, 0, 'F', '0', '0', 'business:output:exportJson', '#', 'admin', sysdate(), '', null, '导出JSON权限');

-- 推送Webhook
insert into sys_menu values('1529', '推送Webhook', '129', '6', '', '', '', '', 1, 0, 'F', '0', '0', 'business:output:pushWebhook', '#', 'admin', sysdate(), '', null, '推送Webhook权限');

-- 保存输出物
insert into sys_menu values('1530', '保存输出物', '129', '7', '', '', '', '', 1, 0, 'F', '0', '0', 'business:output:save', '#', 'admin', sysdate(), '', null, '保存输出物权限');
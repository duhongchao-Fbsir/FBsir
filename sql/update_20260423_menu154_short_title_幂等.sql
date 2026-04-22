-- 侧栏菜单 154：缩短名称，避免窄侧栏显示为「企微智能体工...」；remark 保留全称说明
-- 执行：mysql -h127.0.0.1 -P3306 -uroot -p wxfbsir < sql/update_20260423_menu154_short_title_幂等.sql

UPDATE `sys_menu`
SET `menu_name` = '企微工作流',
    `remark` = '企业微信智能机器人/工作流实验页（原「企微智能体工作流」；Engine QYWEIXIN_*）'
WHERE `menu_id` = '154';

# -*- coding: utf-8 -*-
"""Hermes 纳管联通验证：更新白名单 URL、模拟 Admin HTTP 健康检查逻辑、回写 online_status。"""
import urllib.request

import pymysql

HEALTH_URL = "http://127.0.0.1:8642/health"
HOST_ID = "hermes-verify-20260414"


def main() -> None:
    conn = pymysql.connect(
        host="127.0.0.1",
        port=3306,
        user="root",
        password="root",
        database="wxfbsir",
        charset="utf8mb4",
    )
    try:
        with conn.cursor() as cur:
            cur.execute(
                "UPDATE ws_host_whitelist SET health_check_url=%s WHERE host_id=%s",
                (HEALTH_URL, HOST_ID),
            )
            conn.commit()

            cur.execute(
                "SELECT id, host_id, host_type, health_check_url, online_status "
                "FROM ws_host_whitelist WHERE host_id=%s",
                (HOST_ID,),
            )
            row = cur.fetchone()
            print("1) 白名单记录:", row)
            if not row:
                print("未找到 host_id，请先插入白名单行。")
                return

            # 与 OpenClawHealthChecker / manualHealthCheck 一致：GET、2xx -> online
            req = urllib.request.Request(HEALTH_URL, method="GET")
            try:
                with urllib.request.urlopen(req, timeout=6) as resp:
                    code = resp.getcode()
                    body = resp.read(500).decode("utf-8", errors="replace")
            except Exception as e:
                print("2) HTTP 健康检查失败:", e)
                new_status = "offline"
            else:
                new_status = "online" if 200 <= code < 300 else "offline"
                print("2) HTTP 响应:", code, body[:200])

            cur.execute(
                "UPDATE ws_host_whitelist SET online_status=%s, update_time=NOW() WHERE host_id=%s",
                (new_status, HOST_ID),
            )
            conn.commit()

            cur.execute(
                "SELECT id, host_id, host_type, health_check_url, online_status "
                "FROM ws_host_whitelist WHERE host_id=%s",
                (HOST_ID,),
            )
            print("3) 模拟 Admin 探测后:", cur.fetchone())
            print("完成：若 Hermes Gateway 已启动且 URL 正确，online_status 应为 online。")
    finally:
        conn.close()


if __name__ == "__main__":
    main()

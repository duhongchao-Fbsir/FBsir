/**
 * 调用 Admin HTTP「单次 Engine」触发 QYWEIXIN_EXPLORE_UI_MAP，将企微 AI 助手多路由细粒度 UI 清单落盘。
 * 前置：localhost:8080 Admin、8081 Engine 已启动且企微会话已 QYWEIXIN_SCAN_LOGIN。
 *
 * 用法（仓库根目录）:
 *   node tools/qywx-aihelper-fine-map.mjs
 * 环境变量：WXFBSIR_ADMIN=http://127.0.0.1:8080  ENGINE_ID=engine-dev-001  USER_ID=1
 */
import fs from 'fs';
import http from 'http';
import path from 'path';
import { fileURLToPath } from 'url';

const __dirname = path.dirname(fileURLToPath(import.meta.url));

const admin = (process.env.WXFBSIR_ADMIN || 'http://127.0.0.1:8080').replace(/\/$/, '');
const engineId = process.env.ENGINE_ID || 'engine-dev-001';
const userId = process.env.USER_ID || '1';
const outFile = process.env.OUT || path.join(__dirname, 'qywx-ui-map-result.json');

function postJson(path, bodyObj) {
  const body = Buffer.from(JSON.stringify(bodyObj), 'utf8');
  const u = new URL(path, admin);
  return new Promise((resolve, reject) => {
    const req = http.request(
      {
        hostname: u.hostname,
        port: u.port || 80,
        path: u.pathname + u.search,
        method: 'POST',
        headers: {
          'Content-Type': 'application/json; charset=utf-8',
          'Content-Length': body.length
        }
      },
      (res) => {
        let data = '';
        res.setEncoding('utf8');
        res.on('data', (c) => (data += c));
        res.on('end', () => {
          try {
            resolve(JSON.parse(data));
          } catch (e) {
            reject(new Error(`JSON 解析失败: ${e.message}\n${data.slice(0, 500)}`));
          }
        });
      }
    );
    req.on('error', reject);
    req.write(body);
    req.end();
  });
}

async function main() {
  console.log(`POST ${admin}/ws/engine/request  type=QYWEIXIN_EXPLORE_UI_MAP`);
  const payload = {
    engineId,
    type: 'QYWEIXIN_EXPLORE_UI_MAP',
    userId,
    timeout: 120,
    payload: {}
  };
  const res = await postJson('/ws/engine/request', payload);
  fs.writeFileSync(outFile, JSON.stringify(res, null, 2), 'utf8');
  console.log('已写入:', outFile);
  if (res.success) {
    const views = res.data?.data?.views;
    console.log('views:', Array.isArray(views) ? views.length : 0);
  } else {
    console.error('失败:', res.errorMessage || res);
    process.exitCode = 1;
  }
}

main().catch((e) => {
  console.error(e);
  process.exitCode = 1;
});

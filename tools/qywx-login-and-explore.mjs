/**
 * 触发企业微信扫码登录，并在登录完成后探索工作流页面
 */
import WebSocket from 'ws';
import fs from 'fs';
import https from 'https';

// 先获取 token
async function getToken() {
  return new Promise((resolve, reject) => {
    const body = JSON.stringify({username:'admin',password:'admin123',code:'',uuid:''});
    const req = https.request({ hostname:'localhost', port:8080, path:'/login', method:'POST',
      headers:{'Content-Type':'application/json','Content-Length':Buffer.byteLength(body)},
      rejectUnauthorized: false
    }, res => {
      let data = '';
      res.on('data', d => data += d);
      res.on('end', () => {
        try { resolve(JSON.parse(data).token); } catch { reject(new Error('解析token失败: ' + data)); }
      });
    });
    req.on('error', reject);
    req.write(body);
    req.end();
  });
}

async function fetchToken() {
  // 用 http
  const { default: http } = await import('http');
  return new Promise((resolve, reject) => {
    const body = JSON.stringify({username:'admin',password:'admin123',code:'',uuid:''});
    const req = http.request({ hostname:'localhost', port:8080, path:'/login', method:'POST',
      headers:{'Content-Type':'application/json','Content-Length':Buffer.byteLength(body)}
    }, res => {
      let data = '';
      res.on('data', d => data += d);
      res.on('end', () => {
        try { resolve(JSON.parse(data).token); } catch { reject(new Error('解析token失败: ' + data)); }
      });
    });
    req.on('error', reject);
    req.write(body);
    req.end();
  });
}

const TOKEN = await fetchToken();
console.log('✓ Token 获取成功:', TOKEN.substring(0, 30) + '...');

const ENGINE_ID = 'engine-dev-001';
const WS_URL = `ws://localhost:8080/ws/client?token=${TOKEN}&clientType=web&wsSlot=qywx-login`;

const ws = new WebSocket(WS_URL);
let phase = 'login';  // login -> explore

ws.on('open', () => {
  console.log('✓ 已连接，发送 QYWEIXIN_SCAN_LOGIN...');
  ws.send(JSON.stringify({
    type: 'QYWEIXIN_SCAN_LOGIN',
    engineId: ENGINE_ID,
    requestId: 'login-' + Date.now(),
    userId: '1',
  }));
});

ws.on('message', (data) => {
  const raw = data.toString();
  let parsed;
  try { parsed = JSON.parse(raw); } catch { return; }

  const msgType = parsed.type || parsed.code;
  if (msgType === 'CONNECTED') {
    console.log('← 连接确认:', parsed.clientId);
    return;
  }

  // 流式进度消息
  if (msgType === 'TASK_PROGRESS' || parsed.payload?.progress) {
    const msg = parsed.payload?.message || parsed.message || '...';
    console.log('⟳ 进度:', msg.substring(0, 100));
    return;
  }

  if (msgType === 'TASK_RESULT') {
    const payload = parsed.payload || parsed;
    if (phase === 'login') {
      if (payload.success) {
        console.log('\n✓ 企业微信登录成功！切换到工作流探索...');
        const data = payload.data || {};
        console.log('  loginStatus:', data.loginStatus);
        console.log('  screenshotUrl:', data.screenshotUrl);
        
        // 保存登录截图
        fs.writeFileSync('d:/U3WV2/tools/qywx-login-result.json', JSON.stringify(payload, null, 2));
        
        // 等待2秒后探索工作流
        phase = 'workflow';
        setTimeout(() => {
          console.log('\n→ 发送 QYWEIXIN_EXPLORE_WORKFLOW...');
          ws.send(JSON.stringify({
            type: 'QYWEIXIN_EXPLORE_WORKFLOW',
            engineId: ENGINE_ID,
            requestId: 'workflow-' + Date.now(),
            userId: '1',
          }));
        }, 2000);
      } else {
        console.log('\n✗ 登录失败:', payload.errorMessage);
        console.log('  请手动扫码，或检查企业微信登录状态');
        // 如果是扫码等待中，继续等
        if (payload.errorMessage?.includes('超时') || payload.errorMessage?.includes('等待')) {
          console.log('  等待用户扫码...');
        } else {
          // 可能已经登录了，直接尝试工作流探索
          console.log('  直接尝试探索工作流...');
          phase = 'workflow';
          setTimeout(() => {
            ws.send(JSON.stringify({
              type: 'QYWEIXIN_EXPLORE_WORKFLOW',
              engineId: ENGINE_ID,
              requestId: 'workflow-' + Date.now(),
              userId: '1',
            }));
          }, 1000);
        }
      }
    } else if (phase === 'workflow') {
      console.log('\n========== 工作流探索结果 ==========');
      if (payload.success) {
        const d = payload.data || {};
        console.log('  currentUrl:', d.currentUrl);
        console.log('  tabClickSuccess:', d.tabClickSuccess);
        console.log('  workflowCount:', d.workflowCount);
        console.log('  screenshotUrl:', d.screenshotUrl);
        
        if (d.workflowAnalysis) {
          const wa = d.workflowAnalysis;
          console.log('\n--- 工作流区域分析 ---');
          console.log('  hasCreateBtn:', wa.hasCreateBtn);
          console.log('  createBtnText:', wa.createBtnText);
          console.log('  isEmpty:', wa.isEmpty);
          console.log('  emptyText:', wa.emptyText);
          if (wa.buttons?.length > 0) {
            console.log('  buttons:', JSON.stringify(wa.buttons));
          }
          if (wa.bodyText) {
            console.log('\n--- 页面文本（前500字）---');
            console.log(wa.bodyText.substring(0, 500));
          }
        }
        
        if (d.workflows?.length > 0) {
          console.log('\n--- 工作流列表 ---');
          d.workflows.slice(0, 10).forEach((w, i) => console.log(`  [${i}]`, JSON.stringify(w)));
        }
      } else {
        console.log('  错误:', payload.errorMessage);
      }

      fs.writeFileSync('d:/U3WV2/tools/workflow-final-result.json', JSON.stringify(payload, null, 2));
      console.log('\n✓ 完整结果保存至 workflow-final-result.json');
      ws.close();
    }
  }
});

ws.on('error', (err) => console.error('✗ 错误:', err.message));
ws.on('close', () => { console.log('连接关闭'); process.exit(0); });
setTimeout(() => { console.log('超时'); ws.close(); }, 120000);

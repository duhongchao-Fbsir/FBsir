/**
 * 拉取 QYWEIXIN_CAPABILITY_CATALOG（无企微页面依赖），落盘 JSON。
 * 前置：Admin+Engine，Engine JAR 已含该 OnceCapability。
 *
 * 用法: node tools/qywx-capability-catalog.mjs
 * 环境: WXFBSIR_ADMIN ENGINE_ID USER_ID OUT
 */
import fs from 'fs'
import http from 'http'
import path from 'path'
import { fileURLToPath } from 'url'

const __dirname = path.dirname(fileURLToPath(import.meta.url))
const admin = (process.env.WXFBSIR_ADMIN || 'http://127.0.0.1:8080').replace(/\/$/, '')
const engineId = process.env.ENGINE_ID || 'engine-dev-001'
const userId = process.env.USER_ID || '1'
const outFile = process.env.OUT || path.join(__dirname, 'qywx-capability-catalog-result.json')

function postJson(relPath, bodyObj) {
  const body = Buffer.from(JSON.stringify(bodyObj), 'utf8')
  const u = new URL(relPath, admin)
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
        let data = ''
        res.setEncoding('utf8')
        res.on('data', (c) => (data += c))
        res.on('end', () => {
          try {
            resolve(JSON.parse(data))
          } catch (e) {
            reject(new Error(`${e.message}\n${data.slice(0, 400)}`))
          }
        })
      }
    )
    req.on('error', reject)
    req.write(body)
    req.end()
  })
}

async function main() {
  console.log(`POST ${admin}/ws/engine/request  QYWEIXIN_CAPABILITY_CATALOG`)
  const res = await postJson('/ws/engine/request', {
    engineId,
    type: 'QYWEIXIN_CAPABILITY_CATALOG',
    userId,
    timeout: 25,
    payload: {}
  })
  fs.writeFileSync(outFile, JSON.stringify(res, null, 2), 'utf8')
  console.log('已写入:', outFile)
  if (res.success) {
    const inner = res.data?.data ?? res.data
    console.log('entries:', inner?.entries?.length ?? 0)
  } else {
    console.error('失败:', res.errorMessage || res)
    process.exitCode = 1
  }
}

main().catch((e) => {
  console.error(e)
  process.exitCode = 1
})

#!/usr/bin/env node
/**
 * PerPress 压测演示被测服务（零依赖，Node 14+ 直接运行）
 * -----------------------------------------------------
 * 用途：提供一组典型业务接口作为压测目标，用于整体验证平台能力
 * （TPS/RT 统计、P95/P99 分位数、错误分析、CSV 参数化等）。
 *
 * 启动：node scripts/demo-target-server.js [端口，默认 9090]
 *
 * 接口清单：
 *   GET  /health            健康检查（瞬时返回）
 *   GET  /api/hello         快接口：固定 ~3ms（验证基础 TPS/RT）
 *   GET  /api/query?username=xxx  查询接口：10~30ms 随机（适合 CSV 参数化）
 *   POST /api/order         下单接口：30~80ms（JSON body：{"sku":"x","qty":1}）
 *   GET  /api/slow          慢接口：~300ms（验证 P95/P99 长尾分位数）
 *   GET  /api/error         按概率报错：默认 8% 返回 500（验证错误分析）
 *   GET  /api/metrics       被测服务自身统计（请求数/错误数/慢查询数）
 *
 * 平台「脚本中心 → 表单创建」可直接填写这些 URL 进行压测，
 * 例：http://localhost:9090/api/query?username=${username}（配合 CSV 参数文件）
 */
'use strict';

const http = require('http');
const PORT = Number(process.argv[2] || 9090);

/** 被测服务自身运行统计（GET /api/metrics 可查） */
const stats = { total: 0, errors: 0, slow: 0, startTime: Date.now() };

/**
 * 模拟业务处理耗时：异步等待指定毫秒（不阻塞事件循环）。
 * 注意：不能用同步阻塞（Atomics.wait/busy-loop）模拟延迟——并发下会串行排队，
 * 导致实测 RT 远大于配置延迟，压测数据失真。
 *
 * @param {number} ms 等待毫秒数
 * @returns {Promise<void>} 等待完成的 Promise
 */
function sleep(ms) {
  return new Promise((resolve) => setTimeout(resolve, ms));
}

/** [min,max] 区间随机整数 */
function rand(min, max) {
  return min + Math.floor(Math.random() * (max - min + 1));
}

/**
 * 统一 JSON 响应
 *
 * @param {object} res  ServerResponse
 * @param {number} code HTTP 状态码
 * @param {object} body 响应体
 */
function reply(res, code, body) {
  const text = JSON.stringify(body);
  res.writeHead(code, {
    'Content-Type': 'application/json; charset=utf-8',
    'Content-Length': Buffer.byteLength(text)
  });
  res.end(text);
}

/** 服务实例：按路径路由并注入模拟延迟/错误（异步处理，不阻塞事件循环） */
const server = http.createServer(async (req, res) => {
  stats.total++;
  const url = new URL(req.url, `http://localhost:${PORT}`);
  const path = url.pathname;

  // 健康检查：瞬时
  if (path === '/health') {
    return reply(res, 200, { ok: true, uptime: Math.round((Date.now() - stats.startTime) / 1000) });
  }

  // 被测服务自身指标
  if (path === '/api/metrics') {
    return reply(res, 200, { ...stats, qps: (stats.total / ((Date.now() - stats.startTime) / 1000)).toFixed(1) });
  }

  // 快接口：~3ms
  if (path === '/api/hello') {
    await sleep(3);
    return reply(res, 200, { code: 0, msg: 'ok', data: 'hello' });
  }

  // 查询接口：10~30ms（接收 username 参数并回显，适合 CSV 参数化验证）
  if (path === '/api/query') {
    const username = url.searchParams.get('username') || 'anonymous';
    await sleep(rand(10, 30));
    return reply(res, 200, { code: 0, msg: 'ok', data: { username, vip: username.startsWith('vip') } });
  }

  // 下单接口：30~80ms（读 JSON body）
  if (path === '/api/order' && req.method === 'POST') {
    const body = await new Promise((resolve) => {
      let buf = '';
      req.on('data', (c) => { buf += c; });
      req.on('end', () => resolve(buf));
    });
    await sleep(rand(30, 80));
    let sku = 'default';
    try { sku = JSON.parse(body).sku || sku; } catch (e) { /* 非法 body 用默认值 */ }
    return reply(res, 200, { code: 0, msg: 'ok', data: { orderId: 'O' + Date.now(), sku } });
  }

  // 慢接口：~300ms（制造长尾，验证 P95/P99）
  if (path === '/api/slow') {
    await sleep(300);
    stats.slow++;
    return reply(res, 200, { code: 0, msg: 'ok', data: 'slow-done' });
  }

  // 按概率报错：8% 返回 500（验证错误率/错误分析）
  if (path === '/api/error') {
    await sleep(rand(5, 15));
    if (Math.random() < 0.08) {
      stats.errors++;
      return reply(res, 500, { code: 500, msg: 'mock internal error' });
    }
    return reply(res, 200, { code: 0, msg: 'ok' });
  }

  stats.errors++;
  reply(res, 404, { code: 404, msg: 'not found: ' + path });
});

server.listen(PORT, () => {
  console.log('PerPress 压测演示被测服务已启动: http://localhost:' + PORT);
  console.log('接口：GET /health | GET /api/hello | GET /api/query?username=x | POST /api/order');
  console.log('      GET /api/slow(300ms 长尾) | GET /api/error(8% 报错) | GET /api/metrics(自身统计)');
  console.log('平台使用：脚本中心→表单创建，URL 填以上接口即可开始压测');
});

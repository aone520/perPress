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
 * 混合压测场景接口（/api/mix/*，配合「混合场景」示例脚本）：
 *   串行主链路（引用 users.csv：username,password,sku；逐级提取传递）：
 *     POST /api/mix/login          20~40ms，body {username,password} → data.token/userId
 *     GET  /api/mix/product?sku=   10~30ms，头 Authorization: Bearer ${token} → data.price/stock
 *     POST /api/mix/order          30~80ms，头 token，body {sku,price} → data.orderId/amount
 *     POST /api/mix/pay            40~100ms，头 token，body {orderId,payChannel} → data.payNo
 *   并行浏览流量（引用 browse.txt：channel|city|keyword）：
 *     GET /api/mix/feed?channel=&city=      5~15ms
 *     GET /api/mix/recommend?city=&limit=   15~40ms
 *     GET /api/mix/search?keyword=&city=    20~50ms
 *   token 格式强校验（tk.<userId>.<16hex>）：上游提取器配置错误时后续接口立即 401，链路断裂可见
 *
 * 平台「脚本中心 → 表单创建」可直接填写这些 URL 进行压测，
 * 例：http://localhost:9090/api/query?username=${username}（配合 CSV 参数文件）
 */
'use strict';

const http = require('http');
const crypto = require('crypto');
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

/** 混合场景合法 token 格式：tk.<userId>.<16位hex>（login 颁发，后续接口强校验） */
const TOKEN_RE = /^tk\.(\d+)\.([a-f0-9]{16})$/;

/**
 * 读取请求体的完整文本（POST/PUT JSON 解析前置步骤）。
 *
 * @param {IncomingMessage} req 请求对象
 * @returns {Promise<string>} body 原始文本
 */
function readBody(req) {
  return new Promise((resolve) => {
    let buf = '';
    req.on('data', (c) => { buf += c; });
    req.on('end', () => resolve(buf));
  });
}

/**
 * 解析请求体为 JSON 对象（非法 JSON 返回空对象，不抛错——mock 服务容错优先）。
 *
 * @param {string} body 请求体文本
 * @returns {object} 解析结果
 */
function parseJson(body) {
  try { return JSON.parse(body) || {}; } catch (e) { return {}; }
}

/**
 * 颁发混合场景登录令牌：tk.<userId>.<16位hex>。
 *
 * @param {number} userId 用户ID
 * @returns {string} token 字符串
 */
function issueToken(userId) {
  return 'tk.' + userId + '.' + crypto.randomBytes(8).toString('hex');
}

/**
 * 校验并提取 Authorization 头中的 Bearer token（格式非法返回 null）。
 *
 * @param {IncomingMessage} req 请求对象
 * @returns {string|null} 合法 token 或 null
 */
function bearerToken(req) {
  const auth = req.headers['authorization'] || '';
  const token = auth.startsWith('Bearer ') ? auth.slice(7) : '';
  return TOKEN_RE.test(token) ? token : null;
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

  // ==================== 混合压测场景接口（/api/mix/*） ====================

  // 串行链路 1/4 登录：校验 username/password（来自 users.csv），颁发 token（供 JSON 提取器提取）
  if (path === '/api/mix/login' && req.method === 'POST') {
    const { username, password } = parseJson(await readBody(req));
    await sleep(rand(20, 40));
    if (!username || String(password || '').length < 6) {
      stats.errors++;
      return reply(res, 401, { code: 401, msg: 'invalid credentials: username required, password >= 6 chars' });
    }
    const userId = 10000 + rand(1, 8999);
    return reply(res, 200, { code: 0, msg: 'ok', data: { userId, token: issueToken(userId), username } });
  }

  // 串行链路 2/4 商品查询：Bearer token 强校验（提取器配置错误立即 401），返回价格/库存
  if (path === '/api/mix/product') {
    const token = bearerToken(req);
    if (!token) {
      stats.errors++;
      return reply(res, 401, { code: 401, msg: 'missing or invalid bearer token' });
    }
    const sku = url.searchParams.get('sku');
    await sleep(rand(10, 30));
    if (!sku) {
      stats.errors++;
      return reply(res, 400, { code: 400, msg: 'sku required' });
    }
    return reply(res, 200, { code: 0, msg: 'ok', data: { sku, price: rand(990, 99900) / 100, stock: rand(1, 100) } });
  }

  // 串行链路 3/4 提交订单：token 校验 + body 携带 csv 的 sku 与上一步提取的 price，返回 orderId
  if (path === '/api/mix/order' && req.method === 'POST') {
    const token = bearerToken(req);
    if (!token) {
      stats.errors++;
      return reply(res, 401, { code: 401, msg: 'missing or invalid bearer token' });
    }
    const body = parseJson(await readBody(req));
    await sleep(rand(30, 80));
    if (!body.sku) {
      stats.errors++;
      return reply(res, 400, { code: 400, msg: 'sku required' });
    }
    const amount = Number(body.price) > 0 ? Number(body.price) : rand(99, 999) / 10;
    return reply(res, 200, {
      code: 0, msg: 'ok',
      data: { orderId: 'OD' + Date.now() + rand(100, 999), sku: body.sku, amount: amount.toFixed(2) }
    });
  }

  // 串行链路 4/4 订单支付：token 校验 + 上一步提取的 orderId，返回 payNo（边界提取器目标）
  if (path === '/api/mix/pay' && req.method === 'POST') {
    const token = bearerToken(req);
    if (!token) {
      stats.errors++;
      return reply(res, 401, { code: 401, msg: 'missing or invalid bearer token' });
    }
    const body = parseJson(await readBody(req));
    await sleep(rand(40, 100));
    if (!/^OD\d{12,}$/.test(String(body.orderId || ''))) {
      stats.errors++;
      return reply(res, 400, { code: 400, msg: 'invalid orderId (expect OD+timestamp)' });
    }
    return reply(res, 200, {
      code: 0, msg: 'ok',
      data: { payNo: 'PN' + Date.now() + rand(100, 999), orderId: body.orderId, channel: body.payChannel || 'DEFAULT' }
    });
  }

  // 并行浏览 1/3 信息流：回显 channel/city（browse.txt 参数化验证）
  if (path === '/api/mix/feed') {
    const channel = url.searchParams.get('channel') || 'all';
    const city = url.searchParams.get('city') || 'all';
    await sleep(rand(5, 15));
    return reply(res, 200, { code: 0, msg: 'ok', data: { channel, city, items: rand(6, 20) } });
  }

  // 并行浏览 2/3 猜你喜欢：按城市返回推荐位列表
  if (path === '/api/mix/recommend') {
    const city = url.searchParams.get('city') || 'all';
    const limit = Number(url.searchParams.get('limit') || 5);
    await sleep(rand(15, 40));
    return reply(res, 200, {
      code: 0, msg: 'ok',
      data: { city, list: Array.from({ length: Math.min(limit, 20) }, (_, i) => ({ id: i + 1, score: rand(60, 99) })) }
    });
  }

  // 并行浏览 3/3 搜索：按关键词返回命中数（browse.txt 的 keyword 列）
  if (path === '/api/mix/search') {
    const keyword = url.searchParams.get('keyword') || '';
    const city = url.searchParams.get('city') || 'all';
    await sleep(rand(20, 50));
    if (!keyword) {
      stats.errors++;
      return reply(res, 400, { code: 400, msg: 'keyword required' });
    }
    return reply(res, 200, { code: 0, msg: 'ok', data: { keyword, city, hits: rand(1, 120) } });
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

#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
PerPress Docker 平台完整演示脚本创建工具：
1. 上传 CSV 参数文件（username 列，20 行测试用户）
2. 创建覆盖全能力的表单压测脚本：
   - 串行组「查询下单链路」：query（CSV 参数化）→ JSON 提取 vip → order（引用提取变量）
   - 并行组「混合压力」：hello / slow(长尾) / error(8%报错) 三接口独立并行（可设流量占比）
3. 创建并启动一个 3 节点验证任务（15 秒）
用法：python3 scripts/create-demo-script.py [平台地址，默认 http://localhost:8180]
"""
import io
import json
import time
import urllib.request
import urllib.error
import uuid

BASE = "http://localhost:8180"
TARGET = "http://per-target:9090"


def call(url, body=None, method=None, token=None, data=None, headers=None):
    """统一 HTTP 调用：body 为 JSON 对象，data 为原始字节（multipart 用）"""
    if body is not None:
        data = json.dumps(body).encode()
    req = urllib.request.Request(url, data=data, method=method or ('POST' if data else 'GET'))
    req.add_header('Content-Type', 'application/json')
    for k, v in (headers or {}).items():
        req.add_header(k, v)
    if token:
        req.add_header('Authorization', 'Bearer ' + token)
    return json.load(urllib.request.urlopen(req))


def upload_csv(token):
    """生成并上传 username 参数文件（20 行用户）"""
    rows = ["username"] + [f"vip{i}" for i in range(1, 11)] + [f"user{i}" for i in range(1, 11)]
    content = ("\n".join(rows) + "\n").encode()
    boundary = uuid.uuid4().hex
    body = (f"--{boundary}\r\n"
            f"Content-Disposition: form-data; name=\"file\"; filename=\"usernames.csv\"\r\n"
            f"Content-Type: text/csv\r\n\r\n").encode() + content + f"\r\n--{boundary}--\r\n".encode()
    r = call(f"{BASE}/api/files", data=body, token=token,
             headers={'Content-Type': f'multipart/form-data; boundary={boundary}'})
    fid = r['data']['id']
    print(f"[1/3] 参数文件已上传: usernames.csv (id={fid}, 20 行)")
    return fid


def create_script(token, file_id):
    """创建覆盖全能力的演示脚本：串行链路（CSV+提取+引用）+ 并行组（长尾/错误）"""
    form = {
        "name": "演示-全功能压测脚本",
        "description": "CSV参数化+串行提取+并行组+断言+长尾+错误分析 全能力演示",
        "formDef": {
            "threadGroupName": "全功能演示",
            "thinkTimeMs": 0,
            "groups": [
                {
                    "name": "查询下单链路",
                    "execution": "SERIAL",
                    "samplers": [
                        {
                            "name": "查询-query",
                            "method": "GET",
                            "url": f"{TARGET}/api/query?username=${{username}}",
                            "csvRefs": [{"fileId": file_id, "varNames": "username",
                                         "delimiter": ",", "recycle": True,
                                         "shareMode": "shareMode.all", "ignoreFirstLine": True}],
                            "assertions": [{"type": "CODE", "expect": "200"}],
                            "extractors": [
                                {"type": "JSON", "refName": "vip", "expression": "$.data.vip",
                                 "source": "RESPONSE_BODY", "matchNumber": 1, "defaultValue": "NOT_FOUND"}]
                        },
                        {
                            "name": "下单-order",
                            "method": "POST",
                            "url": f"{TARGET}/api/order",
                            "headers": [{"k": "Content-Type", "v": "application/json"}],
                            "body": '{"sku":"${vip}","qty":1}',
                            "assertions": [{"type": "CODE", "expect": "200"}]
                        }
                    ]
                },
                {
                    "name": "混合压力",
                    "execution": "PARALLEL",
                    "samplers": [
                        {"name": "快接口-hello", "method": "GET", "url": f"{TARGET}/api/hello",
                         "assertions": [{"type": "CODE", "expect": "200"}]},
                        {"name": "慢接口-slow", "method": "GET", "url": f"{TARGET}/api/slow",
                         "assertions": [{"type": "CODE", "expect": "200"}]},
                        {"name": "错误接口-error", "method": "GET", "url": f"{TARGET}/api/error",
                         "assertions": [{"type": "CODE", "expect": "200"}]}
                    ]
                }
            ]
        }
    }
    r = call(f"{BASE}/api/scripts/form", form, token=token)
    sid = r['data']['id']
    print(f"[2/3] 演示脚本已创建: {form['name']} (id={sid}, 2 组 / 5 接口 / 4 执行单元)")
    return sid


def run_verify_task(token, script_id):
    """创建并启动 3 节点验证任务（占比 40/20/20/20，15 秒）"""
    nodes = call(f"{BASE}/api/nodes?page=1&size=10", token=token)['data']['records']
    nks = [n['nodeKey'] for n in nodes if n['status'] == 'ONLINE']
    body = {"name": "演示-全功能验证", "scriptId": script_id, "version": 1,
            "mode": "CONCURRENT", "nodeKeys": nks,
            "config": {"threads": 40, "rampupSeconds": 2, "durationSeconds": 15,
                       "weights": [40, 20, 20, 20]}}
    t = call(f"{BASE}/api/tasks", body, token=token)['data']
    call(f"{BASE}/api/tasks/{t['id']}/start", {}, token=token)
    print(f"[3/3] 验证任务已启动: {t['taskNo']} (3 节点×占比 40/20/20/20, 15 秒)")
    return t['id']


if __name__ == '__main__':
    tk = call(f"{BASE}/api/auth/login",
              {"username": "admin", "password": "admin123"})['data']['token']
    fid = upload_csv(tk)
    sid = create_script(tk, fid)
    tid = run_verify_task(tk, sid)
    print("\n等待任务结束...")
    for _ in range(20):
        time.sleep(4)
        st = call(f"{BASE}/api/tasks/{tid}", token=tk)['data']['status']
        if st in ('FINISHED', 'FAILED'):
            break
    d = call(f"{BASE}/api/tasks/{tid}/report", token=tk)['data']
    s = d['summary']
    print(f"状态: {st} | 总样本: {s['totalCount']} | 错误: {s['totalErrorCount']} "
          f"(错误率 {s['errorRate']}%) | 平均TPS: {s['avgTps']} | APDEX: {s.get('apdex')}")
    for u in d['samplers']:
        print(f"  {u['label']:<14} 样本={u['count']:<7} 错误={u['errorCount']:<5} "
              f"平均TPS={u['tps']:<7} P95={u['p95']}ms")

#!/usr/bin/env bash
# =====================================================================
# PerPress 混合压测示例脚本灌入工具
# -----------------------------------------------------
# 用途：部署完成后调用平台 API 自动创建「混合压测场景」示例：
#   1. admin 登录获取 token
#   2. 上传参数文件 demo-data/users.csv（CSV）与 browse.txt（TXT，| 分隔）
#   3. 创建混合脚本：
#      - 串行组「下单主链路」：登录→查价→下单→支付，
#        引用 users.csv，逐级提取 token/price/orderId/payNo 向后传递
#      - 并行组「浏览流量」：信息流/推荐/搜索 3 接口各自独立线程组，
#        引用 browse.txt（TXT 参数文件，自定义 | 分隔符）
#   4. 调用一键调试接口验证链路（变量替换/提取传递/断言）
#
# 用法：bash deploy/seed-demo.sh [平台地址，默认 http://localhost:8180]
# 幂等：同名脚本已存在时跳过创建，仅重新执行调试验证
# =====================================================================
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
BASE_URL="${1:-http://localhost:8180}"
SCRIPT_NAME="混合压测场景-电商下单+浏览流量"
TMP_DIR="$(mktemp -d)"
trap 'rm -rf "$TMP_DIR"' EXIT

# 从 stdin 的 JSON 中提取字段（python3 实现，参数为访问路径，如 ['data']['id']）
json_field() {
  python3 -c "import sys,json;d=json.load(sys.stdin);print(d$1)" 2>/dev/null || echo ""
}

# 轮询登录接口直到平台就绪（最多 120 秒）
wait_server_ready() {
  echo "等待平台就绪: $BASE_URL ..."
  for _ in $(seq 1 60); do
    if curl -s -o /dev/null --max-time 3 "$BASE_URL/api/auth/login" -X POST \
         -H 'Content-Type: application/json' -d '{}'; then
      return 0
    fi
    sleep 2
  done
  echo "错误：平台 120s 内未就绪" >&2
  return 1
}

# ---------- 0. 等待就绪并登录 ----------
wait_server_ready

LOGIN_RESP="$(curl -s -X POST "$BASE_URL/api/auth/login" -H 'Content-Type: application/json' \
  -d '{"username":"admin","password":"admin123"}')"
TOKEN="$(echo "$LOGIN_RESP" | json_field "['data']['token']")"
if [ -z "$TOKEN" ]; then
  echo "错误：登录失败：$LOGIN_RESP" >&2
  exit 1
fi
echo "登录成功"

# ---------- 1. 查询是否已存在（幂等） ----------
EXIST_TOTAL="$(curl -s -H "Authorization: Bearer $TOKEN" \
  --get --data-urlencode "keyword=混合压测场景" "$BASE_URL/api/scripts" | json_field "['data']['total']")"

# ---------- 2. 上传参数文件（CSV + TXT） ----------
CSV_ID="$(curl -s -X POST "$BASE_URL/api/files" -H "Authorization: Bearer $TOKEN" \
  -F "file=@$SCRIPT_DIR/demo-data/users.csv" | json_field "['data']['id']")"
TXT_ID="$(curl -s -X POST "$BASE_URL/api/files" -H "Authorization: Bearer $TOKEN" \
  -F "file=@$SCRIPT_DIR/demo-data/browse.txt" | json_field "['data']['id']")"
if [ -z "$CSV_ID" ] || [ -z "$TXT_ID" ]; then
  echo "错误：参数文件上传失败（csv=$CSV_ID txt=$TXT_ID）" >&2
  exit 1
fi
echo "参数文件就绪：users.csv#$CSV_ID / browse.txt#$TXT_ID"

# ---------- 3. 创建混合脚本（或复用已有） ----------
# 串行组：登录(${username}/${password} 来自 CSV) → JSON提取 token →
#         查价(头携带 ${token}) → JSON提取 price →
#         下单(REGEX提取 orderId) → 支付(BOUNDARY提取 payNo)
# 并行组：3 接口独立线程组，各自挂 TXT 引用（shareMode.all 共享指针逐行取数）
cat > "$TMP_DIR/formdef.json" <<'EOF'
{
  "threadGroupName": "混合压测-场景组",
  "thinkTimeMs": 200,
  "config": {
    "protocol": "http",
    "host": "per-target",
    "port": 9090,
    "variables": [
      { "name": "payChannel", "value": "ALIPAY" },
      { "name": "appVersion", "value": "2.4.0" }
    ]
  },
  "groups": [
    {
      "name": "下单主链路",
      "execution": "SERIAL",
      "samplers": [
        {
          "name": "01-登录",
          "method": "POST",
          "url": "/api/mix/login",
          "csvRefs": [
            { "fileId": __CSV_ID__, "varNames": "username,password,sku", "delimiter": ",", "ignoreFirstLine": true, "recycle": true, "shareMode": "shareMode.all" }
          ],
          "headers": [ { "k": "Content-Type", "v": "application/json" } ],
          "body": "{\"username\":\"${username}\",\"password\":\"${password}\"}",
          "assertions": [ { "type": "CODE", "expect": "200" } ],
          "extractors": [ { "type": "JSON", "refName": "token", "expression": "$.data.token", "defaultValue": "NOT_FOUND" } ]
        },
        {
          "name": "02-查商品价格",
          "method": "GET",
          "url": "/api/mix/product?sku=${sku}",
          "headers": [ { "k": "Authorization", "v": "Bearer ${token}" } ],
          "assertions": [ { "type": "CODE", "expect": "200" } ],
          "extractors": [ { "type": "JSON", "refName": "price", "expression": "$.data.price", "defaultValue": "0" } ]
        },
        {
          "name": "03-提交订单",
          "method": "POST",
          "url": "/api/mix/order",
          "headers": [
            { "k": "Content-Type", "v": "application/json" },
            { "k": "Authorization", "v": "Bearer ${token}" }
          ],
          "body": "{\"sku\":\"${sku}\",\"price\":\"${price}\",\"appVersion\":\"${appVersion}\"}",
          "assertions": [ { "type": "CODE", "expect": "200" } ],
          "extractors": [ { "type": "REGEX", "refName": "orderId", "expression": "\"orderId\":\"([^\"]+)\"", "template": "$1$", "defaultValue": "NOT_FOUND" } ]
        },
        {
          "name": "04-订单支付",
          "method": "POST",
          "url": "/api/mix/pay",
          "headers": [
            { "k": "Content-Type", "v": "application/json" },
            { "k": "Authorization", "v": "Bearer ${token}" }
          ],
          "body": "{\"orderId\":\"${orderId}\",\"payChannel\":\"${payChannel}\"}",
          "assertions": [ { "type": "CODE", "expect": "200" }, { "type": "TEXT", "expect": "ok" } ],
          "extractors": [ { "type": "BOUNDARY", "refName": "payNo", "expression": "\"payNo\":\"", "rightBoundary": "\"", "defaultValue": "NOT_FOUND" } ]
        }
      ]
    },
    {
      "name": "浏览流量",
      "execution": "PARALLEL",
      "samplers": [
        {
          "name": "P1-信息流",
          "method": "GET",
          "url": "/api/mix/feed?channel=${channel}&city=${city}&v=${appVersion}",
          "csvRefs": [
            { "fileId": __TXT_ID__, "varNames": "channel,city,keyword", "delimiter": "|", "ignoreFirstLine": true, "recycle": true, "shareMode": "shareMode.all" }
          ],
          "assertions": [ { "type": "CODE", "expect": "200" } ]
        },
        {
          "name": "P2-猜你喜欢",
          "method": "GET",
          "url": "/api/mix/recommend?city=${city}&limit=5",
          "csvRefs": [
            { "fileId": __TXT_ID__, "varNames": "channel,city,keyword", "delimiter": "|", "ignoreFirstLine": true, "recycle": true, "shareMode": "shareMode.all" }
          ],
          "assertions": [ { "type": "CODE", "expect": "200" } ]
        },
        {
          "name": "P3-搜索",
          "method": "GET",
          "url": "/api/mix/search?keyword=${keyword}&city=${city}",
          "csvRefs": [
            { "fileId": __TXT_ID__, "varNames": "channel,city,keyword", "delimiter": "|", "ignoreFirstLine": true, "recycle": true, "shareMode": "shareMode.all" }
          ],
          "assertions": [ { "type": "CODE", "expect": "200" } ]
        }
      ]
    }
  ]
}
EOF
sed -i '' -e "s/__CSV_ID__/$CSV_ID/g" -e "s/__TXT_ID__/$TXT_ID/g" "$TMP_DIR/formdef.json"

if [ "$EXIST_TOTAL" != "0" ] && [ -n "$EXIST_TOTAL" ]; then
  echo "脚本 ${SCRIPT_NAME} 已存在（${EXIST_TOTAL} 个），跳过创建"
else
  python3 -c "import json;d=json.load(open('$TMP_DIR/formdef.json'));print(json.dumps({'name':'$SCRIPT_NAME','description':'串行组：登录→查价→下单→支付（引用 users.csv，提取 token/price/orderId/payNo 逐级传递）；并行组：信息流/推荐/搜索（引用 browse.txt，TXT 参数文件 | 分隔）','formDef':d}))" > "$TMP_DIR/create.json"
  CREATE_RESP="$(curl -s -X POST "$BASE_URL/api/scripts/form" \
    -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
    --data-binary @"$TMP_DIR/create.json")"
  SCRIPT_ID="$(echo "$CREATE_RESP" | json_field "['data']['id']")"
  if [ -z "$SCRIPT_ID" ]; then
    echo "错误：脚本创建失败：$CREATE_RESP" >&2
    exit 1
  fi
  echo "脚本创建成功：id=$SCRIPT_ID"
fi

# ---------- 4. 一键调试验证混合链路 ----------
python3 -c "import json;d=json.load(open('$TMP_DIR/formdef.json'));print(json.dumps({'formDef':d}))" > "$TMP_DIR/debug.json"
DEBUG_RESP_FILE="$TMP_DIR/debug_response.json"
HTTP_CODE="$(curl -s -o "$DEBUG_RESP_FILE" -w '%{http_code}' -X POST "$BASE_URL/api/scripts/debug" \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  --data-binary @"$TMP_DIR/debug.json")"
if [ "$HTTP_CODE" != "200" ]; then
  echo "错误：调试请求失败 HTTP $HTTP_CODE" >&2
  cat "$DEBUG_RESP_FILE" >&2
  exit 1
fi

echo ""
echo "================ 调试结果 ================"
DEBUG_RESP_FILE="$DEBUG_RESP_FILE" python3 - <<'PYEOF'
import json
import os
import sys

with open(os.environ['DEBUG_RESP_FILE']) as f:
    resp = json.load(f)
items = (resp.get('data') or {}).get('items') or []
fail = 0
for it in items:
    mark = 'PASS' if not it.get('error') else 'FAIL'
    code = it.get('statusCode') or '-'
    err = it.get('error') or ''
    cost = it.get('elapsedMs', '-')
    print(f"  [{mark}] {it.get('groupName')}/{it.get('name')}  HTTP {code}  {cost}ms  {err}")
    if it.get('error'):
        fail += 1
print(f"  -------- 共 {len(items)} 个接口，失败 {fail} 个 --------")
sys.exit(1 if fail else 0)
PYEOF
echo ""
echo "完成：平台页面 http://localhost（admin/admin123）→ 脚本中心 可查看/调试/发起压测"

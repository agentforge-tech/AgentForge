#!/usr/bin/env bash
#
# 百炼 Partial Mode（前缀续写）连通性验证脚本
#
# 用法：
#   export DASHSCOPE_API_KEY=sk-xxxx
#   bash verify-bailian-fim.sh
#
# 说明：正式验证请用 DashScopeLiveVerificationTest（同时覆盖流式与非流式）。

set -u

# ==================== ① 三项配置 ====================

# 百炼兼容模式地址（不含 /chat/completions）
FIM_BASE_URL="${FIM_BASE_URL:-https://dashscope.aliyuncs.com/compatible-mode/v1}"

# 百炼 API Key（注意与地域绑定）
FIM_API_KEY="${FIM_API_KEY:-${DASHSCOPE_API_KEY:-}}"

# Partial Mode 支持清单内的模型
FIM_MODEL_NAME="${FIM_MODEL_NAME:-qwen3-coder-flash}"

# ==================== 逻辑 ====================

if [[ -z "${FIM_API_KEY}" ]]; then
  echo "未设置 FIM_API_KEY / DASHSCOPE_API_KEY，脚本退出。"
  echo "请先执行: export DASHSCOPE_API_KEY=sk-xxxx"
  exit 1
fi

echo "=== 非流式 Partial Mode ==="
echo "url   = ${FIM_BASE_URL}/chat/completions"
echo "model = ${FIM_MODEL_NAME}"
echo

curl --silent --show-error --max-time 30 "${FIM_BASE_URL}/chat/completions" \
  --header "Content-Type: application/json" \
  --header "Authorization: Bearer ${FIM_API_KEY}" \
  --data @- <<JSON
{
  "model": "${FIM_MODEL_NAME}",
  "messages": [
    {"role": "user", "content": "请补全这段代码中间缺失的部分，只输出代码，不要解释。"},
    {"role": "assistant", "content": "    public int add(int a, int b) {\n", "partial": true}
  ],
  "max_tokens": 64,
  "temperature": 0,
  "stop": ["\n\n    public ", "\`\`\`"],
  "stream": false
}
JSON

echo
echo
echo "=== 流式 Partial Mode ==="

curl --silent --show-error --no-buffer --max-time 30 "${FIM_BASE_URL}/chat/completions" \
  --header "Content-Type: application/json" \
  --header "Accept: text/event-stream" \
  --header "Authorization: Bearer ${FIM_API_KEY}" \
  --data @- <<JSON
{
  "model": "${FIM_MODEL_NAME}",
  "messages": [
    {"role": "user", "content": "请补全这段代码中间缺失的部分，只输出代码，不要解释。"},
    {"role": "assistant", "content": "    public int add(int a, int b) {\n", "partial": true}
  ],
  "max_tokens": 64,
  "temperature": 0,
  "stream": true,
  "stream_options": {"include_usage": true}
}
JSON

echo
echo "=== 完成 ==="
echo "检查点："
echo "  1. 响应 object 应为 chat.completion，文本在 choices[0].message.content"
echo "  2. 最后一条 messages 必须是 role=assistant 且带 partial=true，否则会退化成普通问答"
echo "  3. 百炼无原生 suffix：若补全重复了后文代码，属预期内能力上限"
echo "  4. 404  -> 检查 BASE_URL 是否多/少写了 /chat/completions"
echo "  5. 401 invalid_api_key -> 多为 Key 与地域不匹配"
echo "  6. 400 模型不支持 -> 换 qwen3-coder-flash（deepseek-v4.1-flash 不在 Partial Mode 清单）"
echo "  7. content 为空且 finish_reason=stop -> 不要给模型附加 suffix 提示（见坑点记录 03）"
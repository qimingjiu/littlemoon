# 小月助手服务端

Node 22，零依赖，单文件。实现 `client/README.md` 里的接口，用 OpenAI 兼容模型回复，用 FCM HTTP v1 推送。

## 部署

```bash
mkdir -p /opt/littlemoon/secrets && cp server.mjs littlemoon.service /opt/littlemoon/
cp .env.example /opt/littlemoon/.env   # 填 LM_LLM_KEY
cp persona.example.md /opt/littlemoon/persona.md
# 放 Firebase 服务账号 JSON 到 /opt/littlemoon/secrets/fcm-sa.json
cp littlemoon.service /etc/systemd/system/ && systemctl enable --now littlemoon
cd /opt/littlemoon && node server.mjs newcode   # 生成 30 分钟有效的一次性配对码
```

默认只监听 127.0.0.1，请用 Caddy/Nginx 反代 HTTPS。`/internal/*` 测试接口只接受本机直连请求，不要对外暴露。

## 模型工具

- `set_media_volume(percent | step, delay_seconds)`：FCM 静默 data 消息，App 在后台调媒体音量；延时任务持久化，重启后恢复。
- `python_version`：FCM 下发 `tool_request`，App 通过 Termux RUN_COMMAND 执行固定的 `python --version`，结果 PUT 回服务器；超时后迟到的结果会让模型补一句告诉用户。

## ColorOS 实测

- 静默推送要在后台执行，需开「自启动」和「关联启动」。
- 后台 `setStreamVolume()` 会被忽略，`adjustStreamVolume(..., FLAG_SHOW_UI)` 逐格有效。
- 手机必须能连上 Google（FCM）。

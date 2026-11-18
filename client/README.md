# 小月助手 Android 客户端

小月助手是独立的 Android 应用，包名为 `app.littlemoon.assistant`，支持 Android 8.0 及以上。启动入口、配对、消息存储、推送和工具调用都由本工程实现。

## 在手机上试用

1. 安装调试 APK，点输入框左侧的附件按钮。本机工具可以在未配对时使用。可以选择自己的音频文件交给系统播放器、授权后取一次前台位置，或让 Termux 报告已安装的 Python 版本。通过附件按钮执行的工具结果留在本机。
2. 需要聊天时点“连接服务器”，输入实现下文接口的 HTTPS 服务地址和一次性配对码。配对成功后在前台每五秒取一次消息，离开页面就停止定时查询。发送失败的消息可以点“重试”，客户端会沿用第一次的消息编号。
3. 需要锁屏提醒时，在自己的 Firebase 项目中登记名为 `app.littlemoon.assistant` 的 Android 应用，下载它的 **Android 客户端配置** `google-services.json`。点聊天页右上角“服务器与通知”并选“导入推送配置”。手机需有可达的 Google Play 服务，并允许“聊天回复”频道通知。服务器使用同一项目发送。完整的 Android 配置可以从手机导入，无需为每个 Firebase 项目重新编译；服务账号密钥只放在服务器上。
4. 右上角齿轮打开「权限与后台」，可查看通知、聊天频道、电池优化和 Termux 授权。ColorOS 需在系统设置里检查「自启动」与「关联启动」。先打开主线 Termux 完成初始化并安装 Python，在 `~/.termux/termux.properties` 中启用 `allow-external-apps = true` 并运行 `termux-reload-settings`。然后在助手页授予「在 Termux 环境中运行命令」。 [Termux 官方调用说明](https://github.com/termux/termux-app/wiki/RUN_COMMAND-Intent)

## 服务器接口

这是客户端工程。使用现有 OpenClaw 或其他 AI 服务时，在服务器侧实现下列接口。直接填模型供应商或 OpenClaw Gateway 的基础 URL 无法完成配对，因为它们的接口格式不同。

| 请求 | 请求参数 | 成功响应 |
| --- | --- | --- |
| `POST /v1/pair` | JSON 对象，字符串 `code` | `{"device_id":"d1","access_token":"…","conversation_id":"c1"}` |
| `PUT /v1/devices/me/push` | `fid`、布尔值 `notifications_enabled`、递增的 `binding_version` | HTTP 204 或 JSON 对象 |
| `POST /v1/conversations/{id}/messages` | `text`、`client_message_id` | HTTP 202 和 `{"message_id":"m1"}`；同一设备会话重试应返回原消息编号 |
| `GET /v1/conversations/{id}/messages?after_seq=N` | 首次查询 N 为 0 | `{"messages":[…],"next_seq":2,"has_more":false}` |
| `PUT /v1/conversations/{id}/tool-requests/{message_id}/result` | 如 `{"status":"selected","choice":"钢琴"}` 或 `{"status":"opened_player"}` | HTTP 204。按会话和请求编号幂等保存；重试返回同一结果 |

所有配对后的请求携带 `Authorization: Bearer` 设备凭证，由服务器核验授权及会话归属。手机用 Android Keystore 保护凭证，对消息使用按配对设备和会话隔离的本地 SQLite 存储。每页 `messages` 按 `seq` 严格升序返回。非空页的 `next_seq` 是最后一行的序号，空页返回查询时的游标。用户消息回显原 `client_message_id`。聊天页目前显示最近 250 条已缓存记录。

```json
{
  "messages": [
    {"id":"m1","seq":1,"role":"user","text":"挑首歌吧","client_message_id":"客户端原始编号"},
    {"id":"m2","seq":2,"role":"assistant","text":"今晚想听哪一种？"},
    {"id":"m3","seq":3,"role":"tool_request","text":"挑一种音乐","tool":{"name":"choose_option","options":["钢琴","爵士"]}}
  ],
  "next_seq":3,
  "has_more":false
}
```

**聊天里的工具卡片。** 支持 `choose_option` 和 `play_music`。选择请求接受 1 至 6 个文字选项。音乐请求打开系统文件选择器，客户端仅回传播放器是否打开。两者都经已配对接口拉取，用户点卡片后处理。用户可拒绝或稍后处理。网络中断时手机保存结果，再点卡片只重试上传。普通 FCM 聊天通知使用 `notification + data`，其 `data` 携带 `conversation_id`、`message_id`，服务器用 `message.fid` 定向发送并指定频道 `assistant_replies`。

**后台 Termux 工具。** 从 0.2.3 起，同一 Firebase 项目的 data-only 推送可请求配对设备检查一次 Python 版本。字段为 `kind=tool_request`、`tool=python_version`、`device_id`、`conversation_id`、`request_id`，可选 `args={}`。客户端写死程序路径和 `--version` 参数，回传版本号、退出码或执行状态。结果仍走工具结果 PUT，服务端按同一请求编号幂等保存。安卓可能拒绝后台服务启动，届时客户端回报 `blocked`。ColorOS 行为需要实机验证。 [Android 后台启动规则](https://developer.android.com/develop/background-work/services/fgs/restrictions-bg-start)

## 构建和验证

在本目录用 JDK 17、Android SDK 35 和 Gradle Wrapper 构建，Windows 命令为 `.\gradlew.bat :app:assembleDebug`；macOS 和 Linux 为 `./gradlew :app:assembleDebug`。也可在 Android Studio 直接打开本目录。Firebase Messaging SDK 随 APK 一起打包。若想预置项目，可在构建前把匹配包名的 Android 配置放在 `app/google-services.json`，该路径已被 Git 忽略。准备发布给他人时使用自己的发布签名。

可分别验证聊天和推送。聊天测试应覆盖断网重试、相同消息只出现一次，以及选择工具后服务器取得结果。验证 FCM 时，先确认设备在“服务器与通知”里完成注册和绑定，再用同一 Firebase 项目的服务器在手机回桌面、锁屏以及进程被回收时各发一条可见通知。点通知应打开对应会话。Android 系统设置中的强行停止需单独验证。 [Firebase Android 指南](https://firebase.google.com/docs/cloud-messaging/android/get-started)

本次在 Android 15 模拟器中安装了调试包，验证本地音频实际进入播放状态、单次定位返回模拟坐标、选取 Firebase 配置后重启仍能读回项目。临时 HTTPS 服务验证了配对、收发、返回选择及失败后重试。本机 Termux 测试用接口替身检查了空闲时启动服务、固定程序和参数、以及一次性结果回调；目标手机的真实 Termux 环境和 Firebase 锁屏投递仍需联调。

本工程以 MIT 许可发布；Firebase Messaging 等第三方依赖保留各自的许可。

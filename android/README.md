# 雨课伴学 Android 0.3 预览版

独立安卓 AI 学习客户端。配置自己的模型，连接自己的雨课堂，以逐页讲解形成课堂笔记。现有 Python / 云端 MCP 保留。

## 上课模式

雨课堂页 → 查找正在上的课 → 进入课程。**进入会向雨课堂登记入课**，获取当前账号的课堂令牌；只读取课件与翻页事件，不自动答题、发送弹幕或处理位置签到。

- 监听 `hello / fetchtimeline / showpresentation / slidenav / slide`，从时间线补齐已展示的页面，不推测老师尚未展示的页面。
- 每个「课件编号 + 页码」只生成一次，老师回翻不会重复调用模型。翻页快时排队逐页生成，展示待处理数量。
- 每一页完成后，将讲解追加到同一份笔记并加密保存。已完成键和待处理队列也会保留；恢复后避免重复。单页未完成时不写成已完成笔记。
- 支持暂停、继续、结束；连接断开最多自动重连 4 次，重新读取时间线。失败会显示原因并暂停，保留笔记。
- **本版仅前台跟随，切到后台自动暂停；返回后手动继续。** 运行时保持屏幕常亮。没有后台服务或录音，也不会把 AI 补充冒充老师口述。
- 停止本节课会保留完成的笔记并停止剩余队列；下课事件会先整理队列内剩余页面。

## 课件、笔记与模型

历史活动的 type 14 `courseware_id` 先作为课堂编号读取 `classroom-report/student/lesson-info`，从 `presentationIds` 获取真实课件；再用课堂编号和课件编号读取 `classroom-report/student/ppt`。仅展示开放的 `slideList.visible` 页面，保留服务端页码。明确的 presentation_id 仍支持直接读取，不把课程编号当作课件编号。

可添加单页到对话，或选择最多 50 页连续整理到一份笔记。讲解型笔记按资料内容提供概念、机制、公式条件、对照表、易错点、AI 举例和带答案的自测；保留来源，区分 AI 补充与原页内容。笔记支持编辑和 Markdown 导出。运行中的课堂笔记暂不允许手工修改或删除。

内置学习助教与笔记角色 Prompt，可编辑；应用环境和资料处理规则每次都会附加，包括缺少课件时引导应用内选择、不默认索要截图、不声称具有未实现的操作工具。

模型页统一使用「配置模型」。首次安装不预置特定服务商或模型；原有已填写的模型配置保留。

### 使用 ChatGPT 账号

模型页 → **Continue with ChatGPT** → 在本机系统浏览器登录并授权 → 点击「返回雨课伴学」→ 选择账号返回的模型 → 保存并使用。

此方式不要求 API Key。符合资格的请求使用账号授权的 ChatGPT 套餐或额度，可用性由账号、工作区、地区和官方授权结果决定。不绕过权限，也不把登录成功视作模型请求已验证。

- 使用官方开源/本机客户端的动态注册流程，稳定保存 host ID；不借用其他客户端的 ID 或抓取 ChatGPT Cookie。
- 回调仅监听本机 `127.0.0.1` 的随机端口，绑定一次性 state、nonce、S256 PKCE 和精确回调地址；校验签名、issuer、audience、有效期和账号身份后才保存。
- 登录时短暂运行带通知的前台服务，保持切到系统浏览器后的本机回调可用；完成、取消或超时后关闭。这项服务不让上课模式在后台运行。
- 多个账号/工作区注册分别保存，支持重新授权、切换和退出。拒绝使用套餐时保留已验证身份，显示「尚未授权套餐」，不发起模型请求。
- 授权与刷新记录使用现有 Android Keystore 加密存储。接近到期时串行刷新，一次保存更新后的 access / refresh token；临时网络错误不会删除登录。
- 模型列表使用账号返回的 `models`，过滤 `visibility=list`、保留顺序、展示 `display_name`，以 `slug` 请求 Responses。
- 聊天、笔记和上课模式共用此授权；请求固定发送到 `https://api.openai.com/v1/responses`，使用 `store=false`、`stream=true`，收到 `response.completed` 才视为完成。
- 使用套餐时展示用量入口；不支持、受限或失效会明确提示，不自动改为 API 付费。

也可以手动添加其他模型接口（Chat Completions / Responses），用户自行填写对应服务的配置。账号授权与 API 配置分别保存，不会将 ChatGPT 令牌发往自定义服务地址。

这是本应用的模型接入与学习流程，并不嵌入官方 Chat / Work 的全部工具。官方协议来源：
- https://developers.openai.com/siwc/token-sharing-open-source/sign-in
- https://developers.openai.com/siwc/token-sharing-open-source/profiles-and-sessions
- https://developers.openai.com/siwc/token-sharing-open-source/models-and-inference
- https://developers.openai.com/siwc/token-sharing-open-source/errors-and-recovery

## 安装与构建

Android 8.0 及以上。GitHub Actions → Android preview → Artifacts → study-bridge-android-preview，解压安装 `app-release.apk`。

预览包采用临时调试签名；新旧构建签名可能不同，无法覆盖时须先导出重要笔记，再卸载旧版安装。卸载会删除本机密钥与记录。这不是稳定发行签名。

JDK 17、Gradle 8.11.1、SDK 35，在 `android/` 执行：

```sh
gradle testDebugUnitTest assembleRelease
```

Release 启用 R8 与资源收缩。聊天流式文字按时间/大小合并刷新；生成中显示纯文字，结束后解析 Markdown；图片解码与 Markdown 解析移到后台，减少主线程负担。

## 数据与验证边界

模型凭据、ChatGPT 授权记录、雨课堂登录记录、聊天和笔记使用 Android Keystore 的 AES-GCM 加密写入本机应用私有目录。不申请无障碍、读屏、麦克风或联系人权限。选择的课件图片/文字会直接发送给配置的模型服务商，不提供 OCR 自动降级链路。

收到登录 Cookie 后先保存，再单独验证账号资料；资料请求失败保留记录，可重试。诊断进度不展示登录令牌或密钥。

CI 执行单元测试、Release 构建与干净模拟器启动/四个标签页、授权入口及取消检查。OAuth 测试使用本机回调、临时测试签名和模拟端点，不使用真实用户登录。真实账号、学校地区接口、老师实际翻页、模型权限、网络延迟与设备帧率仍须手机联调，不能仅凭编译通过宣称全部可用。雨课堂接口未获官方稳定性保证；不支持的活动或未开放课件会如实提示。

本客户端独立实现，未复制或打包 RikkaHub 源码。

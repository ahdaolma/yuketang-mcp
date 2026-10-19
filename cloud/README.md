# 雨课堂学习桥 · 手机云端版

手机入口：https://yuketang-mobile-study.nuttylemur514.chatgpt.site

站点为账号私有，通过 ChatGPT 身份校验后访问。当前已验证标准雨课堂网站与 WebSocket 登录通道连通；尚未用真实雨课堂账号完成登录和课件联调。

## 手机操作

1. 用创建此站点的同一 ChatGPT 账号打开手机入口。
2. 选择微信中的雨课堂版本，点击“开始微信授权”。
3. 尝试保存二维码，在微信“扫一扫 → 相册”中识别并确认。此类登录码是否允许相册识别由微信决定；若不支持，需要另一台设备协助扫码。
4. 授权期间不要关闭页面。回到页面核对账号名，再点击“读取课程”。
5. 使用已由 Sites 创建的私有插件“雨课堂学习桥”。若当前对话未出现工具，可新建对话并在插件选择器中选中它。

MCP 路径：`/mcp`。平台负责身份认证和插件连接，普通网页登录凭证不等于 MCP OAuth 身份。

## 当前能力与边界

- 已实现课程列表、课程活动、已知 presentation_id 的页面目录和课件图片读取。
- 不猜测 `classroom_id`、`lesson_id`、`courseware_id` 与 `presentation_id` 的关系；未返回明确课件标识时仍需适配。
- 部分课件需要课堂令牌，未接入课堂授权时会明确报错。
- 尚未实现老师当前页实时同步。
- 真实账号、同机微信识码及各学校接口仍待用户实测。

## 数据与实现

登录记录以 AES-GCM 加密后存储到 D1，按 Site 作用域 ChatGPT 用户 ID 隔离。运行时密钥 `YKT_SESSION_KEY` 由 Sites Secret 管理，不在源码中。二维码只通过当前授权响应流发送，不写入数据库或日志。

`app/` 为手机界面、账号 API 和 MCP，`lib/` 为雨课堂协议及加密，`db/` 和 `drizzle/` 为数据库 schema 与迁移，`tests/core.test.mjs` 为基础验证。

本目录在 GitHub 中保存云端应用层源码，需放入 Sites 提供的 Vinext starter 中构建；它不是独立的 npm 项目。完整托管源码、构建配置与精确版本已同步到该 Site 的源代码仓库。不要把生产密钥、Cookie、数据库导出或二维码提交到 Git。

验证命令：`node --test tests/core.test.mjs`；在 Sites starter 内执行 TypeScript 检查、构建，并通过 Sites 发布。新增数据库变化应追加迁移，不应改写已应用的 SQL。


# Project Discovery Implementation Plan

> Inline implementation of the user's approved direction; no further questions. Read the design at `docs/superpowers/specs/2026-10-28-project-discovery-design.md` before editing.

**Goal:** 让陌生用户从搜索摘要和 README 首屏看懂雨课堂 AI 笔记 Android App，并能直接下载。

**Architecture:** 修改公开仓库元数据和根 README；现有技术说明迁到 docs；仅复用已验收的真实界面图片。

**Tech Stack:** GitHub Markdown/HTML、GitHub repository API、原始 PNG。

- [x] 保存原 README；将 `## 只有手机：使用私有云端版` 至 `### 0.8.0 阅读布局` 之间的 MCP 内容迁入 `docs/mcp-usage.md`，修正 Android/cloud 相对链接；0.8 系列另存 `docs/releases/android-0.8-history.md` 并注明历史属性。
- [x] 创建 `docs/images/classroom.png`、`notebook.png`、`chat.png`，分别复制正式报告的 `sample-courses-light.png`、`sample-note-reading-large-text.png`、`chat-learning-note-card.png`；在 `docs/images/README.md` 记录云端来源及摘要。
- [x] 重写 `README.md`：标题、简短中英文定位、固定正式 1.0.7 APK/版本/安装说明入口、可换行三图、三步使用、核心功能、模型与权限条件、折叠验证记录、MCP 文档与 MIT 许可。
- [x] 用一次性静态检查解析本批 Markdown/HTML 相对链接与图片，确认文件存在；`git diff --check`；将分支推到 GitHub 后用浏览器核对实际渲染和图片加载。无需 Android 执行或为文档添加产品测试。
- [x] API 更新 About 简介及 10 个准确主题，先保存旧值再回读确认；合并到 main 后重新查看公开页面和正式下载资产。

界面图片不含真实账号资料；不改 App 或正式包构建输入。全部检查完成后只提交本批文档/图片，用户的 `.codex-remote-attachments/` 保持原样。

## 实际验收

2026-10-28：22 个相对链接/图片目标存在，三张 PNG 与云端原图摘要一致，GitHub 主分支首屏和三图已实际打开核对。简介与 10 个主题经 API 回读及公开页面确认；APK 下载 HEAD 返回 200，发布页仍为正式 1.0.7，资产摘要保持已验收的 5ac3a7b4fa7994e98e68c8b65fb94e869c2e091c6478f8756b008aebab08a2d4。只修改 Markdown、截图文件和仓库简介/主题；应用与构建输入不变，没有执行 Android 本地构建或测试。

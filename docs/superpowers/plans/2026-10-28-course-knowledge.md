# Course Knowledge Implementation Plan

> Execute inline with executing-plans; user has already approved the direction and requested no further questions.

**Goal:** 独立章节总览、可核对来源、持续课程范围问答，不覆盖原笔记。

**Architecture:** 在现有 Markdown 笔记、ChatNotebookContext、ViewModel 和限高面板上增加来源段落和可见范围。生成前捕获来源，生成后核验版本与引用并保存独立结果。

**Tech Stack:** Kotlin / Compose / Markwon / JSON / GitHub Actions。

- [x] 在 `CourseKnowledge.kt` 实现来源分段、链接解析/校验、查询排序和完整总览预算；`CourseKnowledgeTest.kt` 验证围栏、真实页码、范围隔离、长文和伪造链接。
- [x] 扩展 `Conversation` 保存可空课程范围，扩展笔记记录总览来源版本；补齐 JSON、备份合并与旧记录兼容。不得默认把无范围聊天绑定某课程。
- [x] 在 `ChatNotebookContext.kt` 将已选范围的最新相关段落、备注及来源链接加入请求；保留已有修改摘要。删除范围必须返回不可用，不回退全库。
- [x] 在 `StudyViewModel.kt` 添加独立总览生成和课程对话入口：`operation` 同步取得所有权，`checkpoint` 后发布；停止/失败不保存残缺总览，生成中来源变化不冒充最新。
- [x] 在 `CourseKnowledgeUi.kt`、笔记库和聊天页提供范围入口与来源面板；通过 Markdown 的内部来源链接转发实现追溯，外部链接保留现有行为。
- [x] 云端新增流程测试与完整回归；核对实际截图、报告及新增风险，记录当前完成项。未通过不发布。证据见 `docs/releases/1.0.7-verification.md`。

验证命令仅云端运行：`gradle --no-daemon testDebugUnitTest assembleDebug assembleDebugAndroidTest`、`lintRelease`、现有 `verify_device.py` 和 Python workflow。本地只使用 `git diff --check`、源文件检查与下载证据检查。

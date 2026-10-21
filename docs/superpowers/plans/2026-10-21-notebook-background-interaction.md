# 笔记、后台跟课与操作手感改进

用户要求直接补齐与课程笔记适配的能力，删除重复卡片，修复页面切换，系统悬浮窗应在离开应用后继续跟课；所有构建和测试在 GitHub Actions 执行。

## 设计依据

- Joplin 回收站、历史和导出：[回收站](https://joplinapp.org/help/apps/trash/)、[历史](https://joplinapp.org/help/apps/note_history/)、[导出](https://joplinapp.org/help/apps/import_export/)。采用可恢复删除、恢复副本和完整笔记归档。
- Obsidian [标签](https://help.obsidian.md/Editing+and+formatting/Tags)及[反向链接](https://help.obsidian.md/Plugins/Backlinks)。采用轻量标签与显式双向关联，不引入新的账号或同步服务。
- Android [前台服务类型](https://developer.android.com/develop/background-work/services/fgs/service-types)及[超时](https://developer.android.com/develop/background-work/services/fgs/timeout)。跟课由 dataSync 前台服务持有，超时保存后暂停，不承诺无限后台运行。

## 实施和验证

1. 单一上课管理入口；单页淡入避免保留可误触的旧页面。
2. 应用级课堂状态、前台服务、权限可撤销的系统悬浮窗，暂停/恢复/结束与持久化。
3. 全宽高清预览、独立矢量图片批注，缩放绘画手势分离，保存与放弃明确。
4. 回收站、历史恢复副本、标签、复习状态、排序、双向关联、模板、文本撤销、Markdown 导入、PDF 导出。
5. 严格校验且只包含笔记的备份，保留现有记录和草稿，保护原图与本机已有同图批注。
6. GitHub Actions 执行单元测试、Android UI/服务测试、固定签名与覆盖安装保留检查；收集截图、PDF和报告。云端合成课堂验证不能代替真实账号、模型响应或厂商手机后台联调。

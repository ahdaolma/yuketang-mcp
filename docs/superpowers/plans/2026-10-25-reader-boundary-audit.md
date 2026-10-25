# 小幅连续回滚与点击原地翻页调查

> **For agentic workers:** Use superpowers:executing-plans inline for this investigation; do not change reading behavior before obtaining a failing reproduction.

**Goal:** 复现用户的小幅上下回退被拉回，以及点击后实际正文没有推进，并针对确认根因比较方案后修正。

**Architecture:** 先保留现有 LazyColumn、原生可选文字与统一命令所有者。只增加 debug 布局日志及实际界面用例，以可见正文坐标、焦点请求、页高变化和命令代次区分根因。

**Tech Stack:** Kotlin / 当前 Compose、AndroidView / TextView、GitHub Actions。禁止本地 Android 构建、测试、模拟器与 ADB。

- [x] 用户确认小幅卡回发生在上下连续滚动；另收录点击第一页仍显示第一页。
- [x] 阅读现有手势与渲染链；横向 56dp 门槛不属于本次上下滚动，不能据此修改阈值。
- [x] 增加 `ReaderBoundaryRegressionTest`：纯文字小幅反向、富文本小幅反向、视口底部折叠光标反向、短页前后连续点击、正文已全部可见但工具留白产生滚动范围时的点击。保留实际内容、坐标和原生视图断言。
- [ ] 用 `reader-audit.yml` 的 `boundary` / API 35 云端执行 1.0.3 行为基线，保存 XML、ReaderMotion 页高/位置日志和 ReaderFocus 矩形请求。
- [ ] 通过日志区分：异步 Markdown 使重现页缩高、原生光标请求可视区域、旧恢复任务回写；检查点击是否仅在尾部留白推进。记录具体触发顺序后再设计修正。
- [ ] 比较修正方案的根因覆盖、新风险、长笔记内存/排版成本、选字/搜索兼容，实测通过后执行完整云端回归再发布。

## 已查证的官方依据与候选方案

[Android 官方 Lazy layouts 文档](https://developer.android.com/develop/ui/compose/lists#tips-on-using-lazy-layouts) 建议异步加载前后维持相同条目尺寸，以保持滚动位置；项目当前先用原 Markdown 占位，格式化后隐藏链接等内容可能改变高度，这是待日志验证的假设，不是既定根因。

[TextView 官方接口](https://developer.android.com/reference/android/widget/TextView#requestRectangleOnScreen(android.graphics.Rect,boolean)) 允许为光标请求滚动到可视区域；现有 debug 日志保留该请求，不能先全局禁用焦点/矩形请求而破坏选词与无障碍。

| 候选方向 | 适用证据 | 需要排除的代价 |
| --- | --- | --- |
| 保持已排版条目的尺寸/内容稳定，或使用同宽的真实格式化测量 | 小幅反向时前页高度缩小，与回弹时间对应 | 字号/宽度/主题/标记变化的失效、缓存体积；不能恢复已否定的零宽预测量 |
| 限制非用户意图的焦点回滚 | 回弹伴随旧光标 rectangle 请求，页高不变 | 真实选区、光标导航、精确查找和无障碍仍须工作 |
| 修正有效正文末尾与尾部留白的推进边界 | 所有实际首页面已可见，但点击仅滚过留白 | 不能跳过长页末行、图片或被悬浮工具遮挡的内容 |
| 改成非惰性整本文档或全面换阅读容器 | 仅在现有架构无法保证稳定性且有实测证据时考虑 | 整本预排版/原图内存与长笔记首开成本；不能因只有两页就把长笔记性能风险留给用户 |

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

补充取舍：[LazyListState 官方接口](https://developer.android.com/reference/kotlin/androidx/compose/foundation/lazy/LazyListState) 明确 `requestScrollToItem` 会取消当前滚动，`dispatchRawDelta` 绕过互斥和嵌套滚动、手动调用容易损害体验。因此不应以排版后强制回写位置来补偿每次手势，除非证明不会打断当前拖动；优先消除尺寸/内容不一致的来源。

## 调查运行记录

- `40163906906` / `7734b1b`：新增测试的局部 `height` 遮蔽手势尺寸，编译失败，未执行产品复现。
- `40164188746` / `bc572a4`：5 项中短页 8 轮往返点击通过；3 项回滚测试未进入手势（初始位置错误写入主 Vault，而非独立阅读位置文件）；留白样本超过整屏，未满足准备断言。这 4 项不作为已复现产品缺陷的证据。
- `005705b`：仅修正上述测试准备，产品阅读行为仍与 1.0.3 一致，继续云端基线。
- `40164597787` / `005705b`：5 项中 4 项实际断言失败。纯文字前页由 2034 缩到 1913px，约 82px 反向手势后第二页从 y=200 反向退到 y=140；长链接前页由 2689 缩到 2056px，第二页退到 y=-372。无焦点也能复现，且手势期间无新恢复代次，因此根因是原始 Markdown 占位高度变化。点击留白用例中全部正文末行 y=1522、视口底 y=1542，却只生成同一源页 scroll=206 的帧；真正短页 8 轮双向点击通过。报告 ZIP SHA-256 `a543bf75839bb18dab2103e276b4a568b1237cf92b21b440590d47383f4e32ce`。

## 修正分步与取舍

1. 先仅修复文字测量来源：阅读容器作用域内的可见段落在首次原生测量前生成最终 Markdown spans；仍按原有分段惰性创建，聊天的流式异步格式化保持。相对整本文档预渲染，该方案不引入整本 native 视图；相对每帧位置补偿，不中断滚动或竞逐手势。代价是冷显示段落的格式化在主线程进行，须以 debug `ReaderFormat` 时长及长文本/表格回归评估，未评估前不能认定方案收尾。
2. 不把格式化后的 `Spanned` 跨视图缓存：Markwon 4.6.2 的 `TableRowSpan` 含可变宽高、布局及 invalidator，CorePlugin 还会附带 TextViewSpan；复用有串视图风险。仅组合内 remember 复用格式，先测实际耗时再决定是否需要缓存不可变解析树。
3. 此步不改点击边界、不抑制焦点、不改手势阈值。原 3 项回退及短页往返应转绿，留白用例应仍红，以确认单一变量；随后单独处理有效正文末尾与工具避让。

Markwon 对照：[4.6.2 TableRowSpan](https://github.com/noties/Markwon/blob/v4.6.2/markwon-ext-tables/src/main/java/io/noties/markwon/ext/tables/TableRowSpan.java)、[CorePlugin](https://github.com/noties/Markwon/blob/v4.6.2/markwon-core/src/main/java/io/noties/markwon/core/CorePlugin.java)。

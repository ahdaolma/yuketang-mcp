package cn.studybridge.mobile

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelProvider
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

// Synthetic metadata and cached previews. These tests never log in or request course APIs.
class CourseLibraryUiTest {
    @get:Rule val compose = createAndroidComposeRule<WebFixtureActivity>()
    private lateinit var vm: StudyViewModel
    private fun fixture() = UiState(ready = true, data = Saved(session = "course-fixture", sessionVerified = true), course = Course("1", "遗传学", teacher = "王 · 老师"), activityPage = 2,
        presentations = listOf(Presentation("11", "早期课件", teacher = "王 · 老师", times = listOf(sourceTime(SourceTimeKind.ACTIVITY_PUBLISHED, "2026-10-20 08:00:01", "publish_time"))), Presentation("12", "最新课件", teacher = "王 · 老师", times = listOf(sourceTime(SourceTimeKind.PRESENTATION_CREATED, "2026-10-21 08:00:02", "created_at")))))
    private fun prepare(ui: UiState) {
        compose.activityRule.scenario.onActivity { vm = ViewModelProvider(it)[StudyViewModel::class.java] }
        kotlinx.coroutines.runBlocking { ui.presentations.forEach { p -> vm.previews.load(vm.deckPreviewKey(ui, p)) { Attachment(syntheticSlideImage(), p.title, "") } } }
    }
    private fun diagnostic(name: String, block: () -> Unit) {
        try { block() } catch (failure: Throwable) {
            runCatching {
                val roots = compose.onAllNodes(isRoot(), useUnmergedTree = true)
                roots.fetchSemanticsNodes().indices.forEach { index -> android.util.Log.e("CourseLibraryUiTest", roots[index].printToString()) }
            }
            runCatching { advancedUiShot(compose.activity, "courseware-failure-$name") }
            throw failure
        }
    }
    @Test fun bothSurfacesUseSameSelectionScopeAndHonestLabels() {
        val fixture = fixture()
        prepare(fixture)
        var material by mutableStateOf(false)
        compose.setContent {
            val model by vm.ui.collectAsState()
            val ui = fixture.copy(courseOrder = model.courseOrder, materialsOpen = material, materialPane = "rain")
            StudyTheme(false) { if (material) LearningMaterialsHost(ui, vm) else RainScreen(ui, vm) }
        }
        compose.onNodeWithTag("课件排序").performScrollTo().performClick()
        compose.onNodeWithText("名称顺序").performClick()
        compose.onNodeWithText("排列：名称顺序").assertIsDisplayed()
        compose.onNodeWithText("仅当前第 3 批已加载记录", substring = true).assertIsDisplayed()
        compose.onNodeWithText("早期课件").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("创建时间：未提供", substring = true).assertIsDisplayed()
        compose.onNodeWithText("发布时间：2026-10-20 08:00:01", substring = true).assertIsDisplayed()
        compose.runOnIdle { material = true }
        compose.onNodeWithText("排列：名称顺序").assertIsDisplayed()
        compose.onNodeWithText("仅当前第 3 批已加载记录", substring = true).assertIsDisplayed()
        compose.onNodeWithTag("课件排序").performClick()
        compose.onNodeWithText("按老师分组").performClick()
        compose.onNodeWithText("王 · 老师").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("排列：按老师分组").performScrollTo().assertIsDisplayed()
        compose.onNodeWithContentDescription("关闭学习资料").assertIsDisplayed()
    }
    @Test fun largeFontKeepsLastBatchAndFixedSheetCloseReachable() = diagnostic("large-font") {
        val ui = fixture().copy(materialsOpen = true, materialPane = "rain")
        prepare(ui)
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 2f)) {
                StudyTheme(false) { Box(Modifier.widthIn(max = 340.dp).fillMaxHeight()) { LearningMaterialsHost(ui, vm) } }
            }
        }
        compose.onNodeWithTag("学习资料列表").performScrollToNode(hasText("下一批"))
        compose.onNodeWithText("下一批").assertIsDisplayed()
        compose.onNodeWithContentDescription("关闭学习资料").assertIsDisplayed()
        compose.onNodeWithTag("学习资料列表").performScrollToNode(hasTestTag("课件排序"))
        compose.onNodeWithTag("课件排序").assertIsDisplayed().performClick()
        compose.onNodeWithTag("课件排列列表").performScrollToNode(hasText("按老师分组"))
        compose.onNodeWithText("按老师分组").assertIsDisplayed()
        compose.onNodeWithText("关闭排列").assertIsDisplayed().performClick()
        compose.onNodeWithText("关闭排列").assertDoesNotExist()
    }
    @Test fun splitWidthUsesSingleColumnWithoutClippingMetadataOrLastRow() = diagnostic("split-width") {
        val ui = fixture()
        prepare(ui)
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 2f)) {
                StudyTheme(false) {
                    Box(Modifier.width(340.dp).fillMaxHeight().testTag("分屏课件容器")) {
                        androidx.compose.foundation.lazy.LazyColumn(Modifier.fillMaxSize().testTag("分屏课件列表")) { courseLibraryItems(ui, vm, courseLibraryColumns(340f, 2f)) }
                    }
                }
            }
        }
        compose.onNodeWithText("最新课件").assertIsDisplayed()
        val container = compose.onNodeWithTag("分屏课件容器").getUnclippedBoundsInRoot()
        assertEquals("Fixture must constrain the actual content width", 340f, (container.right - container.left).value, 1f)
        val bounds = compose.onNodeWithText("创建时间：2026-10-21 08:00:02", substring = true).getUnclippedBoundsInRoot()
        assertTrue("Metadata right $bounds must remain inside actual container $container", bounds.right <= container.right)
        assertTrue("Metadata left $bounds must remain inside actual container $container", bounds.left >= container.left)
        compose.onNodeWithTag("分屏课件列表").performScrollToNode(hasText("下一批"))
        compose.onNodeWithText("下一批").assertIsDisplayed()
    }
    @Test fun teacherHeadingsRetainFullNamesAndMissingTeachers() {
        val ui = fixture().copy(course = null, courseOrder = CourseOrder.TEACHER, presentations = emptyList(), courses = listOf(Course("1", "遗传学", teacher = "王 · 老师"), Course("2", "生物化学")))
        prepare(ui)
        compose.setContent { StudyTheme(false) { RainScreen(ui, vm) } }
        compose.onNodeWithText("王 · 老师").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("老师未提供").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("生物化学").assertIsDisplayed()
    }
    private fun shell(command: String): String = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command).let { descriptor -> android.os.ParcelFileDescriptor.AutoCloseInputStream(descriptor).bufferedReader().use { it.readText() } }
    private fun viewport(width: Int, height: Int, block: () -> Unit) {
        check(shell("getprop ro.kernel.qemu").trim() == "1") { "Viewport checks run on disposable cloud emulators only" }
        try {
            shell("wm size ${width}x$height"); shell("wm density 320")
            compose.waitUntil(10000) {
                val metrics = compose.activity.resources.displayMetrics
                kotlin.math.abs(metrics.widthPixels - width) < 16 &&
                    kotlin.math.abs(metrics.density - 2f) < .01f &&
                    compose.activity.lifecycle.currentState == androidx.lifecycle.Lifecycle.State.RESUMED
            }
            block()
        } finally { shell("wm size reset"); shell("wm density reset") }
    }
    @Test fun wideLearningMaterialsSurfaceActuallyPlacesCardsInColumns() = viewport(2560, 1600) {
        val ui = fixture().copy(materialsOpen = true, materialPane = "rain")
        prepare(ui)
        compose.setContent { StudyTheme(false) { LearningMaterialsHost(ui, vm) } }
        compose.onNodeWithText("最新课件").assertIsDisplayed()
        compose.onNodeWithText("早期课件").assertIsDisplayed()
        val newest = compose.onNodeWithText("最新课件").getUnclippedBoundsInRoot()
        val oldest = compose.onNodeWithText("早期课件").getUnclippedBoundsInRoot()
        assertEquals(newest.top.value, oldest.top.value, 1f)
        assertTrue("Actual sheet must allow a second column", newest.right < oldest.left)
        compose.onNodeWithText("创建时间：2026-10-21 08:00:02", substring = true).assertIsDisplayed()
        compose.onNodeWithText("发布时间：2026-10-20 08:00:01", substring = true).assertIsDisplayed()
        compose.onNodeWithContentDescription("关闭学习资料").assertIsDisplayed()
        compose.waitForIdle()
        advancedUiShot(compose.activity, "sample-courseware-tablet-materials")
    }
}

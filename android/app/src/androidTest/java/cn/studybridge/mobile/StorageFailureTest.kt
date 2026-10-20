package cn.studybridge.mobile

import androidx.compose.material3.Text
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File

class StorageFailureTest {
    @get:Rule val compose = createAndroidComposeRule<WebFixtureActivity>()

    @Test fun unreadableEncryptedRecordLocksWritesAndNeverClaimsModelWasSaved() {
        val context = compose.activity
        val vault = Vault(context)
        val previous = vault.load()
        val file = File(context.noBackupFilesDir, "study-v1.enc")
        val broken = byteArrayOf(1, 2, 3)
        try {
            file.writeBytes(broken)
            lateinit var vm: StudyViewModel
            compose.activityRule.scenario.onActivity { vm = ViewModelProvider(it)[StudyViewModel::class.java] }
            compose.setContent { Text("加密记录故障回归") }
            compose.waitUntil(5000) { vm.ui.value.ready }
            assertTrue(vm.ui.value.storageError.contains("无法解密"))
            assertTrue(vm.ui.value.locked)
            compose.runOnIdle {
                assertFalse(vm.saveProvider(Provider(name = "测试", baseUrl = "https://provider.example/v1", key = "fixture-only", model = "test-model")))
                vm.newChat()
            }
            assertFalse(vm.ui.value.notice.contains("已保存"))
            assertArrayEquals(broken, file.readBytes())
        } finally { vault.save(previous) }
    }
}

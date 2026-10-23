package cn.studybridge.mobile

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.OpenInNew
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp

const val PROJECT_URL = "https://github.com/ahdaolma/yuketang-mcp"
const val PROJECT_SOURCE_STATEMENT = "本项目完全开源，原创代码采用 MIT 许可证，允许使用、修改、再分发与商业使用，但须保留版权及许可说明。许可证全文见项目仓库 LICENSE；第三方依赖遵循各自的许可证。"
const val PROJECT_RISK_STATEMENT = "本软件按现状提供，不对服务持续可用、AI 内容准确性或特定用途作保证。在适用法律允许的范围内，开发者不对因使用本软件产生的损失承担责任；依法不能免除的责任不受本声明影响。"

@Composable fun AboutSettings(onOpenProject: () -> Unit) {
    val context = LocalContext.current
    val version = remember(context) {
        @Suppress("DEPRECATION")
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        @Suppress("DEPRECATION")
        info.versionName.orEmpty() to if (android.os.Build.VERSION.SDK_INT >= 28) info.longVersionCode else info.versionCode.toLong()
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 12.dp).testTag("关于页面"), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        StudyPanel {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("雨课伴学", style = MaterialTheme.typography.titleLarge)
                Text("版本 ${version.first}", style = MaterialTheme.typography.bodyLarge)
                Text("构建号 ${version.second}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        FeedbackCard(onClick = onOpenProject, modifier = Modifier.fillMaxWidth().testTag("项目地址")) {
            Row(Modifier.fillMaxWidth().padding(20.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("GitHub 项目地址", style = MaterialTheme.typography.titleSmall)
                    Text(PROJECT_URL, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                }
                Icon(Icons.Outlined.OpenInNew, null, Modifier.size(20.dp))
            }
        }
        StudyPanel {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("项目声明", style = MaterialTheme.typography.titleSmall)
                Text("本项目独立开发，非雨课堂官方客户端。仅处理你本人有权访问的课程与资料。", style = MaterialTheme.typography.bodyMedium)
                Text("开源与许可", style = MaterialTheme.typography.titleSmall)
                Text(PROJECT_SOURCE_STATEMENT, style = MaterialTheme.typography.bodyMedium)
                Text("AI 生成内容用于辅助学习，请结合原始课件核对。", style = MaterialTheme.typography.bodyMedium)
                Text("登录凭据和模型密钥加密保存在本机；发送的课件与提问由你配置的模型服务处理。", style = MaterialTheme.typography.bodyMedium)
                Text("使用风险与责任", style = MaterialTheme.typography.titleSmall)
                Text(PROJECT_RISK_STATEMENT, style = MaterialTheme.typography.bodyMedium)
                Text("请自行评估使用风险，妥善保管账号与密钥，备份重要笔记；遵守课程资料授权、学校及相关平台的使用规则。因违规使用产生的后果由使用者承担。", style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

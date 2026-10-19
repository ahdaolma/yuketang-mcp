package cn.studybridge.mobile

// A received cookie is retained even if the optional profile request is unavailable.
// Only a validated profile sets sessionVerified=true.
fun rememberLogin(data: Saved, region: String, session: String, displayName: String): Saved {
    require(region == data.region && session.matches(Regex("[A-Za-z0-9._-]{8,512}"))) { "登录记录无效或版本已改变。" }
    return data.copy(session = session, username = displayName, sessionVerified = false)
}
fun verifyRememberedLogin(data: Saved, session: String, name: String): Saved {
    if (data.session != session) return data
    return data.copy(username = name, sessionVerified = true)
}
fun loginStatusText(data: Saved, active: Boolean, stage: String): String = when {
    data.session.isNotBlank() && data.sessionVerified -> "已连接：${data.username}"
    data.session.isNotBlank() -> "登录记录已保存，账号待验证"
    active -> stage.ifBlank { "正在连接微信登录通道…" }
    else -> "尚未连接账号"
}

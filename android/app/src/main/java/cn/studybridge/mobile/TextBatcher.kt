package cn.studybridge.mobile

// Coalesce token callbacks without dropping the first chunk or final partial batch.
class TextBatcher(private val clock: () -> Long = System::nanoTime, private val emit: (String) -> Unit) {
    private val pending = StringBuilder()
    private var last = 0L
    private var first = true
    fun append(value: String) {
        if (value.isEmpty()) return
        pending.append(value)
        val now = clock()
        if (first || now - last >= 80_000_000L || pending.length >= 256) { flush(); last = now; first = false }
    }
    fun flush() { if (pending.isNotEmpty()) { val value = pending.toString(); pending.clear(); emit(value) } }
}

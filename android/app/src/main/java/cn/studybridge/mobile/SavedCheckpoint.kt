package cn.studybridge.mobile

// Call under the storage mutex. The transform must be pure and capture fixed IDs/timestamps:
// it is rebased on the latest UI state after IO, preserving events received during the save.
// A failed write never publishes completion or clears the durable pending-send marker.
suspend fun saveBeforePublish(
    snapshot: () -> Saved,
    save: suspend (Saved) -> Unit,
    publish: ((Saved) -> Saved) -> Unit,
    change: (Saved) -> Saved
) {
    val candidate = change(snapshot())
    save(candidate)
    publish(change)
}

package co.tryterra.merged

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class Pr(
    val id: String,
    val repo: String,
    val number: Int,
    val title: String,
    val additions: Int,
    val deletions: Int,
    val mergedAtMs: Long,
    // Details for the Recent view's focus card; 0 / "" when unknown (older snapshots, demo data).
    val createdAtMs: Long = 0,
    val files: Int = 0,
    val commits: Int = 0,
    val reviews: Int = 0,
    val comments: Int = 0,
    val branch: String = "",
    val base: String = "",
    // Lines in generated files (lockfiles, snapshots, …), already subtracted from additions/deletions.
    val genAdds: Int = 0,
    val genDels: Int = 0,
    val genFiles: Int = 0,
    /** Files were checked; merged PRs never change, so once is enough. */
    val genChecked: Boolean = false,
)

data class OpenPr(
    val id: String,
    val repo: String,
    val number: Int,
    val title: String,
    val createdAtMs: Long,
    val updatedAtMs: Long,
    val draft: Boolean,
    /** reviewDecision: APPROVED | CHANGES_REQUESTED | REVIEW_REQUIRED | "" (no branch protection). */
    val review: String,
    val approvals: Int,
    /** Head commit's statusCheckRollup: SUCCESS | FAILURE | ERROR | PENDING | EXPECTED | "" (no checks). */
    val checks: String,
    /** MERGEABLE | CONFLICTING | UNKNOWN (not computed yet — counts as fine). */
    val mergeable: String,
    val autoMerge: Boolean,
    val branch: String = "",
    val base: String = "",
) {
    enum class Light { GREEN, RED, AMBER, OFF }
    /** [word]: the row's first word on OPEN (status first, so ellipsizing never eats it). */
    enum class Status(val text: String, val word: String, val light: Light) {
        READY("READY TO MERGE", "READY", Light.GREEN), FAILING("CHECKS FAILED", "FAILING", Light.RED),
        CONFLICT("MERGE CONFLICT", "CONFLICT", Light.RED), CHANGES("CHANGES REQUESTED", "CHANGES", Light.RED),
        RUNNING("CHECKS RUNNING", "RUNNING", Light.AMBER), WAITING("WAITING ON REVIEW", "WAITING", Light.AMBER),
        DRAFT("DRAFT", "DRAFT", Light.OFF),
    }

    /** Checks had failed (the rescue watch list, UX-PASS #19). */
    val failing: Boolean get() = checks == "FAILURE" || checks == "ERROR"

    val status: Status get() = when {
        draft -> Status.DRAFT
        checks == "FAILURE" || checks == "ERROR" -> Status.FAILING
        mergeable == "CONFLICTING" -> Status.CONFLICT
        review == "CHANGES_REQUESTED" -> Status.CHANGES
        (review == "APPROVED" || review.isEmpty() && approvals > 0) && (checks == "SUCCESS" || checks.isEmpty()) -> Status.READY
        checks == "PENDING" || checks == "EXPECTED" -> Status.RUNNING
        else -> Status.WAITING
    }
}

/** Last successful fetch. Persisted so the screen renders instantly on relaunch. */
data class Snapshot(
    val login: String,
    val prs: List<Pr>,
    val windowCount: Int,
    val ytd: Int,
    val lifetime: Int,
    val fetchedAtMs: Long,
    val open: List<OpenPr> = emptyList(),
    /** issueCount of the open search — may exceed open.size. */
    val openCount: Int = 0,
) {
    fun toJson(): String = JSONObject()
        .put("login", login)
        .put("windowCount", windowCount)
        .put("ytd", ytd)
        .put("lifetime", lifetime)
        .put("fetchedAtMs", fetchedAtMs)
        .put("openCount", openCount)
        .put("prs", JSONArray().apply {
            prs.forEach {
                put(JSONObject()
                    .put("id", it.id).put("repo", it.repo).put("number", it.number)
                    .put("title", it.title).put("additions", it.additions)
                    .put("deletions", it.deletions).put("mergedAtMs", it.mergedAtMs)
                    .put("createdAtMs", it.createdAtMs).put("files", it.files).put("commits", it.commits)
                    .put("reviews", it.reviews).put("comments", it.comments).put("branch", it.branch).put("base", it.base)
                    .put("genAdds", it.genAdds).put("genDels", it.genDels).put("genFiles", it.genFiles)
                    .put("genChecked", it.genChecked))
            }
        })
        .put("open", JSONArray().apply {
            open.forEach {
                put(JSONObject()
                    .put("id", it.id).put("repo", it.repo).put("number", it.number).put("title", it.title)
                    .put("createdAtMs", it.createdAtMs).put("updatedAtMs", it.updatedAtMs).put("draft", it.draft)
                    .put("review", it.review).put("approvals", it.approvals).put("checks", it.checks)
                    .put("mergeable", it.mergeable).put("autoMerge", it.autoMerge)
                    .put("branch", it.branch).put("base", it.base))
            }
        })
        .toString()

    companion object {
        fun fromJson(s: String): Snapshot {
            val j = JSONObject(s)
            val arr = j.getJSONArray("prs")
            val open = j.optJSONArray("open") ?: JSONArray()
            return Snapshot(
                login = j.optString("login"),
                windowCount = j.getInt("windowCount"),
                ytd = j.getInt("ytd"),
                lifetime = j.getInt("lifetime"),
                fetchedAtMs = j.getLong("fetchedAtMs"),
                prs = List(arr.length()) { i ->
                    val o = arr.getJSONObject(i)
                    Pr(o.getString("id"), o.getString("repo"), o.getInt("number"), o.getString("title"),
                        o.getInt("additions"), o.getInt("deletions"), o.getLong("mergedAtMs"),
                        o.optLong("createdAtMs"), o.optInt("files"), o.optInt("commits"), o.optInt("reviews"),
                        o.optInt("comments"), o.optString("branch"), o.optString("base"),
                        o.optInt("genAdds"), o.optInt("genDels"), o.optInt("genFiles"), o.optBoolean("genChecked"))
                },
                open = List(open.length()) { i ->
                    val o = open.getJSONObject(i)
                    OpenPr(o.getString("id"), o.getString("repo"), o.getInt("number"), o.getString("title"),
                        o.optLong("createdAtMs"), o.optLong("updatedAtMs"), o.optBoolean("draft"), o.optString("review"),
                        o.optInt("approvals"), o.optString("checks"), o.optString("mergeable"), o.optBoolean("autoMerge"),
                        o.optString("branch"), o.optString("base"))
                },
                openCount = j.optInt("openCount"),
            )
        }

        private fun file(ctx: Context) = File(ctx.filesDir, "snapshot.json")

        fun load(ctx: Context): Snapshot? =
            runCatching { fromJson(file(ctx).readText()) }.getOrNull()

        fun clear(ctx: Context) { file(ctx).delete() }

        fun save(ctx: Context, s: Snapshot) {
            val f = file(ctx)
            val tmp = File(f.path + ".tmp")
            tmp.writeText(s.toJson())
            tmp.renameTo(f)
        }
    }
}

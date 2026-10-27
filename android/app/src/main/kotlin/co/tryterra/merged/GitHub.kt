package co.tryterra.merged

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

/**
 * GitHub over plain HttpURLConnection + org.json (both in the platform).
 * Blocking — call from a background thread.
 */
object GitHub {

    class AuthError : Exception("GitHub rejected the token")

    // ---- Device Flow (https://docs.github.com/apps/oauth-apps/building-oauth-apps/authorizing-oauth-apps#device-flow)

    data class DeviceCode(
        val deviceCode: String,
        val userCode: String,
        val verificationUri: String,
        val intervalSec: Int,
        val expiresAtMs: Long,
    )

    sealed interface Poll {
        data object Pending : Poll
        data object SlowDown : Poll
        data class Token(val token: String) : Poll
        data class Failed(val reason: String) : Poll
    }

    /** `repo` is the only OAuth scope that lets search see private-repo PRs. */
    fun requestDeviceCode(clientId: String): DeviceCode {
        val j = postForm("https://github.com/login/device/code", "client_id" to clientId, "scope" to "repo")
        j.optString("error").takeIf { it.isNotEmpty() }?.let {
            error(j.optString("error_description", it))
        }
        return DeviceCode(
            deviceCode = j.getString("device_code"),
            userCode = j.getString("user_code"),
            verificationUri = j.getString("verification_uri"),
            intervalSec = j.optInt("interval", 5),
            expiresAtMs = System.currentTimeMillis() + j.optLong("expires_in", 900) * 1000,
        )
    }

    fun pollToken(clientId: String, deviceCode: String): Poll {
        val j = postForm(
            "https://github.com/login/oauth/access_token",
            "client_id" to clientId,
            "device_code" to deviceCode,
            "grant_type" to "urn:ietf:params:oauth:grant-type:device_code",
        )
        j.optString("access_token").takeIf { it.isNotEmpty() }?.let { return Poll.Token(it) }
        return when (val e = j.optString("error")) {
            "authorization_pending" -> Poll.Pending
            "slow_down" -> Poll.SlowDown
            "expired_token" -> Poll.Failed("Code expired — try again")
            "access_denied" -> Poll.Failed("Sign-in was cancelled")
            else -> Poll.Failed(j.optString("error_description", e.ifEmpty { "unknown error" }))
        }
    }

    // ---- Merged PRs

    data class Fetched(
        val login: String,
        val prs: List<Pr>,
        /** issueCount for the window — exceeds prs.size only if the 1000-result search cap hit. */
        val windowCount: Int,
        val ytd: Int,
        val lifetime: Int,
        /** The 50 most recently updated open PRs (null: not asked for); openCount is the search's issueCount. */
        val open: List<OpenPr>?,
        val openCount: Int,
    )

    /**
     * Every PR authored by the token's user and merged at/after [since], plus
     * year-to-date and lifetime merged counts. One GraphQL call per 100 PRs;
     * GitHub search stops at 1000 results, so we stop there too. Then one call
     * for the open PRs: as a fourth search in QUERY it took 8.5–10.8 s against
     * GitHub's 10 s GraphQL timeout (HTTP 502); apart, each is ~4 s.
     */
    fun fetchMerged(token: String, since: OffsetDateTime, yearStart: OffsetDateTime, withOpen: Boolean = true): Fetched {
        val base = "author:@me is:pr is:merged"
        val vars = JSONObject()
            .put("w", "$base merged:>=${since.format(ISO)}")
            .put("y", "$base merged:>=${yearStart.format(ISO)}")
            .put("a", base)
        val prs = ArrayList<Pr>()
        var login = ""
        var windowCount = 0
        var ytd = 0
        var lifetime = 0
        var after: String? = null
        for (page in 0 until 10) {
            vars.put("after", after ?: JSONObject.NULL)
            val data = graphql(token, QUERY, vars)
            login = data.getJSONObject("viewer").getString("login")
            ytd = data.getJSONObject("ytd").getInt("issueCount")
            lifetime = data.getJSONObject("all").getInt("issueCount")
            val w = data.getJSONObject("window")
            windowCount = w.getInt("issueCount")
            val nodes = w.getJSONArray("nodes")
            for (i in 0 until nodes.length()) {
                // GitHub nulls hits (or their repository) it won't show this token — SSO /
                // OAuth-app-restricted orgs — and reports them as partial errors. Skip those.
                val n = nodes.optJSONObject(i) ?: continue
                if (!n.has("id")) continue
                val repo = n.optJSONObject("repository")?.optString("nameWithOwner").orEmpty()
                if (repo.isEmpty()) continue
                prs += Pr(
                    id = n.getString("id"),
                    repo = repo,
                    number = n.getInt("number"),
                    title = n.getString("title"),
                    additions = n.getInt("additions"),
                    deletions = n.getInt("deletions"),
                    mergedAtMs = Instant.parse(n.getString("mergedAt")).toEpochMilli(),
                    createdAtMs = n.optString("createdAt").takeIf { it.isNotEmpty() }?.let { Instant.parse(it).toEpochMilli() } ?: 0,
                    files = n.optInt("changedFiles"),
                    commits = n.optJSONObject("commits")?.optInt("totalCount") ?: 0,
                    reviews = n.optJSONObject("reviews")?.optInt("totalCount") ?: 0,
                    comments = n.optJSONObject("comments")?.optInt("totalCount") ?: 0,
                    branch = n.optString("headRefName"),
                    base = n.optString("baseRefName"),
                )
            }
            val info = w.getJSONObject("pageInfo")
            if (!info.getBoolean("hasNextPage")) break
            after = info.getString("endCursor")
        }
        if (!withOpen) return Fetched(login, prs.distinctBy { it.id }, windowCount, ytd, lifetime, null, 0)
        // The open search failing (it's slow) mustn't throw away the merged counts: the caller keeps the last list.
        val o = runCatching {
            graphql(token, OPEN_QUERY, JSONObject().put("q", "author:@me is:pr is:open archived:false sort:updated-desc")).getJSONObject("open")
        }.getOrElse { return Fetched(login, prs.distinctBy { it.id }, windowCount, ytd, lifetime, null, 0) }
        val open = ArrayList<OpenPr>()
        val nodes = o.getJSONArray("nodes")
        for (i in 0 until nodes.length()) {
            val n = nodes.optJSONObject(i) ?: continue
            if (!n.has("id")) continue
            val repo = n.optJSONObject("repository")?.optString("nameWithOwner").orEmpty()
            if (repo.isEmpty()) continue
            open += OpenPr(
                id = n.getString("id"),
                repo = repo,
                number = n.getInt("number"),
                title = n.getString("title"),
                createdAtMs = Instant.parse(n.getString("createdAt")).toEpochMilli(),
                updatedAtMs = Instant.parse(n.getString("updatedAt")).toEpochMilli(),
                draft = n.optBoolean("isDraft"),
                review = if (n.isNull("reviewDecision")) "" else n.getString("reviewDecision"),
                approvals = n.optJSONObject("reviews")?.optInt("totalCount") ?: 0,
                checks = n.optJSONObject("commits")?.optJSONArray("nodes")?.optJSONObject(0)?.optJSONObject("commit")
                    ?.optJSONObject("statusCheckRollup")?.optString("state").orEmpty(),
                mergeable = n.optString("mergeable"),
                autoMerge = !n.isNull("autoMergeRequest"),
                branch = n.optString("headRefName"),
                base = n.optString("baseRefName"),
            )
        }
        return Fetched(login, prs.distinctBy { it.id }, windowCount, ytd, lifetime, open, o.getInt("issueCount"))
    }

    // ---- Generated files (lockfiles, snapshots, build output): their lines don't count

    class Gen(val adds: Int, val dels: Int, val files: Int)

    private val GENERATED = Regex(
        """(^|/)(package-lock\.json|yarn\.lock|pnpm-lock\.yaml|bun\.lockb?|Cargo\.lock|poetry\.lock|Pipfile\.lock|""" +
            """uv\.lock|Gemfile\.lock|composer\.lock|go\.sum|Podfile\.lock|pubspec\.lock|mix\.lock|flake\.lock|""" +
            """gradle\.lockfile|packages\.lock\.json)$|(^|/)(__snapshots__|vendor|node_modules)/|(^|/)dist/.*\.(m?js|cjs|css)$|""" +
            """\.snap$|\.min\.(js|css)$|\.map$|\.pb\.go$|_pb2\.pyi?$|\.generated\.|\.g\.dart$""",
    )

    fun isGenerated(path: String) = GENERATED.containsMatchIn(path)

    /** Generated-file lines per PR id, 50 PRs per call; a PR past 100 files pages on by itself (≤ 30 pages). */
    fun generated(token: String, ids: List<String>): Map<String, Gen> {
        val out = HashMap<String, Gen>()
        for (batch in ids.chunked(50)) {
            // A failing batch or page leaves just those PRs unchecked (retried next poll); the rest still count.
            val nodes = runCatching {
                graphql(token, GEN_QUERY, JSONObject().put("ids", JSONArray(batch)).put("after", JSONObject.NULL)).getJSONArray("nodes")
            }.getOrNull() ?: continue
            for (i in 0 until nodes.length()) {
                val n = nodes.optJSONObject(i) ?: continue
                val id = n.optString("id")
                var files = n.optJSONObject("files") ?: continue // partial error: stays unchecked, retried next poll
                var adds = 0
                var dels = 0
                var count = 0
                val complete = runCatching { for (page in 1..30) {
                    val fs = files.getJSONArray("nodes")
                    for (j in 0 until fs.length()) {
                        val f = fs.getJSONObject(j)
                        if (!isGenerated(f.getString("path"))) continue
                        adds += f.getInt("additions")
                        dels += f.getInt("deletions")
                        count++
                    }
                    val info = files.getJSONObject("pageInfo")
                    if (!info.getBoolean("hasNextPage") || page == 30) break
                    files = graphql(token, FILES_QUERY, JSONObject().put("id", id).put("after", info.getString("endCursor")))
                        .getJSONObject("node").getJSONObject("files")
                } }.isSuccess
                if (complete) out[id] = Gen(adds, dels, count)
            }
        }
        return out
    }

    // ---- History backfill

    /**
     * Merged-PR count for every local day in [from]..[to] (0 included), from mergedAt alone.
     * Pages one search instead of a per-day alias each (90 aliases timed out); a range over
     * the 1000-hit search cap is split in half.
     */
    fun mergedDays(token: String, from: LocalDate, to: LocalDate, zone: ZoneId): Map<LocalDate, Int> {
        val days = HashMap<LocalDate, Int>()
        var d = from
        while (!d.isAfter(to)) { days[d] = 0; d = d.plusDays(1) }
        fun scan(a: LocalDate, b: LocalDate) {
            val q = "author:@me is:pr is:merged merged:${a.atStartOfDay(zone).format(ISO)}.." +
                b.plusDays(1).atStartOfDay(zone).minusSeconds(1).format(ISO)
            val vars = JSONObject().put("q", q)
            var after: String? = null
            for (page in 0 until 10) {
                vars.put("after", after ?: JSONObject.NULL)
                val s = graphql(token, DAYS_QUERY, vars).getJSONObject("search")
                if (page == 0 && s.getInt("issueCount") > 1000 && a < b) {
                    val mid = a.plusDays(ChronoUnit.DAYS.between(a, b) / 2)
                    scan(a, mid)
                    scan(mid.plusDays(1), b)
                    return
                }
                val nodes = s.getJSONArray("nodes")
                for (i in 0 until nodes.length()) {
                    val at = nodes.optJSONObject(i)?.optString("mergedAt").orEmpty()
                    if (at.isEmpty()) continue
                    days.computeIfPresent(Instant.parse(at).atZone(zone).toLocalDate()) { _, n -> n + 1 }
                }
                val info = s.getJSONObject("pageInfo")
                if (!info.getBoolean("hasNextPage")) break
                after = info.getString("endCursor")
            }
        }
        scan(from, to)
        return days
    }

    private val ISO = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssXXX")

    private val QUERY = """
        query(${'$'}w: String!, ${'$'}y: String!, ${'$'}a: String!, ${'$'}after: String) {
          viewer { login }
          window: search(query: ${'$'}w, type: ISSUE, first: 100, after: ${'$'}after) {
            issueCount
            pageInfo { hasNextPage endCursor }
            nodes { ... on PullRequest {
              id number title additions deletions mergedAt createdAt changedFiles headRefName baseRefName
              repository { nameWithOwner } commits { totalCount } reviews { totalCount } comments { totalCount }
            } }
          }
          ytd: search(query: ${'$'}y, type: ISSUE, first: 0) { issueCount }
          all: search(query: ${'$'}a, type: ISSUE, first: 0) { issueCount }
        }
    """.trimIndent()

    private val OPEN_QUERY = """
        query(${'$'}q: String!) {
          open: search(query: ${'$'}q, type: ISSUE, first: 50) {
            issueCount
            nodes { ... on PullRequest {
              id number title createdAt updatedAt isDraft reviewDecision mergeable headRefName baseRefName
              repository { nameWithOwner } autoMergeRequest { enabledAt } reviews(states: APPROVED) { totalCount }
              commits(last: 1) { nodes { commit { statusCheckRollup { state } } } }
            } }
          }
        }
    """.trimIndent()

    private const val FILES = "files(first: 100, after: \$after) { pageInfo { hasNextPage endCursor } nodes { path additions deletions } }"
    private const val GEN_QUERY = "query(\$ids: [ID!]!, \$after: String) { nodes(ids: \$ids) { ... on PullRequest { id $FILES } } }"
    private const val FILES_QUERY = "query(\$id: ID!, \$after: String) { node(id: \$id) { ... on PullRequest { $FILES } } }"

    private val DAYS_QUERY = """
        query(${'$'}q: String!, ${'$'}after: String) {
          search(query: ${'$'}q, type: ISSUE, first: 100, after: ${'$'}after) {
            issueCount
            pageInfo { hasNextPage endCursor }
            nodes { ... on PullRequest { mergedAt } }
          }
        }
    """.trimIndent()

    private fun graphql(token: String, query: String, vars: JSONObject): JSONObject {
        val body = JSONObject().put("query", query).put("variables", vars).toString()
        val (code, text) = post("https://api.github.com/graphql", body, "application/json", token)
        if (code == 401) throw AuthError()
        check(code in 200..299) { "GitHub HTTP $code" }
        val j = JSONObject(text)
        j.optJSONArray("errors")?.let { errs ->
            if (!j.has("data") || j.isNull("data")) error(errs.optJSONObject(0)?.optString("message") ?: "GraphQL error")
        }
        return j.getJSONObject("data")
    }

    private fun postForm(url: String, vararg fields: Pair<String, String>): JSONObject {
        val body = fields.joinToString("&") { (k, v) -> "$k=${URLEncoder.encode(v, "UTF-8")}" }
        val (code, text) = post(url, body, "application/x-www-form-urlencoded", null)
        check(code in 200..299) { "GitHub HTTP $code" }
        return JSONObject(text)
    }

    private fun post(url: String, body: String, contentType: String, token: String?): Pair<Int, String> {
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.requestMethod = "POST"
            c.doOutput = true
            c.connectTimeout = 15_000
            c.readTimeout = 30_000
            c.setRequestProperty("Accept", "application/json")
            c.setRequestProperty("Content-Type", contentType)
            c.setRequestProperty("User-Agent", "merged-rear-screen")
            if (token != null) c.setRequestProperty("Authorization", "Bearer $token")
            c.outputStream.use { it.write(body.toByteArray()) }
            val code = c.responseCode
            val text = (if (code < 400) c.inputStream else c.errorStream)
                ?.bufferedReader()?.use { it.readText() }.orEmpty()
            return code to text
        } finally {
            c.disconnect()
        }
    }
}

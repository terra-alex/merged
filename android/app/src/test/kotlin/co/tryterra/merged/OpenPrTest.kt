package co.tryterra.merged

import co.tryterra.merged.OpenPr.Status.CHANGES
import co.tryterra.merged.OpenPr.Status.CONFLICT
import co.tryterra.merged.OpenPr.Status.DRAFT
import co.tryterra.merged.OpenPr.Status.FAILING
import co.tryterra.merged.OpenPr.Status.READY
import co.tryterra.merged.OpenPr.Status.RUNNING
import co.tryterra.merged.OpenPr.Status.WAITING
import org.junit.Assert.assertEquals
import org.junit.Test

class OpenPrTest {
    private fun pr(draft: Boolean = false, review: String = "REVIEW_REQUIRED", approvals: Int = 0,
                   checks: String = "SUCCESS", mergeable: String = "UNKNOWN") =
        OpenPr("id", "acme/api", 1, "t", 0, 0, draft, review, approvals, checks, mergeable, autoMerge = false).status

    @Test fun status() {
        // first match wins
        assertEquals(DRAFT, pr(draft = true, review = "APPROVED", checks = "FAILURE", mergeable = "CONFLICTING"))
        assertEquals(FAILING, pr(checks = "FAILURE", mergeable = "CONFLICTING"))
        assertEquals(FAILING, pr(checks = "ERROR", review = "CHANGES_REQUESTED"))
        assertEquals(CONFLICT, pr(mergeable = "CONFLICTING", review = "APPROVED"))
        assertEquals(CHANGES, pr(review = "CHANGES_REQUESTED", checks = "PENDING"))
        assertEquals(READY, pr(review = "APPROVED"))
        assertEquals(READY, pr(review = "APPROVED", checks = "", mergeable = "MERGEABLE"))
        assertEquals(READY, pr(review = "", approvals = 1)) // no branch protection: an approval is enough
        assertEquals(RUNNING, pr(review = "APPROVED", checks = "PENDING"))
        assertEquals(RUNNING, pr(checks = "EXPECTED"))
        assertEquals(WAITING, pr(review = "REVIEW_REQUIRED", approvals = 1)) // protection wants more
        assertEquals(WAITING, pr(review = "", approvals = 0, checks = ""))
        assertEquals(WAITING, pr())
    }

    @Test fun generated() {
        listOf(
            "package-lock.json", "web/yarn.lock", "pnpm-lock.yaml", "bun.lock", "bun.lockb", "Cargo.lock", "poetry.lock",
            "Pipfile.lock", "uv.lock", "Gemfile.lock", "composer.lock", "services/api/go.sum", "ios/Podfile.lock",
            "pubspec.lock", "mix.lock", "flake.lock", "gradle.lockfile", "src/App/packages.lock.json",
            "src/__snapshots__/view.test.ts.snap", "__snapshots__/a.json", "ui/button.snap",
            "static/app.min.js", "static/app.min.css", "static/app.js.map", "proto/user.pb.go", "gen/user_pb2.py",
            "gen/user_pb2.pyi", "src/api.generated.ts", "lib/model.g.dart", "dist/index.js", "web/dist/a.css",
            "vendor/github.com/x/y.go", "app/node_modules/left-pad/index.js",
        ).forEach { assertEquals(it, true, GitHub.isGenerated(it)) }
        listOf(
            "package.json", "go.mod", "src/lock.go", "Cargo.toml", "README.md", "src/minify.js", "sitemap.xml",
            "src/distance.go", "services/api/dist/templates/v3/a.gohtml", "vendored.go", "cmd/vendor.go", "docs/snapshots.md", "generated.go", "lib/a.dart",
            "yarn.lock.md", "mygo.sum",
        ).forEach { assertEquals(it, false, GitHub.isGenerated(it)) }
    }
}

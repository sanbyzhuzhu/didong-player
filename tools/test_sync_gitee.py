"""Credential-free regression tests: local bare Git remotes and mocked release APIs only."""
import contextlib
import hashlib
import io
import json
import os
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest import mock
import sync_gitee as sync


def sha(data):
    return hashlib.sha256(data).hexdigest()


class MirrorTests(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory(prefix="didong-sync-test-")
        self.root = Path(self.temporary.name)
        self.source = self.root / "source"
        self.remote = self.root / "gitee.git"
        subprocess.run(["git", "init", "--bare", str(self.remote)], check=True, capture_output=True)
        subprocess.run(["git", "init", "-b", "main", str(self.source)], check=True, capture_output=True)
        self.git("config", "user.name", "Test")
        self.git("config", "user.email", "test@example.invalid")
        self.commit("initial")
        self.git("push", str(self.remote), "main")
        self.initial = self.git("rev-parse", "HEAD")

    def tearDown(self):
        # Git objects may be read-only on Windows; normalize only this owned temporary repository.
        import stat
        for path in self.root.rglob("*"):
            if path.is_file():
                path.chmod(stat.S_IWRITE | stat.S_IREAD)
        self.temporary.cleanup()

    def git(self, *args):
        return subprocess.check_output(["git", *args], cwd=self.source, stderr=subprocess.PIPE).decode().strip()

    def commit(self, content):
        (self.source / "file.txt").write_text(content)
        self.git("add", "file.txt")
        self.git("commit", "-m", content)

    def mirror(self):
        return sync.GitMirror(self.source, str(self.remote))

    def remote_value(self, ref):
        return subprocess.check_output(["git", "--git-dir=" + str(self.remote), "rev-parse", ref]).decode().strip()

    def test_preserves_history_and_is_idempotent(self):
        self.commit("new source")
        commit, _ = self.mirror().sync("HEAD")
        self.assertEqual(self.remote_value("main^{tree}"), self.git("rev-parse", "HEAD^{tree}"))
        self.assertEqual(self.remote_value("main^"), self.initial)
        self.assertNotEqual(commit, self.git("rev-parse", "HEAD"))
        again, _ = self.mirror().sync("HEAD")
        self.assertEqual(commit, again)

    def test_release_tag_uses_release_tree_not_latest_main(self):
        self.git("tag", "v0.0.24")
        release_tree = self.git("rev-parse", "v0.0.24^{tree}")
        self.commit("newer main automation")
        commit, tag_commit = self.mirror().sync("HEAD", "v0.0.24")
        self.assertEqual(self.remote_value("main^{tree}"), self.git("rev-parse", "HEAD^{tree}"))
        self.assertEqual(self.remote_value("v0.0.24^{tree}"), release_tree)
        self.assertNotEqual(commit, tag_commit)
        self.mirror().sync("HEAD", "v0.0.24")

    def test_never_rewrites_existing_conflicting_tag(self):
        self.git("tag", "v0.0.24")
        self.git("push", str(self.remote), "refs/tags/v0.0.24")
        old = self.remote_value("v0.0.24^{tree}")
        self.commit("different release")
        self.git("tag", "-f", "v0.0.24")
        with self.assertRaisesRegex(RuntimeError, "refusing to rewrite"):
            self.mirror().sync("HEAD", "v0.0.24")
        self.assertEqual(self.remote_value("v0.0.24^{tree}"), old)


class FakeRelease(sync.ReleaseSync):
    def __init__(self):
        self.payloads = {"player.apk": b"apk fixture", "release-notes.md": b"notes"}
        sums = "".join(f"{sha(data)}  {name}\n" for name, data in sorted(self.payloads.items()))
        self.payloads["SHA256SUMS.txt"] = sums.encode()
        self.uploads = {}
        self.created = 0
        self.exists = False
        self.fail_after_upload = False

    def api(self, service, method, path, fields=None, upload=None):
        if service == "GitHub":
            assert method == "GET"
            return {"tag_name": "v0.0.24", "name": "Release", "body": "Notes", "draft": False,
                    "prerelease": False, "assets": [{"name": name, "size": len(data),
                    "digest": "sha256:" + sha(data), "browser_download_url": "https://github.test/" + name}
                    for name, data in self.payloads.items()]}
        if path.endswith("/attach_files"):
            if method == "GET":
                return [{"name": name, "browser_download_url": "https://gitee.test/" + name} for name in self.uploads]
            name = Path(upload).name
            if name in self.uploads:
                raise AssertionError("Duplicate upload")
            self.uploads[name] = Path(upload).read_bytes()
            if self.fail_after_upload:
                self.fail_after_upload = False
                raise RuntimeError("Transport interrupted after successful upload")
            return {"name": name}
        if method == "GET":
            if not self.exists:
                raise sync.ApiError("Gitee", method, path, 404)
            return {"id": 7, "name": "Release", "body": "Notes"}
        if method == "POST":
            self.exists = True
            self.created += 1
            return {"id": 7}
        if method == "PATCH":
            return {"id": 7}
        raise AssertionError((service, method, path))

    def download(self, url, destination, expected_size=None):
        name = url.rsplit("/", 1)[1]
        data = self.payloads[name] if "github.test" in url else self.uploads[name]
        Path(destination).write_bytes(data)
        if expected_size is not None:
            assert len(data) == expected_size
        return sha(data)


class ReleaseTests(unittest.TestCase):
    def test_resume_without_duplicate_release_or_assets(self):
        release = FakeRelease()
        release.fail_after_upload = True
        with self.assertRaisesRegex(RuntimeError, "Transport"):
            release.sync("v0.0.24", "mirror-commit")
        release.sync("v0.0.24", "mirror-commit")
        release.sync("v0.0.24", "mirror-commit")
        self.assertEqual(release.created, 1)
        self.assertEqual(release.uploads, release.payloads)

    def test_existing_different_asset_is_not_overwritten(self):
        release = FakeRelease()
        release.exists = True
        release.uploads["player.apk"] = b"other contents"
        with self.assertRaisesRegex(ValueError, "refusing to overwrite"):
            release.sync("v0.0.24", "mirror-commit")
        self.assertEqual(release.uploads["player.apk"], b"other contents")

    def test_wrong_checksum_prevents_any_gitee_release_write(self):
        release = FakeRelease()
        release.payloads["SHA256SUMS.txt"] = ("0" * 64 + "  player.apk\n").encode()
        with self.assertRaisesRegex(ValueError, "do not match"):
            release.sync("v0.0.24", "mirror-commit")
        self.assertEqual(release.created, 0)
        self.assertFalse(release.uploads)

    def test_missing_opt_in_or_secret_never_uses_credentials_or_network(self):
        for environment in ({}, {"GITEE_TOKEN": "test-only"}, {"GITEE_SYNC_ENABLED": "true"}):
            with mock.patch.dict(os.environ, environment, clear=True), mock.patch.object(sync, "GitMirror") as mirror, mock.patch.object(sync, "ReleaseSync") as release:
                self.assertEqual(sync.main([]), 0)
                mirror.assert_not_called()
                release.assert_not_called()

    def test_token_boundaries_and_no_following_api_redirects(self):
        client = sync.ReleaseSync("gitee-test-token", "github-test-token")
        requests = []

        class Response(io.BytesIO):
            pass

        def opened(request, **kwargs):
            requests.append(request)
            return Response(b"{}")

        with mock.patch.object(client.api_opener, "open", side_effect=opened):
            client.api("GitHub", "GET", "/repos/example/repo")
            client.api("Gitee", "GET", "/api/v5/repos/example/repo")
        self.assertEqual(requests[0].get_header("Authorization"), "Bearer github-test-token")
        self.assertNotIn("gitee-test-token", requests[0].full_url)
        self.assertIsNone(requests[1].get_header("Authorization"))
        self.assertNotIn("github-test-token", requests[1].full_url)
        self.assertIsNone(sync.NoRedirect().redirect_request(None, None, 302, "", {}, "https://other.test"))
        with self.assertRaisesRegex(ValueError, "portable ASCII"):
            sync.safe_name("../secret")

    def test_large_upload_timeout_and_github_credential_isolation(self):
        client = sync.ReleaseSync("gitee-test-token", "github-test-token")
        with tempfile.TemporaryDirectory() as temporary:
            asset = Path(temporary) / "player.apk"
            asset.write_bytes(b"fixture")
            with mock.patch.object(client.api_opener, "open", return_value=io.BytesIO(b'{"name":"player.apk"}')) as opened:
                client.api("Gitee", "POST", "/api/v5/repos/example/repo/releases/1/attach_files", upload=asset)
            request = opened.call_args.args[0]
            self.assertEqual(opened.call_args.kwargs["timeout"], 600)
            self.assertIsNone(request.get_header("Authorization"))
            self.assertNotIn(b"github-test-token", request.data)
            self.assertIn(b"gitee-test-token", request.data)


if __name__ == "__main__":
    unittest.main(verbosity=2)

"""Opt-in GitHub -> Gitee sync. Credentials come only from explicitly configured CI environment."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import tempfile
import time
import urllib.error
import urllib.parse
import urllib.request
from validate_update_feed import validate

ROOT = Path(__file__).resolve().parents[1]
GITHUB_REPO = "sanbyzhuzhu/didong-player"
GITEE_REPO = "sanbyzhu/didong-player"
GITEE_REMOTE = f"https://gitee.com/{GITEE_REPO}.git"
MAX_ASSET = 256 * 1024 * 1024
TAG_PATTERN = re.compile(r"v[0-9]+\.[0-9]+\.[0-9]+(?:[-.][A-Za-z0-9.-]+)?\Z")


class GitMirror:
    def __init__(self, root, remote, env=None):
        self.root, self.remote = Path(root), remote
        self.env = dict(os.environ if env is None else env)
        self.env.update(GIT_AUTHOR_NAME="Didong Mirror Bot", GIT_AUTHOR_EMAIL="mirror@didong.invalid",
                        GIT_COMMITTER_NAME="Didong Mirror Bot", GIT_COMMITTER_EMAIL="mirror@didong.invalid")

    def git(self, *args, data=None, optional=False):
        result = subprocess.run(["git", "-c", "credential.helper=", "-c", "http.followRedirects=false", *args],
                                cwd=self.root, env=self.env, input=None if data is None else data.encode("utf-8"),
                                capture_output=True)
        if result.returncode and not optional:
            detail = result.stderr.decode("utf-8", "replace")
            token = self.env.get("GITEE_TOKEN", "")
            if token:
                detail = detail.replace(token, "***")
            raise RuntimeError(f"Git {args[0]} failed: {detail.strip()}")
        return result.returncode, result.stdout.decode("utf-8").strip()

    def value(self, *args, **kwargs):
        return self.git(*args, **kwargs)[1]

    def tree(self, ref):
        return self.value("rev-parse", "--verify", ref + "^{tree}")

    def snapshot(self, source, parent):
        source_commit = self.value("rev-parse", "--verify", source + "^{commit}")
        tree = self.tree(source_commit)
        if self.tree(parent) == tree:
            return parent
        message = f"Mirror GitHub source {source_commit}\n\nSource-Tree: {tree}\nHistory preserved; source contents match GitHub.\n"
        return self.value("commit-tree", tree, "-p", parent, data=message)

    def sync(self, source="origin/main", tag=""):
        if tag and not TAG_PATTERN.fullmatch(tag):
            raise ValueError("Invalid release tag")
        self.git("fetch", "--no-tags", self.remote, "main:refs/remotes/gitee-sync/main")
        parent = self.value("rev-parse", "refs/remotes/gitee-sync/main")
        commit = self.snapshot(source, parent)
        if commit != parent:
            self.git("push", self.remote, f"{commit}:refs/heads/main")
        print("Gitee main source tree:", self.tree(commit), flush=True)
        tag_commit = None
        if tag:
            source_tag = self.value("rev-parse", "--verify", "refs/tags/" + tag + "^{commit}")
            source_tree = self.tree(source_tag)
            remote_tag = self.value("ls-remote", "--tags", self.remote, "refs/tags/" + tag)
            if remote_tag:
                local_ref = "refs/sync/gitee/" + tag
                self.git("fetch", "--no-tags", self.remote, f"refs/tags/{tag}:{local_ref}")
                if self.tree(local_ref) != source_tree:
                    raise RuntimeError(f"Gitee {tag} has a different source tree; refusing to rewrite the tag")
                tag_commit = self.value("rev-parse", local_ref + "^{commit}")
            else:
                tag_commit = self.snapshot(source_tag, commit)
                identity = self.value("var", "GIT_COMMITTER_IDENT")
                tag_object = self.value("mktag", data=f"object {tag_commit}\ntype commit\ntag {tag}\ntagger {identity}\n\nMirror {tag}; GitHub source {source_tag}\n")
                self.git("push", self.remote, f"{tag_object}:refs/tags/{tag}")
            print("Gitee tag verified:", tag, source_tree, flush=True)
        return commit, tag_commit


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, *args, **kwargs):
        return None


class HttpsRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, request, fp, code, message, headers, new_url):
        if urllib.parse.urlsplit(new_url).scheme != "https":
            raise RuntimeError("Refusing a non-HTTPS download redirect")
        return super().redirect_request(request, fp, code, message, headers, new_url)


class ApiError(RuntimeError):
    def __init__(self, service, method, path, status):
        super().__init__(f"{service} API {method} {path}: HTTP {status}")
        self.status = status


class ReleaseSync:
    def __init__(self, gitee_token, github_token=""):
        self.gitee_token, self.github_token = gitee_token, github_token
        self.api_opener = urllib.request.build_opener(NoRedirect())
        self.download_opener = urllib.request.build_opener(HttpsRedirect())

    def api(self, service, method, path, fields=None, upload=None):
        assert service in {"GitHub", "Gitee"} and path.startswith("/") and "?" not in path
        host = "api.github.com" if service == "GitHub" else "gitee.com"
        headers = {"User-Agent": "didong-gitee-sync", "Accept": "application/json"}
        data = None
        url = f"https://{host}" + path
        if service == "GitHub":
            assert method == "GET"  # Never changes GitHub or its secrets.
            if self.github_token:
                headers["Authorization"] = "Bearer " + self.github_token
            headers["X-GitHub-Api-Version"] = "2022-11-28"
        elif method == "GET":
            url += "?" + urllib.parse.urlencode({"access_token": self.gitee_token, "per_page": 100})
        elif upload is None:
            data = urllib.parse.urlencode(dict(fields or {}, access_token=self.gitee_token)).encode("utf-8")
            headers["Content-Type"] = "application/x-www-form-urlencoded"
        else:
            path_file = Path(upload)
            safe_name(path_file.name)
            boundary = "didong-" + os.urandom(16).hex()
            data = (f"--{boundary}\r\nContent-Disposition: form-data; name=\"access_token\"\r\n\r\n{self.gitee_token}\r\n"
                    f"--{boundary}\r\nContent-Disposition: form-data; name=\"file\"; filename=\"{path_file.name}\"\r\n"
                    "Content-Type: application/octet-stream\r\n\r\n").encode("utf-8")
            data += path_file.read_bytes() + f"\r\n--{boundary}--\r\n".encode("ascii")
            headers["Content-Type"] = "multipart/form-data; boundary=" + boundary
        request = urllib.request.Request(url, data=data, headers=headers, method=method)
        for attempt in range(3 if method == "GET" else 1):
            try:
                with self.api_opener.open(request, timeout=120) as response:
                    return json.load(response)
            except urllib.error.HTTPError as error:
                if method != "GET" or error.code not in {429, 500, 502, 503, 504} or attempt == 2:
                    raise ApiError(service, method, path, error.code) from None
            except (urllib.error.URLError, TimeoutError, ConnectionError):
                if method != "GET" or attempt == 2:
                    raise RuntimeError(f"{service} API transport error ({method} {path}); check connectivity and rerun") from None
            time.sleep(2 ** attempt)

    def download(self, url, destination, expected_size=None):
        parsed = urllib.parse.urlsplit(url)
        if parsed.scheme != "https" or parsed.username or parsed.password:
            raise ValueError("Invalid public asset download URL")
        request = urllib.request.Request(url, headers={"User-Agent": "didong-gitee-sync"})
        # Public download only: neither service's credential is forwarded to a CDN.
        with self.download_opener.open(request, timeout=120) as response, open(destination, "wb") as output:
            total = 0
            for chunk in iter(lambda: response.read(1024 * 1024), b""):
                total += len(chunk)
                if total > MAX_ASSET:
                    raise ValueError("Release asset exceeds the 256 MB sync limit")
                output.write(chunk)
        if expected_size is not None and total != expected_size:
            raise ValueError("Incomplete release asset download")
        return hashlib.sha256(Path(destination).read_bytes()).hexdigest()

    def assets(self, release_id):
        assets = self.api("Gitee", "GET", f"/api/v5/repos/{GITEE_REPO}/releases/{release_id}/attach_files")
        if len(assets) == 100:
            raise ValueError("Too many existing Gitee assets; refusing a truncated asset listing")
        indexed = {asset["name"]: asset for asset in assets}
        if len(indexed) != len(assets):
            raise ValueError("Duplicate existing Gitee asset names; inspect the release before retrying")
        return indexed

    def sync(self, tag, target_commit):
        if not TAG_PATTERN.fullmatch(tag):
            raise ValueError("Invalid release tag")
        release = self.api("GitHub", "GET", f"/repos/{GITHUB_REPO}/releases/tags/{tag}")
        if release.get("draft") or release["tag_name"] != tag:
            raise ValueError("Only published GitHub releases may be synchronized")
        with tempfile.TemporaryDirectory(prefix="didong-release-") as temporary:
            directory = Path(temporary)
            assets = release.get("assets", [])
            if not assets or len(assets) >= 30:
                raise ValueError("Missing or potentially truncated GitHub asset list")
            expected = {}
            for asset in assets:
                name = safe_name(asset["name"])
                if name in expected or not 0 < asset["size"] <= MAX_ASSET:
                    raise ValueError("Duplicate or oversized GitHub release asset")
                digest = self.download(asset["browser_download_url"], directory / name, asset["size"])
                if asset.get("digest") and asset["digest"] != "sha256:" + digest:
                    raise ValueError("GitHub asset digest mismatch: " + name)
                expected[name] = digest
            verify_checksums(directory, expected)
            route = f"/api/v5/repos/{GITEE_REPO}/releases"
            try:
                mirrored = self.api("Gitee", "GET", route + "/tags/" + tag)
            except ApiError as error:
                if error.status != 404:
                    raise
                mirrored = None
            fields = {"tag_name": tag, "target_commitish": target_commit, "name": release["name"] or tag,
                      "body": release["body"] or tag, "prerelease": str(bool(release["prerelease"])).lower()}
            if mirrored is None:
                # A transport error may follow a successful POST. Reruns check existing state first.
                mirrored = self.api("Gitee", "POST", route, fields)
            elif (any(str(mirrored.get(key, "")) != str(fields[key]) for key in ("name", "body"))
                  or bool(mirrored.get("prerelease")) != bool(release["prerelease"])):
                self.api("Gitee", "PATCH", route + "/" + str(mirrored["id"]), fields)
            release_id = mirrored["id"]
            existing = self.assets(release_id)
            for name, digest in expected.items():
                found = existing.get(name)
                if found is not None:
                    actual = self.download(found["browser_download_url"], directory / "existing.bin")
                    if actual != digest:
                        raise ValueError("Existing Gitee asset differs; refusing to overwrite: " + name)
                else:
                    uploaded = self.api("Gitee", "POST", route + f"/{release_id}/attach_files", upload=directory / name)
                    if uploaded["name"] != name:
                        raise ValueError("Gitee changed the asset filename; checksums would not match")
            final = self.assets(release_id)
            for name, digest in expected.items():
                if name not in final or self.download(final[name]["browser_download_url"], directory / "verified.bin") != digest:
                    raise ValueError("Gitee public asset verification failed: " + name)
                print("Gitee asset SHA256 verified:", name, flush=True)
            print("Gitee release synchronized:", tag, flush=True)


def safe_name(name):
    if not re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9._-]*", name) or len(name) > 200:
        raise ValueError("Use portable ASCII release filenames (for example release-notes.md)")
    return name


def verify_checksums(directory, actual):
    declared = {}
    for line in (Path(directory) / "SHA256SUMS.txt").read_text(encoding="utf-8").splitlines():
        match = re.fullmatch(r"([0-9a-f]{64})  (.+)", line)
        if match is None:
            raise ValueError("Invalid SHA256SUMS.txt line")
        digest, name = match.groups()
        safe_name(name)
        if name in declared or name == "SHA256SUMS.txt":
            raise ValueError("Duplicate or self-referential checksum entry")
        declared[name] = digest
    if declared != {name: digest for name, digest in actual.items() if name != "SHA256SUMS.txt"}:
        raise ValueError("Release assets and SHA256SUMS.txt do not match")


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--source-ref", default="origin/main")
    parser.add_argument("--release-tag", default="")
    parser.add_argument("--sync-release", action="store_true")
    args = parser.parse_args(argv)
    if os.environ.get("GITEE_SYNC_ENABLED") != "true" or not os.environ.get("GITEE_TOKEN"):
        print("::notice::Gitee sync skipped. Configure a dedicated Actions secret GITEE_TOKEN and repository variable GITEE_SYNC_ENABLED=true. See docs/gitee-sync.md.")
        return 0
    if args.release_tag and not TAG_PATTERN.fullmatch(args.release_tag):
        raise ValueError("Invalid release tag")
    if args.sync_release and not args.release_tag:
        raise ValueError("Release sync requires --release-tag")
    username = os.environ.get("GITEE_USERNAME", "sanbyzhu")
    if not re.fullmatch(r"[A-Za-z0-9_-]+", username):
        raise ValueError("Invalid Gitee username")
    validate(ROOT)
    with tempfile.TemporaryDirectory(prefix="didong-git-auth-") as temporary:
        helper = Path(temporary) / "askpass.sh"
        helper.write_text('#!/bin/sh\ncase "$1" in\n  *gitee.com*Username*|*Username*gitee.com*) printf "%s\\n" "$GITEE_USERNAME" ;;\n  *gitee.com*Password*|*Password*gitee.com*) printf "%s\\n" "$GITEE_TOKEN" ;;\n  *) exit 1 ;;\nesac\n', encoding="utf-8")
        helper.chmod(0o700)
        env = dict(os.environ, GIT_ASKPASS=str(helper), GIT_TERMINAL_PROMPT="0", GCM_INTERACTIVE="never", GITEE_USERNAME=username)
        _, tag_commit = GitMirror(ROOT, GITEE_REMOTE, env).sync(args.source_ref, args.release_tag)
    if args.sync_release:
        ReleaseSync(os.environ["GITEE_TOKEN"], os.environ.get("GITHUB_TOKEN", "")).sync(args.release_tag, tag_commit)
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except (RuntimeError, ValueError, KeyError, OSError) as error:
        # Never print exception URLs or tracebacks containing query-string API credentials.
        text = str(error) if isinstance(error, (RuntimeError, ValueError)) else type(error).__name__
        for key in ("GITEE_TOKEN", "GITHUB_TOKEN"):
            if os.environ.get(key):
                text = text.replace(os.environ[key], "***")
        print("::error::" + text, file=sys.stderr)
        raise SystemExit(1)

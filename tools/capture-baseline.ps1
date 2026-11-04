[CmdletBinding()]
param(
    [string]$SourceParent = (Split-Path (Split-Path $PSScriptRoot -Parent) -Parent),
    [string]$OutputDirectory = "",
    [string]$VerifyAgainst = "",
    [string]$Python = "python"
)
$ErrorActionPreference = "Stop"
if (-not $OutputDirectory) {
    $OutputDirectory = Join-Path (Split-Path $PSScriptRoot -Parent) (
        "out/baseline/" + [DateTime]::UtcNow.ToString("yyyyMMddTHHmmssZ"))
}
# Git receives only read commands; optional index refresh and fsmonitor are disabled.
$program = @'
import datetime, hashlib, json, os, pathlib, stat, subprocess, sys

parent, destination = map(pathlib.Path, sys.argv[1:3])
previous = pathlib.Path(sys.argv[3]) if sys.argv[3] else None
names = ("KernelSU", "KernelSU-Next", "KPatch-Next", "KPatch-Next-Module")
env = dict(os.environ, GIT_OPTIONAL_LOCKS="0", GIT_NO_LAZY_FETCH="1")
env.update(GIT_CONFIG_COUNT="2", GIT_CONFIG_KEY_0="core.fsmonitor",
           GIT_CONFIG_VALUE_0="false", GIT_CONFIG_KEY_1="core.untrackedCache",
           GIT_CONFIG_VALUE_1="false")

def git(root, *args, optional=False):
    p = subprocess.run(["git", "--no-optional-locks", "-C", str(root), *args],
                       env=env, stdout=subprocess.PIPE, stderr=subprocess.PIPE)
    if p.returncode and not optional:
        raise RuntimeError(p.stderr.decode("utf-8", "replace"))
    return p.stdout

def sha(path):
    with path.open("rb") as f:
        return hashlib.file_digest(f, "sha256").hexdigest()

def atomic(path, data):
    temp = path.with_name(path.name + ".part")
    with temp.open("x", encoding="utf-8", newline="\n") as f:
        f.write(data)
    os.replace(temp, path)

def metadata(root):
    paths = set()
    for option in ("--git-dir", "--git-common-dir"):
        p = pathlib.Path(os.fsdecode(git(root, "rev-parse", option)).strip())
        if not p.is_absolute():
            p = root / p
        for name in ("HEAD", "index", "config", "config.worktree", "packed-refs",
                     "shallow", "info/exclude", "logs/HEAD"):
            paths.add(p.resolve() / name)
        for name in ("refs", "logs/refs"):
            folder = p.resolve() / name
            if folder.is_dir():
                paths.update(q for q in folder.rglob("*") if q.is_file())
    return {str(p): sha(p) if p.is_file() else None for p in sorted(paths)}

def entry(root, relative, **extra):
    p = root / relative
    record = dict(path=relative, **extra)
    if p.is_symlink():
        link = os.readlink(p)
        record.update(kind="symlink", target=link,
                      sha256=hashlib.sha256(os.fsencode(link)).hexdigest())
    elif p.is_file():
        before = p.stat()
        record.update(kind="file", size=before.st_size, sha256=sha(p))
        after = p.stat()
        if (before.st_size, before.st_mtime_ns) != (after.st_size, after.st_mtime_ns):
            raise RuntimeError("file changed during capture: " + str(p))
    elif p.is_dir():
        record.update(kind="gitlink-or-directory", sha256=None)
    else:
        record.update(kind="missing", sha256=None)
    return record

destination.mkdir(parents=True, exist_ok=False)
summary = dict(created_utc=datetime.datetime.now(datetime.timezone.utc).isoformat(),
               source_parent=str(parent.resolve()), read_only_git=True,
               repositories=[])
for name in names:
    root = (parent / name).resolve(strict=True)
    before = metadata(root)
    head = git(root, "rev-parse", "HEAD").decode().strip()
    branch = git(root, "symbolic-ref", "-q", "HEAD", optional=True).decode().strip()
    status_raw = git(root, "status", "--porcelain=v2", "-z", "--untracked-files=all")
    tracked = []
    for row in git(root, "ls-files", "--stage", "-z").split(b"\0"):
        if not row:
            continue
        fields, relative = row.split(b"\t", 1)
        mode, oid, stage = fields.decode().split()
        tracked.append(entry(root, os.fsdecode(relative), mode=mode, index_oid=oid,
                             stage=int(stage)))
    untracked = [entry(root, os.fsdecode(p)) for p in
                 git(root, "ls-files", "--others", "--exclude-standard", "-z").split(b"\0")
                 if p]
    after = metadata(root)
    if before != after:
        raise RuntimeError("Git metadata changed during capture: " + name)
    manifest = "".join(json.dumps(r, ensure_ascii=True, sort_keys=True) + "\n"
                       for r in tracked)
    atomic(destination / (name + ".tracked.jsonl"), manifest)
    atomic(destination / (name + ".untracked.jsonl"),
           "".join(json.dumps(r, ensure_ascii=True, sort_keys=True) + "\n"
                   for r in untracked))
    atomic(destination / (name + ".status.json"),
           json.dumps(dict(porcelain_v2_nul=status_raw.decode("utf-8", "surrogateescape")),
                      ensure_ascii=True, indent=2) + "\n")
    info = dict(name=name, root=str(root), head=head, branch=branch,
                tracked_count=len(tracked), untracked_count=len(untracked),
                status_sha256=hashlib.sha256(status_raw).hexdigest(),
                tracked_manifest_sha256=hashlib.sha256(manifest.encode()).hexdigest(),
                git_metadata_before=before, git_metadata_after=after,
                git_metadata_unchanged=True,
                missing_count=sum(r["kind"] == "missing" for r in tracked))
    summary["repositories"].append(info)
    print(f'{name}: HEAD={head} tracked={len(tracked)} '
          f'untracked={len(untracked)} metadata_unchanged=true', flush=True)
atomic(destination / "baseline.json", json.dumps(summary, indent=2) + "\n")
if previous:
    old = json.loads((previous / "baseline.json").read_text(encoding="utf-8"))
    differences = []
    for current, original in zip(summary["repositories"], old["repositories"], strict=True):
        for key in ("name", "root", "head", "branch", "tracked_manifest_sha256",
                    "status_sha256", "git_metadata_after"):
            if current[key] != original[key]:
                differences.append(dict(repository=current["name"], field=key))
        name = current["name"]
        if (destination / (name + ".untracked.jsonl")).read_bytes() != (
                previous / (name + ".untracked.jsonl")).read_bytes():
            differences.append(dict(repository=name, field="untracked_manifest"))
    result = dict(matches=not differences, previous=str(previous), differences=differences)
    atomic(destination / "verification.json", json.dumps(result, indent=2) + "\n")
    print("baseline_comparison=" + str(result["matches"]).lower())
    if differences:
        sys.exit(2)
print("baseline_directory=" + str(destination.resolve()))
'@
$program | & $Python - $SourceParent $OutputDirectory $VerifyAgainst
if ($LASTEXITCODE -ne 0) { throw "Baseline capture failed: exit $LASTEXITCODE" }

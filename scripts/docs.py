"""Reproducible static documentation assembly (Python 3.13)."""
import argparse
import hashlib
import os
from pathlib import Path
import platform
import shutil
import subprocess
import tarfile
import urllib.request

ROOT = Path(__file__).resolve().parent.parent
TOOLS = ROOT / "build/docs-tools"
PROTO = ROOT / "tiket-contracts/src/main/proto"
HASHES = {
    "windows": "8acf0bf64eda29183b4c6745c3c6a12562fd9a8ab08d61788cf56e6659c66b3b",
    "linux": "47cd72b07e6dab3408d686a65d37d3a6ab616da7d8b564b2bd2a2963a72b72fd",
}
def run(*args):
    subprocess.run(args, cwd=ROOT, check=True)

def install():
    system = platform.system().lower()
    if platform.machine().lower() not in ("amd64", "x86_64") or system not in HASHES:
        raise SystemExit("Automatic tool installation supports Windows/Linux x86_64.")
    name = f"protoc-gen-doc_1.5.1_{system}_amd64.tar.gz"
    TOOLS.mkdir(parents=True, exist_ok=True)
    archive = TOOLS / name
    if not archive.exists():
        urllib.request.urlretrieve(f"https://github.com/pseudomuto/protoc-gen-doc/releases/download/v1.5.1/{name}", archive)
    if hashlib.sha256(archive.read_bytes()).hexdigest() != HASHES[system]:
        raise SystemExit("protoc-gen-doc checksum mismatch")
    binary = "protoc-gen-doc.exe" if system == "windows" else "protoc-gen-doc"
    with tarfile.open(archive) as bundle:
        member = next(m for m in bundle.getmembers() if Path(m.name).name == binary)
        (TOOLS / binary).write_bytes(bundle.extractfile(member).read())
    (TOOLS / binary).chmod(0o755)

def generate():
    binary = TOOLS / ("protoc-gen-doc.exe" if os.name == "nt" else "protoc-gen-doc")
    if not binary.exists():
        raise SystemExit("Run python scripts/docs.py install-tools first")
    # Use exactly the protoc resolved by the contracts Gradle build.
    candidates = sorted((Path.home() / ".gradle/caches/modules-2/files-2.1/com.google.protobuf/protoc/4.36.2").glob("**/*"))
    protoc = next((p for p in candidates if p.is_file() and p.suffix in (".exe",) and ("windows" in p.name if os.name == "nt" else "linux-x86_64" in p.name)), None)
    if protoc is None:
        raise SystemExit("Run Gradle :tiket-contracts:generateProto to resolve protoc 4.36.2")
    protoc.chmod(0o755)
    out = ROOT / "build/protobuf-docs"
    out.mkdir(parents=True, exist_ok=True)
    run(str(protoc), f"-I{PROTO.as_posix()}", f"--plugin=protoc-gen-doc={binary}", f"--doc_out={out}", "--doc_opt=markdown,schema.md", *[p.as_posix() for p in sorted(PROTO.rglob("*.proto"))])
    digest = hashlib.sha256()
    for p in sorted(PROTO.rglob("*.proto")):
        digest.update(p.relative_to(PROTO).as_posix().encode())
        digest.update(p.read_bytes().replace(b"\r\n", b"\n"))
    header = "# Generated Protobuf schema\n\n[Usage and security](overview.md) · [Client workflow](examples.md)\n\nSource SHA-256: " + digest.hexdigest() + "\n\n"
    target = ROOT / "docs/api/grpc-reference.md"
    result = header + (out / "schema.md").read_text(encoding="utf-8")
    if ARGS.check:
        if not target.exists() or target.read_text(encoding="utf-8") != result:
            raise SystemExit("Generated schema is stale; regenerate and commit it")
    else:
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_text(result, encoding="utf-8", newline="\n")

def assemble():
    source = ROOT / "build/dokka/html"
    if not (source / "index.html").exists():
        raise SystemExit("Run Gradle :dokkaGenerate first")
    shutil.copytree(source, ROOT / "site/kotlin", dirs_exist_ok=True)

parser = argparse.ArgumentParser(__doc__)
parser.add_argument("action", choices=["install-tools", "proto", "assemble"])
parser.add_argument("--check", action="store_true")
ARGS = parser.parse_args()
{"install-tools": install, "proto": generate, "assemble": assemble}[ARGS.action]()

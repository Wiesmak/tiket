# Build the documentation
Use Python 3.13 (validated locally with 3.13.9), MkDocs 1.6.1/readthedocs and Dokka 2.2.0. Python dependencies, including transitive packages, are pinned with hashes in requirements-docs.txt. protoc-gen-doc 1.5.1 archives are pinned by SHA-256 for Windows/Linux x86_64. Initial dependency/tool installation needs network access; site rendering uses local source.

Install with Python available on PATH:
```sh
python -m venv .venv-docs
# Activate the environment using the shell-specific command below, then:
python -m pip install --require-hashes -r requirements-docs.txt
```
Alternatively use `uv venv --python 3.13.9 .venv-docs` and `uv pip sync requirements-docs.txt --python .venv-docs --require-hashes`.

Activate on PowerShell with `.venv-docs\Scripts\Activate.ps1`, or Bash with `source .venv-docs/bin/activate`. Then:
```sh
python scripts/docs.py install-tools
# Windows: replace ./gradlew with .\gradlew.bat
./gradlew :tiket-contracts:generateProto :dokkaGenerate --no-configuration-cache --max-workers=1 -Pkotlin.compiler.execution.strategy=in-process
python scripts/docs.py proto
python scripts/docs.py proto --check
python scripts/validate-docs.py
node scripts/validate-doc-examples.mjs
mkdocs build --strict
python scripts/docs.py assemble
mkdocs serve
```
`mkdocs serve` previews Markdown. For the combined site, serve `site/` using `python -m http.server --directory site 8000`; it includes Kotlin HTML copied after MkDocs finishes. Build output stays ignored. The committed schema reference is checked by regeneration; do not edit it by hand.

Dokka documents the four reusable Kotlin libraries. Backend implementation classes are intentionally outside the supported library reference. API content comes from checked-in source, never a running backend, database or physical printer. Documentation CI builds a downloadable combined static site and does not deploy it publicly.

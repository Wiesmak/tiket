"""Validate local Markdown links, fragments, navigation and obsolete-document references."""
from html.parser import HTMLParser
from pathlib import Path
import re
import sys
from urllib.parse import unquote, urlsplit
import markdown
import yaml

ROOT = Path(__file__).resolve().parent.parent
DOCS = ROOT / "docs"
errors = []
class Links(HTMLParser):
    def __init__(self, text):
        super().__init__()
        self.links = []
        self.ids = set()
        self.feed(text)
    def handle_starttag(self, tag, attrs):
        a = dict(attrs)
        self.ids.update(v for k, v in attrs if k in ("id", "name") and v)
        for key in ("href", "src"):
            if a.get(key):
                self.links.append(a[key])

def parsed(path):
    return Links(markdown.markdown(path.read_text(encoding="utf-8"), extensions=["tables", "toc", "fenced_code"]))

pages = [ROOT / "README.md", *sorted(DOCS.rglob("*.md"))]
cache = {p.resolve(): parsed(p) for p in pages}
for page in pages:
    source = page.read_text(encoding="utf-8")
    if re.search(r"-----BEGIN (?:RSA |EC |OPENSSH )?PRIVATE KEY-----|eyJ[A-Za-z0-9_-]{20,}\.[A-Za-z0-9_-]{20,}\.[A-Za-z0-9_-]{20,}|gh[pousr]_[A-Za-z0-9]{30,}", source):
        errors.append(f"{page}: possible embedded credential")
    if re.search(r"phase[1-8]-[^ )]+\\.md", source, re.I):
        errors.append(f"{page}: obsolete documentation link")
    for value in cache[page.resolve()].links:
        url = urlsplit(value)
        if url.scheme or url.netloc or value.startswith("/"):
            continue
        target = (page.parent / unquote(url.path)).resolve() if url.path else page.resolve()
        if page.name == "library-api.md" and value == "../kotlin/index.html":
            target = ROOT / "build/dokka/html/index.html"
        if not target.exists():
            errors.append(f"{page}: missing {value}")
        elif url.fragment and target.suffix == ".md":
            anchors = cache.get(target) or parsed(target)
            if unquote(url.fragment) not in anchors.ids:
                errors.append(f"{page}: missing fragment {value}")
def nav_targets(value):
    if isinstance(value, str):
        yield value
    elif isinstance(value, dict):
        for child in value.values():
            yield from nav_targets(child)
    elif isinstance(value, list):
        for child in value:
            yield from nav_targets(child)
config = yaml.safe_load((ROOT / "mkdocs.yml").read_text(encoding="utf-8"))
for target in nav_targets(config["nav"]):
    if not (DOCS / target).is_file():
        errors.append(f"Navigation: {target}")
if "--site" in sys.argv:
    site = ROOT / "site"
    if not (site / "kotlin/index.html").is_file():
        errors.append("Assembled Kotlin index is missing")
    count = 0
    for page in site.rglob("*.html"):
        count += 1
        for value in Links(page.read_text(encoding="utf-8")).links:
            url = urlsplit(value)
            if url.scheme or url.netloc or not url.path or value.startswith("/"):
                continue
            target = (page.parent / unquote(url.path)).resolve()
            if not target.exists():
                errors.append(f"{page}: missing static resource {value}")
    print(f"Checked local resources in {count} assembled HTML pages.")
if errors:
    raise SystemExit("\n".join(errors))
print(f"Validated {len(pages)} Markdown pages, local links/fragments and navigation.")

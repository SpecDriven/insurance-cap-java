#!/usr/bin/env python3
"""Write [test:] links back into spec files from a package of JUnit tests.

Matches each test method's @DisplayName to a scenario heading in the spec named
by the class's `Spec:` javadoc line. Idempotent: replaces existing links, so it
is safe to re-run after line numbers drift.

Usage: link_tests.py <test-package-path>
"""
import re
import sys
from pathlib import Path

SPECS = Path("/Users/me/dev/personal/SpecDriven/specs-insurance-large/specs")
REPO = Path("/Users/me/dev/personal/SpecDriven/insurance-cap-java")
BLOB = "https://github.com/SpecDriven/insurance-cap-java/blob/main"

LINK_RE = re.compile(r"^\[test: .* \]\n", re.MULTILINE)


def normalize(title: str) -> str:
    """Scenario titles compare on their text before any ' — ' qualifier."""
    title = title.split(" — ")[0]
    return re.sub(r"\s+", " ", title).strip().lower()


def collect(pkg: Path):
    """{spec path: {normalized scenario title: (method, line)}}"""
    found = {}
    for java in sorted(pkg.glob("*.java")):
        text = java.read_text()
        spec_match = re.search(r"Spec:\s*(specs/\S+\.feature\.md)", text)
        if not spec_match:
            continue
        spec_path = spec_match.group(1)
        rel = java.relative_to(REPO).as_posix()

        lines = text.splitlines()
        pending = None
        for i, line in enumerate(lines):
            display = re.search(r'@DisplayName\("(.+)"\)', line)
            if display:
                pending = display.group(1)
                continue
            method = re.match(r"\s+(?:void|public void) (\w+)\(\)", line)
            if method and pending:
                key = normalize(pending)
                # The first test for a scenario is the canonical link.
                found.setdefault(spec_path, {}).setdefault(
                    key, (method.group(1), rel, i + 1))
                pending = None
    return found


def apply(found):
    written = 0
    for spec_path, scenarios in found.items():
        file = SPECS.parent / spec_path
        if not file.exists():
            print(f"  ! missing spec {spec_path}")
            continue
        text = LINK_RE.sub("", file.read_text())
        lines = text.split("\n")

        # The link goes at the END of the scenario's block — after its bullets
        # and any table — not directly under the heading.
        out = []
        pending = None  # (method, rel, lineno) for the scenario being read
        for line in lines:
            heading = re.match(r"^## (.+?)\s*\[(published|proposed)\]\s*$", line)
            if heading:
                if pending:
                    out.extend(["", f"[test: {pending[0]} : {BLOB}/{pending[1]}"
                                    f"#L{pending[2]} ]", ""])
                    written += 1
                    pending = None
                while out and out[-1] == "":
                    out.pop()
                if out:
                    out.append("")
                out.append(line)
                pending = scenarios.get(normalize(heading.group(1)))
                continue
            out.append(line)

        if pending:
            while out and out[-1] == "":
                out.pop()
            out.extend(["", f"[test: {pending[0]} : {BLOB}/{pending[1]}"
                            f"#L{pending[2]} ]"])
            written += 1

        while out and out[-1] == "":
            out.pop()
        # Never leave a run of blank lines behind where a link was removed.
        collapsed = re.sub(r"\n{3,}", "\n\n", "\n".join(out))
        file.write_text(collapsed + "\n")
    return written


def verify(found):
    """Each #L<n> must land on `void <method>(`."""
    bad = 0
    for spec_path in found:
        file = SPECS.parent / spec_path
        for link in re.finditer(r"\[test: (\w+) : \S+/(srv/\S+?)#L(\d+) \]",
                                file.read_text()):
            method, rel, lineno = link.group(1), link.group(2), int(link.group(3))
            target = (REPO / rel).read_text().splitlines()[lineno - 1]
            if f"void {method}(" not in target:
                print(f"  ! {spec_path}: L{lineno} is not void {method}(")
                bad += 1
    return bad


if __name__ == "__main__":
    pkg = Path(sys.argv[1]).resolve()
    found = collect(pkg)
    total = sum(len(v) for v in found.values())
    print(f"matched {total} scenarios across {len(found)} spec files")
    print(f"wrote {apply(found)} links")
    bad = verify(found)
    print("verify: OK" if bad == 0 else f"verify: {bad} BROKEN")
    sys.exit(1 if bad else 0)

#!/usr/bin/env python3
"""Build the static test and coverage report bundle published by GitHub Actions."""

from __future__ import annotations

import argparse
import hashlib
import html
import json
import re
import shutil
import urllib.parse
import xml.etree.ElementTree as ET
from pathlib import Path

SAFE_NAME_PATTERN = re.compile(r"[^A-Za-z0-9._-]")
COLLISION_HASH_BYTES = 4


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser()
    parser.add_argument("--root", type=Path, default=Path.cwd())
    parser.add_argument("--output", type=Path, default=Path("target/gh-pages"))
    return parser.parse_args()


def safe_name(value: str) -> str:
    return SAFE_NAME_PATTERN.sub("_", value or "root")


def module_name(path: Path, root: Path) -> str:
    relative = path.resolve().relative_to(root.resolve())
    parts = relative.parts
    try:
        target_index = parts.index("target")
    except ValueError:
        return "root"
    return "/".join(parts[:target_index]) or "root"


def write_json(path: Path, payload: dict[str, object]) -> None:
    path.write_text(json.dumps(payload), encoding="utf-8")


def prepare_test_reports(root: Path, tests_dir: Path) -> None:
    candidates = [
        root / "target/reports/surefire.html",
        root / "target/site/surefire-report.html",
    ]
    candidates.extend(sorted(root.glob("**/target/reports/surefire.html")))
    candidates.extend(sorted(root.glob("**/target/site/surefire-report.html")))

    unique_candidates: list[Path] = []
    seen: set[Path] = set()
    for candidate in candidates:
        resolved = candidate.resolve()
        if candidate.exists() and resolved not in seen:
            seen.add(resolved)
            unique_candidates.append(candidate)

    module_links: list[tuple[str, str]] = []
    if unique_candidates:
        preferred = unique_candidates[0]
        shutil.copy2(preferred, tests_dir / "surefire-report.html")
        for report in unique_candidates[1:]:
            owner = module_name(report, root)
            destination_name = safe_name(owner)
            destination = tests_dir / destination_name
            destination.mkdir(parents=True, exist_ok=True)
            shutil.copy2(report, destination / "surefire-report.html")
            module_links.append((owner, destination_name))
    else:
        (tests_dir / "surefire-report.html").write_text(
            "<html><body><h1>Surefire report not available</h1></body></html>",
            encoding="utf-8",
        )

    links = "\n".join(
        f'<li><a href="{urllib.parse.quote(destination)}/surefire-report.html">'
        f"{html.escape(owner)}</a></li>"
        for owner, destination in sorted(module_links)
    )
    (tests_dir / "index.html").write_text(
        "<!doctype html><html><head><meta charset=\"utf-8\">"
        "<title>Surefire reports</title></head><body><h1>Surefire reports</h1><ul>"
        f'<li><a href="surefire-report.html">aggregate</a></li>{links}'
        "</ul></body></html>",
        encoding="utf-8",
    )

    total_tests = total_failures = total_errors = total_skipped = 0
    report_files = sorted(root.glob("**/target/surefire-reports/TEST-*.xml"))
    report_files.extend(sorted(root.glob("**/target/failsafe-reports/TEST-*.xml")))
    for report in report_files:
        try:
            document = ET.parse(report).getroot()
        except ET.ParseError as exception:
            print(f"Skipping malformed test report {report}: {exception}")
            continue
        total_tests += int(document.get("tests", 0))
        total_failures += int(document.get("failures", 0))
        total_errors += int(document.get("errors", 0))
        total_skipped += int(document.get("skipped", 0))

    if report_files:
        failed = total_failures + total_errors
        passed = max(total_tests - failed - total_skipped, 0)
        message = f"{failed}/{total_tests} failed" if failed else f"{passed} passed"
        color = "red" if failed else "brightgreen"
    else:
        message = "unknown"
        color = "lightgrey"
    write_json(
        tests_dir / "badge.json",
        {"schemaVersion": 1, "label": "tests", "message": message, "color": color},
    )


def prepare_coverage_reports(root: Path, coverage_dir: Path) -> None:
    jacoco_xml_paths = sorted(root.glob("**/target/site/jacoco/jacoco.xml"))
    covered = missed = 0
    for report in jacoco_xml_paths:
        document = ET.parse(report).getroot()
        for counter in document.iter("counter"):
            if counter.get("type") == "LINE":
                covered += int(counter.get("covered", 0))
                missed += int(counter.get("missed", 0))

    total = covered + missed
    if total:
        percentage = round(covered / total * 100)
        color = "brightgreen" if percentage >= 80 else "yellow" if percentage >= 60 else "red"
        message = f"{percentage}%"
    else:
        color = "lightgrey"
        message = "unknown"
    write_json(
        coverage_dir / "badge.json",
        {"schemaVersion": 1, "label": "coverage", "message": message, "color": color},
    )

    links: list[tuple[str, str]] = []
    used_destinations: set[str] = set()
    for jacoco_dir in sorted(root.glob("**/target/site/jacoco")):
        owner = module_name(jacoco_dir, root)
        destination_name = safe_name(owner)
        if destination_name in used_destinations:
            suffix = hashlib.blake2s(
                owner.encode("utf-8"), digest_size=COLLISION_HASH_BYTES
            ).hexdigest()
            destination_name = f"{destination_name}-{suffix}"
        used_destinations.add(destination_name)
        destination = coverage_dir / destination_name
        shutil.copytree(jacoco_dir, destination)
        links.append((owner, destination_name))

    link_markup = "\n".join(
        f'<li><a href="{urllib.parse.quote(destination)}/index.html">'
        f"{html.escape(owner)}</a></li>"
        for owner, destination in sorted(links)
    )
    body = (
        f"<h1>JaCoCo Coverage Reports</h1><ul>{link_markup}</ul>"
        if links
        else "<h1>No coverage report generated</h1>"
    )
    (coverage_dir / "index.html").write_text(
        "<!doctype html><html><head><meta charset=\"utf-8\">"
        f"<title>Coverage reports</title></head><body>{body}</body></html>",
        encoding="utf-8",
    )


def main() -> None:
    arguments = parse_args()
    root = arguments.root.resolve()
    output = arguments.output
    if not output.is_absolute():
        output = root / output
    if output.exists():
        shutil.rmtree(output)
    tests_dir = output / "tests"
    coverage_dir = output / "coverage"
    tests_dir.mkdir(parents=True)
    coverage_dir.mkdir(parents=True)

    prepare_test_reports(root, tests_dir)
    prepare_coverage_reports(root, coverage_dir)


if __name__ == "__main__":
    main()

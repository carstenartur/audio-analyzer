#!/usr/bin/env python3
"""Keep CITATION.cff and .zenodo.json aligned with the project version."""

from __future__ import annotations

import argparse
import datetime as dt
import json
from pathlib import Path
import re


def set_cff_key(text: str, key: str, value: str) -> str:
    line = f'{key}: "{value}"'
    pattern = rf'^{re.escape(key)}: .*$'
    if re.search(pattern, text, flags=re.MULTILINE):
        return re.sub(pattern, line, text, flags=re.MULTILINE)
    if not text.endswith("\n"):
        text += "\n"
    return text + line + "\n"


def remove_cff_key(text: str, key: str) -> str:
    return re.sub(rf'^{re.escape(key)}: .*\n?', "", text, flags=re.MULTILINE)


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("version")
    parser.add_argument(
        "--release",
        action="store_true",
        help="Add release-date fields; otherwise remove release-only date fields.",
    )
    args = parser.parse_args()

    release_date = dt.date.today().isoformat()

    citation = Path("CITATION.cff")
    citation_text = citation.read_text(encoding="utf-8")
    citation_text = set_cff_key(citation_text, "version", args.version)
    if args.release:
        citation_text = set_cff_key(citation_text, "date-released", release_date)
    else:
        citation_text = remove_cff_key(citation_text, "date-released")
    citation.write_text(citation_text, encoding="utf-8")

    zenodo = Path(".zenodo.json")
    zenodo_data = json.loads(zenodo.read_text(encoding="utf-8"))
    zenodo_data["version"] = args.version
    if args.release:
        zenodo_data["publication_date"] = release_date
    else:
        zenodo_data.pop("publication_date", None)
    zenodo.write_text(
        json.dumps(zenodo_data, indent=2, ensure_ascii=False) + "\n",
        encoding="utf-8",
    )


if __name__ == "__main__":
    main()

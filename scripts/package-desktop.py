#!/usr/bin/env python3
"""Build a desktop image or native installer with preview-only .audioexp association."""

import argparse
import json
import os
import pathlib
import re
import shutil
import subprocess
import sys
import tempfile
import xml.etree.ElementTree as ET
import zipfile


LAUNCHER = "org.hammer.ExperimentWorkbenchLauncher"


def native_version(version):
    release = version.split("-", 1)[0]
    if not re.fullmatch(r"\d+(?:\.\d+){0,2}", release):
        raise ValueError(f"version cannot be represented by a native installer: {version}")
    return release


def stage_application(checkout, version, destination):
    target = checkout / "audio-app/target"
    jar = target / f"audio-app-{version}.jar"
    if not jar.is_file():
        raise ValueError(f"missing desktop JAR: {jar}; run mvn clean verify first")
    libraries = sorted((target / "lib").glob("*.jar"))
    if not libraries:
        raise ValueError("no packaged runtime libraries; run the complete Maven reactor first")
    with zipfile.ZipFile(jar) as archive:
        manifest = archive.read("META-INF/MANIFEST.MF").decode("utf-8")
    manifest = manifest.replace("\r\n ", "").replace("\n ", "")
    if not re.search(r"^Main-Class: " + re.escape(LAUNCHER) + r"\s*$", manifest, re.MULTILINE):
        raise ValueError("desktop artifact does not use the safe document preview launcher")
    shutil.copy2(jar, destination / "audio-analyzer.jar")
    (destination / "lib").mkdir()
    for library in libraries:
        shutil.copy2(library, destination / "lib" / library.name)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--type", default="app-image", choices=("app-image", "deb", "rpm", "dmg", "pkg", "exe", "msi"))
    parser.add_argument("--destination", type=pathlib.Path)
    parser.add_argument("--dry-run", action="store_true", help="validate package input and print the command without invoking jpackage")
    args = parser.parse_args()
    checkout = pathlib.Path(__file__).resolve().parents[1]
    root = ET.parse(checkout / "pom.xml").getroot()
    version = root.findtext("{http://maven.apache.org/POM/4.0.0}version")
    selected = native_version(version)
    supported = ({"deb", "rpm"} if sys.platform.startswith("linux") else
                 {"dmg", "pkg"} if sys.platform == "darwin" else {"exe", "msi"})
    if args.type != "app-image" and args.type not in supported:
        parser.error(f"build {args.type} on its native operating system")
    destination = (args.destination or checkout / "target/desktop").resolve()
    java_home = os.environ.get("JAVA_HOME")
    jpackage = (pathlib.Path(java_home) / "bin" / ("jpackage.exe" if os.name == "nt" else "jpackage")) if java_home else pathlib.Path(shutil.which("jpackage") or "jpackage")
    with tempfile.TemporaryDirectory(prefix="audio-desktop-") as staging:
        stage_application(checkout, version, pathlib.Path(staging))
        icon_extension = "png" if sys.platform.startswith("linux") else "icns" if sys.platform == "darwin" else "ico"
        icon = checkout / f"packaging/desktop/audioexp.{icon_extension}"
        command = [str(jpackage), "--type", args.type, "--name", "AudioAnalyzer",
                   "--app-version", selected, "--vendor", "Audio Analyzer contributors",
                   "--description", "Audio experiment workbench and safe document preview",
                   "--input", staging, "--main-jar", "audio-analyzer.jar",
                   "--main-class", LAUNCHER, "--dest", str(destination), "--icon", str(icon),
                   "--java-options", "-Dfile.encoding=UTF-8"]
        if args.type != "app-image":
            association = pathlib.Path(staging).parent / (pathlib.Path(staging).name + ".properties")
            descriptor = (checkout / "packaging/desktop/audioexp.properties").read_text()
            association.write_text(descriptor + "\nicon=" + icon.resolve().as_posix() + "\n")
            command.extend(["--file-associations", str(association)])
            if sys.platform.startswith("linux"):
                command.extend(["--linux-shortcut", "--linux-app-category", "Science"])
            elif sys.platform == "darwin":
                command.extend(["--mac-package-identifier", "io.github.carstenartur.audioanalyzer"])
            else:
                command.extend(["--win-per-user-install", "--win-menu", "--win-shortcut"])
        try:
            if args.dry_run:
                print(json.dumps({"applicationVersion": version, "packageVersion": selected,
                                  "association": args.type != "app-image", "command": command}, indent=2))
            else:
                subprocess.run(command, check=True)
        finally:
            if args.type != "app-image":
                association.unlink(missing_ok=True)


if __name__ == "__main__":
    main()

#!/usr/bin/env python3
"""Verify the selected storage runtime and exercise the actual packaged application."""

import argparse
import io
import json
import pathlib
import re
import subprocess
import tempfile
import time
import urllib.error
import urllib.request
import xml.etree.ElementTree as ET
import zipfile


NAMESPACE = {"m": "http://maven.apache.org/POM/4.0.0"}
STORAGE_GROUP = "io.github.carstenartur"
STORAGE_PREFIX = "jgit-storage-hibernate-"


def read_resolved_dependencies(contents):
    """Read the flat resolved artifact list; display trees may omit mediated nodes."""
    dependencies = []
    for line in contents.splitlines():
        coordinate = line.strip().split(" -- ", 1)[0]
        if ":" not in coordinate or coordinate == "The following files have been resolved:":
            continue
        parts = coordinate.split(":")
        if len(parts) not in (5, 6) or any(not re.fullmatch(r"[A-Za-z0-9_.+\-]+", part) for part in parts):
            raise ValueError(f"invalid resolved runtime coordinate: {coordinate}")
        group, artifact, kind = parts[:3]
        version, scope = parts[-2:]
        classifier = parts[3] if len(parts) == 6 else ""
        filename = f"{artifact}-{version}" + (f"-{classifier}" if classifier else "") + f".{kind}"
        dependencies.append({"groupId": group, "artifactId": artifact, "version": version,
                             "scope": scope, "type": kind, "classifier": classifier,
                             "filename": filename})
    if not dependencies:
        raise ValueError("empty resolved runtime dependency list")
    return {"children": dependencies}


def declared_storage_versions(checkout, candidate=None):
    parent = ET.parse(checkout / "pom.xml").getroot()
    properties = {element.tag.rsplit("}", 1)[-1]: element.text
                  for element in parent.find("m:properties", NAMESPACE)}
    result = {}
    for pom in sorted(checkout.glob("audio-*/pom.xml")):
        root = ET.parse(pom).getroot()
        local = dict(properties)
        for element in root.findall("m:properties/*", NAMESPACE):
            local[element.tag.rsplit("}", 1)[-1]] = element.text
        for item in root.findall("m:dependencies/m:dependency", NAMESPACE):
            group = item.findtext("m:groupId", namespaces=NAMESPACE)
            artifact = item.findtext("m:artifactId", namespaces=NAMESPACE)
            if group != STORAGE_GROUP or not artifact.startswith(STORAGE_PREFIX):
                continue
            version = item.findtext("m:version", namespaces=NAMESPACE) or ""
            for _ in range(10):
                updated = re.sub(r"\$\{([^}]+)\}", lambda match: local.get(match[1], match[0]), version)
                if updated == version:
                    break
                version = updated
            if not version or "${" in version:
                raise ValueError(f"unresolved storage version for {artifact} in {pom}")
            if artifact in result and result[artifact] != version:
                raise ValueError(f"conflicting declarations for {artifact}")
            if candidate and version != candidate:
                raise ValueError(f"{artifact} declares {version}, expected substituted candidate {candidate}")
            result[artifact] = version
    if not result:
        raise ValueError("no declared storage modules found")
    return result


def verify_dependencies(tree, allowed, expected):
    resolved = []
    seen_storage = set()

    def visit(item):
        group, artifact = item["groupId"], item["artifactId"]
        coordinate = f"{group}:{artifact}"
        version = item["version"]
        classifier = item.get("classifier", "").lower()
        test_classifier = re.search(r"(^|[-_.])(tests?|testfixtures?|benchmarks?|jmh)([-_.]|$)", classifier)
        if ("benchmark" in artifact.lower() or artifact.lower().endswith(("-test", "-tests", "-test-fixtures"))
                or item.get("type") == "test-jar" or test_classifier
                or group in {"junit", "org.testng", "org.assertj", "org.hamcrest"}
                or group.startswith(("org.junit", "org.mockito", "org.testcontainers", "org.openjdk.jmh"))
                or item.get("scope") == "test"):
            raise ValueError(f"test-only runtime dependency: {coordinate}:{version}")
        if coordinate not in allowed:
            raise ValueError(f"unselected runtime dependency: {coordinate}:{version}")
        if group == STORAGE_GROUP and artifact.startswith(STORAGE_PREFIX):
            if artifact not in expected:
                raise ValueError(f"undeclared storage module: {artifact}")
            if version != expected[artifact]:
                raise ValueError(f"{artifact} resolved {version}, expected {expected[artifact]}")
            seen_storage.add(artifact)
        resolved.append({"coordinate": coordinate, "version": version,
                         "scope": item.get("scope", "compile"),
                         "filename": item.get("filename", f"{artifact}-{version}.jar")})
        for child in item.get("children", []):
            visit(child)

    for child in tree.get("children", []):
        visit(child)
    missing = sorted(set(expected) - seen_storage)
    if missing:
        raise ValueError(f"missing declared storage modules: {', '.join(missing)}")
    return resolved


def verify_packaged_dependencies(archive, resolved, allowed):
    expected = {item["filename"] for item in resolved}
    # Spring Boot's packager adds its own reviewed jarmode helper, outside the Maven runtime graph.
    jarmode = "org.springframework.boot:spring-boot-jarmode-tools"
    if jarmode in allowed:
        for item in resolved:
            if item["coordinate"] == "org.springframework.boot:spring-boot":
                expected.add(f"spring-boot-jarmode-tools-{item['version']}.jar")
    with zipfile.ZipFile(archive) as packaged:
        actual = {name.removeprefix("BOOT-INF/lib/") for name in packaged.namelist()
                  if name.startswith("BOOT-INF/lib/") and name.endswith(".jar")}
    unexpected = sorted(actual - expected)
    if unexpected:
        raise ValueError(f"unselected packaged dependency: {', '.join(unexpected)}")
    storage = {item["filename"] for item in resolved
               if item["coordinate"].startswith(STORAGE_GROUP + ":" + STORAGE_PREFIX)}
    missing = sorted(storage - actual)
    if missing:
        raise ValueError(f"declared storage absent from packaged runtime: {', '.join(missing)}")
    return sorted(actual)


def verify_archive(path):
    def inspect(source, label, depth=0):
        if depth > 3:
            raise ValueError(f"unexpected nested archive depth: {label}")
        with zipfile.ZipFile(source) as archive:
            for entry in archive.infolist():
                name = entry.filename
                if "jgit/storage/hibernate/benchmark/" in name or "jgit-storage-hibernate-benchmarks" in name:
                    raise ValueError(f"benchmark content in {label}: {name}")
                if name.endswith(".jar"):
                    if entry.file_size > 512 * 1024 * 1024:
                        raise ValueError(f"oversized nested archive in {label}: {name}")
                    inspect(io.BytesIO(archive.read(entry)), f"{label}!/{name}", depth + 1)

    inspect(path, str(path))


def comparable_projection(projection):
    """The storage DSL sorts graph entities by ID; all entity contents must remain exact."""
    return {**projection, "nodes": sorted(projection["nodes"], key=lambda item: item["id"]),
            "edges": sorted(projection["edges"], key=lambda item: item["id"])}


def exercise_storage(opener, base_url, projection, evidence):
    def request(path, body=None):
        data = None if body is None else json.dumps(body).encode("utf-8")
        query = urllib.request.Request(base_url + path, data=data,
                                       headers={"Content-Type": "application/json"})
        with opener.open(query, timeout=20) as response:
            return json.load(response)

    checkpoint = request("/workflow/checkpoints", {
        "branch": "consumer-contract", "author": "runtime-contract",
        "message": "Packaged storage round trip", "timestamp": "2026-01-01T00:00:00Z"})
    commit = checkpoint.get("commitId")
    if not isinstance(commit, str) or not commit:
        raise ValueError("packaged storage did not create a commit")
    history = request("/workflow/history?branch=consumer-contract")
    if not history or history[0].get("commitId") != commit:
        raise ValueError("packaged storage history did not return the saved commit")
    loaded = request("/workflow/load", {"commitId": commit})
    (evidence / "packaged-storage-roundtrip.json").write_text(
        json.dumps({"checkpoint": checkpoint, "history": history, "projection": loaded}, indent=2) + "\n")
    if comparable_projection(loaded) != comparable_projection(projection):
        raise ValueError("packaged storage changed the workflow during checkpoint/load")


def smoke_packaged_application(archive, evidence):
    """Start with a fresh isolated H2 database and check initialized HTTP projection."""
    log_path = evidence / "packaged-runtime.log"
    opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))
    with tempfile.TemporaryDirectory(prefix="audio-storage-contract-") as folder:
        database = pathlib.Path(folder, "workflow").as_posix()
        arguments = ["java", "-jar", str(archive.resolve()), "--server.address=127.0.0.1",
                     "--server.port=0", "--workbench.persistence.mode=hibernate",
                     "--workbench.persistence.repository-name=consumer-contract",
                     "--workbench.history-search.directory-type=local-heap",
                     "--workbench.persistence.migrations.enabled=true",
                     "--workbench.persistence.schema-action=validate",
                     f"--spring.datasource.url=jdbc:h2:file:{database};DB_CLOSE_ON_EXIT=FALSE",
                     "--spring.datasource.driver-class-name=org.h2.Driver",
                     "--spring.datasource.username=sa", "--spring.datasource.password="]
        with log_path.open("w", encoding="utf-8") as log:
            process = subprocess.Popen(arguments, stdout=log, stderr=subprocess.STDOUT)
            try:
                deadline = time.monotonic() + 120
                while time.monotonic() < deadline:
                    if process.poll() is not None:
                        raise ValueError(f"packaged runtime exited {process.returncode}; see {log_path}")
                    output = log_path.read_text(encoding="utf-8", errors="replace")
                    ports = re.findall(r"Tomcat started on port (\d+)", output)
                    if ports:
                        base_url = f"http://127.0.0.1:{ports[-1]}"
                        try:
                            with opener.open(base_url + "/workflow/projection", timeout=2) as response:
                                projection = json.load(response)
                        except (urllib.error.URLError, TimeoutError):
                            time.sleep(0.25)
                            continue
                        if not isinstance(projection, dict):
                            raise ValueError("packaged runtime returned a non-object projection")
                        (evidence / "packaged-projection.json").write_text(
                            json.dumps(projection, indent=2) + "\n", encoding="utf-8")
                        exercise_storage(opener, base_url, projection, evidence)
                        return
                    time.sleep(0.25)
                raise ValueError(f"packaged runtime startup timed out; see {log_path}")
            finally:
                process.terminate()
                try:
                    process.wait(timeout=10)
                except subprocess.TimeoutExpired:
                    process.kill()
                    process.wait(timeout=10)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--checkout", type=pathlib.Path, required=True)
    parser.add_argument("--evidence", type=pathlib.Path, required=True)
    parser.add_argument("--candidate")
    args = parser.parse_args()
    checkout, evidence = args.checkout.resolve(), args.evidence.resolve()
    expected = declared_storage_versions(checkout, args.candidate)
    allowlist = checkout / ".github/jgit-storage-hibernate-runtime-allowlist.txt"
    allowed = {line.strip() for line in allowlist.read_text().splitlines()
               if line.strip() and not line.lstrip().startswith("#")}
    # Retain the navigable tree as evidence; validate the complete, resolved flat list.
    json.loads((evidence / "dependency-tree.json").read_text())
    tree = read_resolved_dependencies((evidence / "runtime-dependencies.txt").read_text())
    resolved = verify_dependencies(tree, allowed, expected)
    archives = sorted((checkout / "audio-app/target").glob("*.jar"))
    archives = [archive for archive in archives if not archive.name.endswith(("-sources.jar", "-javadoc.jar"))]
    packaged = [archive for archive in archives if archive.name.endswith("-workbench.jar")]
    if len(packaged) != 1:
        raise ValueError(f"expected one packaged workbench JAR, found {len(packaged)}")
    for archive in archives:
        verify_archive(archive)
    packaged_dependencies = verify_packaged_dependencies(packaged[0], resolved, allowed)
    (evidence / "packaged-dependencies.txt").write_text("\n".join(packaged_dependencies) + "\n")
    (evidence / "application-jars.txt").write_text("\n".join(map(str, archives)) + "\n")
    smoke_packaged_application(packaged[0], evidence)
    (evidence / "result.json").write_text(json.dumps({
        "consumer": "audio-analyzer", "mode": "candidate" if args.candidate else "baseline", "java": "21",
        "candidateVersion": args.candidate, "storageModules": expected,
        "runtimeDependencies": resolved, "packagedRuntime": "passed",
    }, indent=2) + "\n")


if __name__ == "__main__":
    main()

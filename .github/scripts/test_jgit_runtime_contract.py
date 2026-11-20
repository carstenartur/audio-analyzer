"""Behavioral fixtures for the consumer's dependency and packaged-runtime contract."""

import importlib.util
import io
import pathlib
import tempfile
import unittest
import zipfile


SCRIPT = pathlib.Path(__file__).with_name("verify_jgit_runtime_contract.py")
SPEC = importlib.util.spec_from_file_location("runtime_contract", SCRIPT)
CONTRACT = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(CONTRACT)


def dependency(artifact, version="0.12.2", group="io.github.carstenartur", children=()):
    return {"groupId": group, "artifactId": artifact, "version": version,
            "type": "jar", "scope": "compile", "children": list(children)}


def archive(entries):
    data = io.BytesIO()
    with zipfile.ZipFile(data, "w") as jar:
        for name, content in entries.items():
            jar.writestr(name, content)
    return data.getvalue()


class RuntimeContractTest(unittest.TestCase):
    def setUp(self):
        self.expected = {"jgit-storage-hibernate-core": "0.12.2",
                         "jgit-storage-hibernate-search": "0.12.2"}
        self.allowed = {"io.github.carstenartur:" + artifact for artifact in self.expected}
        self.tree = dependency("audio-app", "0.1.0-SNAPSHOT", "audioin", [
            dependency("jgit-storage-hibernate-core"),
            dependency("jgit-storage-hibernate-search")])

    def test_selected_modules_are_accepted(self):
        result = CONTRACT.verify_dependencies(self.tree, self.allowed, self.expected)
        self.assertEqual(2, len(result))

    def test_one_matching_module_does_not_hide_a_mixed_version(self):
        self.tree["children"][1]["version"] = "0.11.0"
        with self.assertRaisesRegex(ValueError, "jgit-storage-hibernate-search.*0.11.0"):
            CONTRACT.verify_dependencies(self.tree, self.allowed, self.expected)

    def test_missing_declared_module_is_rejected(self):
        self.tree["children"].pop()
        with self.assertRaisesRegex(ValueError, "missing.*search"):
            CONTRACT.verify_dependencies(self.tree, self.allowed, self.expected)

    def test_unintended_heavy_transitive_is_rejected(self):
        self.tree["children"][0]["children"] = [dependency("elasticsearch", "9.0", "org.elasticsearch")]
        with self.assertRaisesRegex(ValueError, "unselected runtime dependency.*elasticsearch"):
            CONTRACT.verify_dependencies(self.tree, self.allowed, self.expected)

    def test_benchmark_artifact_is_rejected_even_if_allowlisted(self):
        artifact = "jgit-storage-hibernate-benchmarks"
        self.tree["children"].append(dependency(artifact))
        self.allowed.add("io.github.carstenartur:" + artifact)
        with self.assertRaisesRegex(ValueError, "test-only"):
            CONTRACT.verify_dependencies(self.tree, self.allowed, self.expected)

    def test_test_classifier_or_type_cannot_hide_behind_an_allowed_coordinate(self):
        for kind, classifier in [("jar", "tests"), ("jar", "test-fixtures"), ("jar", "benchmarks"),
                                 ("test-jar", "")]:
            with self.subTest(kind=kind, classifier=classifier):
                extra = dependency("runtime", "1.0", "org.example")
                extra.update({"type": kind, "classifier": classifier})
                self.allowed.add("org.example:runtime")
                with self.assertRaisesRegex(ValueError, "test-only"):
                    CONTRACT.verify_dependencies({"children": self.tree["children"] + [extra]}, self.allowed, self.expected)

    def test_legacy_junit_remains_forbidden_when_allowlisted(self):
        self.allowed.add("junit:junit")
        self.tree["children"].append(dependency("junit", "4.13.2", "junit"))
        with self.assertRaisesRegex(ValueError, "test-only"):
            CONTRACT.verify_dependencies(self.tree, self.allowed, self.expected)

    def test_nested_benchmark_classes_are_rejected(self):
        nested = archive({"org/eclipse/jgit/storage/hibernate/benchmark/Bad.class": b"class"})
        with tempfile.TemporaryDirectory() as folder:
            jar = pathlib.Path(folder, "workbench.jar")
            jar.write_bytes(archive({"BOOT-INF/lib/innocent.jar": nested}))
            with self.assertRaisesRegex(ValueError, "benchmark.*innocent.jar"):
                CONTRACT.verify_archive(jar)

    def test_normal_nested_archive_is_accepted(self):
        nested = archive({"org/example/Storage.class": b"class"})
        with tempfile.TemporaryDirectory() as folder:
            jar = pathlib.Path(folder, "workbench.jar")
            jar.write_bytes(archive({"BOOT-INF/lib/storage.jar": nested}))
            CONTRACT.verify_archive(jar)

    def test_resolved_list_includes_runtime_omitted_from_display_tree(self):
        result = CONTRACT.read_resolved_dependencies("""
The following files have been resolved:
   org.springframework:spring-core:jar:7.0.9:compile -- module spring.core [auto]
   example:native:jar:linux-x86_64:1.0:runtime
""")
        self.assertEqual("spring-core-7.0.9.jar", result["children"][0]["filename"])
        self.assertEqual("native-1.0-linux-x86_64.jar", result["children"][1]["filename"])

    def test_unresolved_or_empty_runtime_list_is_rejected(self):
        with self.assertRaisesRegex(ValueError, "empty resolved runtime"):
            CONTRACT.read_resolved_dependencies("The following files have been resolved:\n none\n")

    def test_packaged_dependency_not_in_resolved_runtime_is_rejected(self):
        with tempfile.TemporaryDirectory() as folder:
            jar = pathlib.Path(folder, "workbench.jar")
            jar.write_bytes(archive({"BOOT-INF/lib/unreviewed-driver-1.0.jar": archive({})}))
            with self.assertRaisesRegex(ValueError, "unselected packaged dependency"):
                CONTRACT.verify_packaged_dependencies(jar, [], set())

    def test_roundtrip_accepts_canonical_graph_order_without_ignoring_content(self):
        original = {"workflowId": "w", "nodes": [{"id": "b", "type": "gain"}, {"id": "a"}],
                    "edges": [{"id": "z"}, {"id": "x"}]}
        reordered = {"workflowId": "w", "nodes": [{"id": "a"}, {"id": "b", "type": "gain"}],
                     "edges": [{"id": "x"}, {"id": "z"}]}
        self.assertEqual(CONTRACT.comparable_projection(original), CONTRACT.comparable_projection(reordered))
        reordered["nodes"][1] = {"id": "b", "type": "wrong"}
        self.assertNotEqual(CONTRACT.comparable_projection(original), CONTRACT.comparable_projection(reordered))
        self.assertEqual("b", original["nodes"][0]["id"])


if __name__ == "__main__":
    unittest.main()

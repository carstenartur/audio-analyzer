"""Check the desktop package input before a native installer can register file types."""

import importlib.util
import pathlib
import tempfile
import unittest
import zipfile


SPEC = importlib.util.spec_from_file_location(
    "package_desktop", pathlib.Path(__file__).with_name("package-desktop.py"))
PACKAGER = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(PACKAGER)


class DesktopPackageTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = pathlib.Path(self.temporary.name)
        self.target = self.root / "audio-app/target"
        (self.target / "lib").mkdir(parents=True)
        (self.target / "lib/storage.jar").write_bytes(b"runtime")
        self.input = self.root / "input"
        self.input.mkdir()
        self.jar = self.target / "audio-app-0.1.0-SNAPSHOT.jar"

    def write_jar(self, main):
        with zipfile.ZipFile(self.jar, "w") as archive:
            archive.writestr("META-INF/MANIFEST.MF", "Manifest-Version: 1.0\nMain-Class: " + main + "\n")

    def test_stages_only_desktop_jar_and_runtime_libraries(self):
        self.write_jar("org.hammer.ExperimentWorkbenchLauncher")
        (self.target / "audio-app-0.1.0-SNAPSHOT-workbench.jar").write_bytes(b"web")
        (self.target / "lib/debug.log").write_text("private diagnostics")
        PACKAGER.stage_application(self.root, "0.1.0-SNAPSHOT", self.input)
        self.assertEqual(["audio-analyzer.jar", "lib/storage.jar"],
                         sorted(str(path.relative_to(self.input)) for path in self.input.rglob("*") if path.is_file()))

    def test_refuses_launcher_that_does_not_open_documents_in_preview(self):
        self.write_jar("org.hammer.audio.app.WorkbenchApplication")
        with self.assertRaisesRegex(ValueError, "preview launcher"):
            PACKAGER.stage_application(self.root, "0.1.0-SNAPSHOT", self.input)
        self.assertFalse((self.input / "audio-analyzer.jar").exists())

    def test_refuses_missing_runtime_instead_of_building_a_broken_launcher(self):
        self.write_jar("org.hammer.ExperimentWorkbenchLauncher")
        (self.target / "lib/storage.jar").unlink()
        with self.assertRaisesRegex(ValueError, "runtime libraries"):
            PACKAGER.stage_application(self.root, "0.1.0-SNAPSHOT", self.input)

    def test_native_version_strips_snapshot_but_refuses_unrepresentable_versions(self):
        self.assertEqual("0.1.0", PACKAGER.native_version("0.1.0-SNAPSHOT"))
        with self.assertRaises(ValueError):
            PACKAGER.native_version("development")


if __name__ == "__main__":
    unittest.main()

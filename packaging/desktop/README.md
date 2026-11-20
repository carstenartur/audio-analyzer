# Desktop packages and experiment file association

Build and verify the application with Java 21 before packaging:

```bash
./mvnw -B clean verify
python3 scripts/test_package_desktop.py
python3 scripts/package-desktop.py --type app-image
```

The image under `target/desktop/AudioAnalyzer` includes its own Java runtime. The packager stages the
desktop launcher JAR and its runtime libraries; it excludes the web executable, reports and build
diagnostics. It checks the desktop manifest before invoking `jpackage`.

## Native installers

Create installers on their target operating system using a JDK that includes `jpackage` and the
platform's installer tools:

| Platform |                 Example command                 |                   File association                    |
|----------|-------------------------------------------------|-------------------------------------------------------|
| Linux    | `python3 scripts/package-desktop.py --type deb` | `.audioexp` MIME handler and application menu entry   |
| macOS    | `python3 scripts/package-desktop.py --type dmg` | `.audioexp` application document type                 |
| Windows  | `python3 scripts/package-desktop.py --type msi` | Per-user `.audioexp` handler, menu entry and shortcut |

RPM, PKG and EXE are also supported on their respective platforms. Add `--destination PATH` to select
an output directory. `--dry-run` validates the input and prints the intended package command without
building or installing anything. The application version is read from the parent POM; native package
versions use its numeric release component because installer formats cannot represent `-SNAPSHOT`.

Native installers consume `audioexp.properties`. An unpackaged app image does not register system file
associations. Installing a package is an explicit user action; the build script never installs it.
Windows and macOS installers must be produced and smoke-tested on those platforms before release.
Linux installers also use `AudioAnalyzer.desktop` through `jpackage --resource-dir`; its `%f`
placeholder forwards the selected local file to the preview launcher.

## Opening a shared experiment

A file-manager open action passes the selected path to `ExperimentWorkbenchLauncher`. Startup
arguments and supported desktop open-file events enter the same document inspection workflow. The
application shows the preview before the user chooses whether to apply the document; opening a file
does not begin capture, execute a workflow or authorize local device/output bindings.

For a release smoke test, install the native package, open `docs/examples/minimal.audioexp` through the
file manager and confirm that the preview is shown. Repeat with a path containing spaces, the
`unknown-optional-plugin.audioexp` fixture and a rejected fixture under `docs/examples/invalid/`.
The unknown section must remain visible and preserved; an invalid file must leave the current
experiment unchanged. Uninstall the test package using the platform package manager.

## Package verification in CI

The `Desktop packaging` workflow builds a real Linux `.deb` after the application verifies, extracts
it without installing it, and checks the `.audioexp` extension, vendor MIME type, launcher argument
placeholder and icon integration. The same association properties are passed to Windows and macOS
native package commands. Their installation and file-manager behavior require the corresponding
platform smoke tests described above.

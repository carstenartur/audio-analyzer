# Release-readiness checklist

Use this checklist before publishing a GitHub release, Zenodo archive or public announcement.

## Build and CI

- [ ] `./mvnw clean verify` passes on the release candidate.
- [ ] CI build/test workflow passes on the release branch or tag.
- [ ] CodeQL workflow passes or any finding has an explicit release decision.
- [ ] Static-analysis baseline does not increase.
- [ ] JaCoCo report is generated and thresholds are met.

## Documentation

- [ ] README describes the current application and version.
- [ ] Quickstart commands work from a clean checkout.
- [ ] Hard-coded snapshot versions are removed or updated.
- [ ] Feature docs match the current UI and code behavior.
- [ ] Architecture docs match current modules and package boundaries.
- [ ] Roadmap focuses on open work, not completed history.
- [ ] `docs/QA-FINDINGS.md` is current.

## Screenshots

- [ ] All documentation screenshots regenerated from the release candidate.
- [ ] Screenshot QA checklist completed.
- [ ] No unreadable labels, overlap, clipped text or misleading empty UI states.
- [ ] Workbench screenshots exist for major plugin flows when they are documented publicly.

## Manual application QA

- [ ] Manual application QA template copied to a dated evidence file.
- [ ] Main dashboard smoke test completed.
- [ ] Recording/replay/export flows completed.
- [ ] Plugin discovery and workbench flows completed.
- [ ] Error handling checks completed.
- [ ] HiDPI/manual resizing checks completed or explicitly deferred.

## Release metadata

- [ ] `CITATION.cff` version matches the release.
- [ ] `.zenodo.json` version matches the release.
- [ ] CodeMeta metadata, if present, matches the release.
- [ ] Release notes link to QA evidence or summarize known limitations.

## Decision

- [ ] No blocking QA issues remain open.
- [ ] Non-blocking limitations are documented.
- [ ] Release owner explicitly approves publication.

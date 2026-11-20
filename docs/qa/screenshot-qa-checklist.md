# Screenshot QA checklist

Use this checklist whenever documentation screenshots are regenerated.

## Regeneration command

Prefer running from a clean build of the release candidate. Avoid hard-coding snapshot versions in
documentation; commands should either use the current version property or a stable generated classpath.

```bash
./mvnw clean verify
java -cp "audio-app/target/classes:audio-app/target/lib/*" \
  org.hammer.tools.DocImageRenderer docs/images
```

On Windows, use `;` instead of `:` in the classpath.

## Complete image inventory

Use the [dated inventory and provenance record](documentation-visual-review-2026-11-20.md) as the
starting list: **11 actual browser captures (including three explicit runtime fixtures)** and
**9 Java2D illustrations**. Review both `docs/assets/screenshots/workbench/` and `docs/images/`,
including files not embedded in a feature page. Add/remove inventory rows whenever the file set changes.

For each image record its filename, source scenario/renderer, code revision, regeneration status,
visual result and any remaining restriction. Do not mark a deferred visual inspection as passed.
Historical review dates do not certify a changed image.

The Java2D command above regenerates illustrations only. Browser captures require the
[packaged-browser setup and verify/update procedure](../workbench-screenshot-pipeline.md#prepare-a-clean-checkout).
Keep actual captures, fixed fixtures and conceptual illustrations labelled separately in captions.

## Visual checks

For every image:

- [ ] no text overlaps other text;
- [ ] no label is clipped by image bounds or panel bounds;
- [ ] axis tick labels are readable and do not collide with each other;
- [ ] legends do not obscure important plotted data;
- [ ] panel titles are readable;
- [ ] contrast is sufficient for text and plotted traces;
- [ ] the screenshot shows a meaningful populated state, not an empty or misleading state;
- [ ] the screenshot matches the surrounding documentation text;
- [ ] image dimensions are intentional and consistent;
- [ ] image is regenerated from current code, not manually edited.

## Common failure patterns

- Fixed coordinate drawings can overflow when label text changes.
- Long commands, file paths or signal descriptions can exceed cell widths.
- Dual-plot images can have overlapping titles, tick labels or legends.
- Generated images can remain technically non-blank while still being unreadable.
- README commands can become stale when the Maven project version changes.

## Required follow-up when a screenshot fails

1. Fix the renderer or the actual UI layout, not the screenshot by manual editing.
2. Add or update a test if the issue can be checked programmatically.
3. Regenerate the screenshot from the fixed code.
4. Update the corresponding documentation page if the visible UI state changed.
5. Record remaining limitations in `docs/QA-FINDINGS.md` or a linked issue.


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

## Image inventory

- [ ] `docs/images/screenshot.png`
- [ ] `docs/images/features/waveform-trigger.png`
- [ ] `docs/images/features/spectrum-peak-hold.png`
- [ ] `docs/images/features/recording-format.png`
- [ ] `docs/images/features/ab-comparison.png`
- [ ] acoustic localization workbench image, once added
- [ ] imported recording workbench image, once added
- [ ] playback explorer image, once added
- [ ] generator calibration image, once added

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


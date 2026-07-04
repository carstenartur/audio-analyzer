# A/B comparison of recordings

Audio Analyzer can compare two `.aar` recordings and render a Markdown report with measurement,
spectrum and diagnosis differences. The report is intended for QA notes, regression evidence and bug
reports.

![A/B comparison spectra](../images/features/ab-comparison.png)

## When to use it

Use A/B comparison when:

- verifying that a fix changes the expected signal behavior;
- comparing a known-good recording with a current recording;
- documenting a hardware or configuration change;
- attaching readable evidence to a GitHub issue or PR.

The report is deterministic for the same input recordings and analyzer configuration.

## How to use it

1. Choose **File > Compare two recordings...**.
2. Select recording A.
3. Select recording B.
4. Let the application replay both files through the standard analyzer stack.
5. Save or preview the generated Markdown report.

No external service is contacted. The report is plain Markdown and can be committed, attached to an
issue or copied into release notes.

## Report content

Each report records:

- recording label, format, duration and frame count;
- RMS, peak level, dominant frequency, stereo correlation when available and clipping state;
- spectrum summary with FFT bin count, peak magnitude and spectral centroid;
- diagnosis findings with severity, type, message and confidence;
- absolute deltas for comparable metrics.

Example excerpt:

```markdown
| Metric | A | B | abs Δ |
|--------|---|---|-------|
| RMS | 0.3536 | 0.3536 | 0.0000 |
| Peak level | 0.6000 | 0.6000 | 0.0000 |
| Dominant freq (Hz) | 430.7 | 861.3 | 430.6 |
```

## Programmatic use

The same machinery is available without the UI:

- `RecordingComparator` replays two recordings through the analyzer stack and returns a
  `ComparisonReport`;
- `MarkdownComparisonReportRenderer` renders the report as Markdown.

This makes it possible to build CI or scripted checks around known recordings later.

## Limitations

- The current report summarizes final-block state. It is not a time-series diff.
- Very short recordings may not produce a full FFT snapshot.
- Large sample-rate differences can dominate spectral deltas.
- The report currently describes differences; it does not apply configurable pass/fail thresholds.


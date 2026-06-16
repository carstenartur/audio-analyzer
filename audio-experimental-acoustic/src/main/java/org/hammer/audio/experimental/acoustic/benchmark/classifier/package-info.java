/**
 * Classifier benchmark framework for wingbeat acoustic classification.
 *
 * <p>The framework allows multiple {@link
 * org.hammer.audio.experimental.acoustic.benchmark.classifier.ClassifierBenchmark} implementations
 * to be compared on the same labelled dataset. Adding a new classifier requires only implementing
 * the interface.
 *
 * <p>Output includes accuracy, per-label precision, recall, F1, and a full confusion matrix
 * captured in {@link
 * org.hammer.audio.experimental.acoustic.benchmark.classifier.ClassifierBenchmarkResult}.
 */
package org.hammer.audio.experimental.acoustic.benchmark.classifier;

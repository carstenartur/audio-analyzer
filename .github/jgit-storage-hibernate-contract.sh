#!/usr/bin/env bash
# Consumer-owned compatibility contract for jgit-storage-hibernate candidates.
set -euo pipefail

mode=${JGIT_STORAGE_HIBERNATE_CONTRACT_MODE:-candidate}
candidate_version=${JGIT_STORAGE_HIBERNATE_CANDIDATE_VERSION:-}
evidence_dir=target/jgit-storage-hibernate-contract
mkdir -p "$evidence_dir"

case "$mode" in
  candidate)
    if [[ -z "$candidate_version" ]]; then
      echo "Candidate mode requires JGIT_STORAGE_HIBERNATE_CANDIDATE_VERSION." >&2
      exit 64
    fi
    ;;
  baseline)
    ;;
  *)
    echo "Unsupported contract mode: $mode" >&2
    exit 64
    ;;
esac

java -version 2>&1 | tee "$evidence_dir/java-version.log"
java_specification="$({ java -XshowSettings:properties -version; } 2>&1 \
  | sed -n 's/^ *java.specification.version = //p' \
  | tail -n 1)"
if [[ "$java_specification" != "21" ]]; then
  echo "Audio Analyzer's storage contract requires Java 21, found $java_specification." >&2
  exit 1
fi

if [[ -x ./mvnw ]]; then
  maven=(./mvnw)
else
  maven=(mvn)
fi

# Build the actual application and every reactor module it needs. External-system
# integration tests are deliberately separate: this contract protects embedding,
# workflow-history, persistence, packaging and runtime linkage without requiring
# unrelated credentials or services.
set -o pipefail
"${maven[@]}" -B -ntp -nsu \
  -pl audio-app -am \
  -DskipITs=true \
  verify 2>&1 | tee "$evidence_dir/maven-verify.log"

"${maven[@]}" -B -ntp -nsu \
  -pl audio-app \
  -Dincludes=io.github.carstenartur \
  -DoutputType=text \
  -DoutputFile="$PWD/$evidence_dir/dependency-tree.txt" \
  dependency:tree

test -s "$evidence_dir/dependency-tree.txt"
if grep -Fq 'jgit-storage-hibernate-benchmarks' "$evidence_dir/dependency-tree.txt"; then
  echo "Benchmark artifacts must not enter the Audio Analyzer runtime." >&2
  exit 1
fi

if [[ "$mode" == "candidate" ]] \
    && ! grep -Fq ":$candidate_version" "$evidence_dir/dependency-tree.txt"; then
  echo "The resolved Audio Analyzer tree does not contain candidate $candidate_version." >&2
  cat "$evidence_dir/dependency-tree.txt" >&2
  exit 1
fi

mapfile -t application_jars < <(
  find audio-app/target -maxdepth 1 -type f -name '*.jar' \
    ! -name '*-sources.jar' ! -name '*-javadoc.jar' | sort
)
if [[ ${#application_jars[@]} -eq 0 ]]; then
  echo "The storage contract did not produce an Audio Analyzer application JAR." >&2
  exit 1
fi
printf '%s\n' "${application_jars[@]}" > "$evidence_dir/application-jars.txt"

for archive in "${application_jars[@]}"; do
  if jar tf "$archive" | grep -Eq '(^|/)jgit/storage/hibernate/benchmark/'; then
    echo "Benchmark classes leaked into $archive." >&2
    exit 1
  fi
done

cat > "$evidence_dir/result.json" <<EOF
{
  "consumer": "audio-analyzer",
  "mode": "$mode",
  "candidateVersion": "$candidate_version",
  "java": "$java_specification",
  "contract": "audio-app reactor verification, dependency resolution and packaged-runtime leakage check"
}
EOF

printf 'Audio Analyzer jgit-storage-hibernate contract passed in %s mode.\n' "$mode"

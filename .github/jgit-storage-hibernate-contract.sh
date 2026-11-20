#!/usr/bin/env bash
# Consumer-owned compatibility contract for jgit-storage-hibernate candidates.
set -euo pipefail

checkout_dir=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)
cd -- "$checkout_dir"

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

# Install the reactor so the subsequent standalone dependency-tree invocation
# resolves exactly the modules just verified. PostgreSQL container tests remain
# in the normal build; this focused contract needs only the embedded H2 runtime.
# Keep Surefire's unit-test naming patterns: an exclusion-only -Dtest would also
# run Failsafe's *IT classes before the executable application has been packaged.
python3 .github/scripts/test_jgit_runtime_contract.py
"${maven[@]}" -B -ntp -nsu \
  -pl audio-app -am \
  -DskipITs=true \
  '-Dtest=Test*,*Test,*Tests,*TestCase,!**/*PostgreSql*' \
  -Dsurefire.failIfNoSpecifiedTests=false \
  install 2>&1 | tee "$evidence_dir/maven-verify.log"

"${maven[@]}" -B -ntp -nsu \
  -pl audio-app \
  -Dscope=runtime -DoutputType=json \
  -DoutputFile="$PWD/$evidence_dir/dependency-tree.json" \
  dependency:tree

"${maven[@]}" -B -ntp -nsu \
  -pl audio-app \
  -DincludeScope=runtime -DoutputAbsoluteArtifactFilename=false \
  -DoutputFile="$PWD/$evidence_dir/runtime-dependencies.txt" \
  dependency:list

checks=(--checkout "$checkout_dir" --evidence "$evidence_dir")
if [[ "$mode" == "candidate" ]]; then
  checks+=(--candidate "$candidate_version")
fi
python3 .github/scripts/verify_jgit_runtime_contract.py "${checks[@]}"

printf 'Audio Analyzer jgit-storage-hibernate contract passed in %s mode.\n' "$mode"

#!/usr/bin/env bash
set -euo pipefail

: "${RELEASE_VERSION:?RELEASE_VERSION is required}"
: "${GITHUB_REPOSITORY:?GITHUB_REPOSITORY is required}"

NEXT_VERSION_INPUT=${NEXT_VERSION_INPUT:-}
SKIP_TESTS=${SKIP_TESTS:-false}
DRY_RUN=${DRY_RUN:-false}
SOURCE_BRANCH=${SOURCE_BRANCH:-master}
METADATA_HELPER=${METADATA_HELPER:?METADATA_HELPER is required}

TAG_NAME="v${RELEASE_VERSION}"
RELEASE_BRANCH="release/${TAG_NAME}"

if ! [[ "$RELEASE_VERSION" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]]; then
  echo "::error::release_version must use X.Y.Z without a leading v"
  exit 1
fi

if [[ "$SOURCE_BRANCH" != "master" && "$DRY_RUN" != "true" ]]; then
  echo "::error::Real releases must be dispatched from master, not $SOURCE_BRANCH"
  exit 1
fi

CURRENT_VERSION=$(./mvnw -q -DforceStdout help:evaluate -Dexpression=project.version)
if [[ "$CURRENT_VERSION" != *-SNAPSHOT ]]; then
  echo "::error::Current Maven version must be a SNAPSHOT, but was $CURRENT_VERSION"
  exit 1
fi
if [[ "${CURRENT_VERSION%-SNAPSHOT}" != "$RELEASE_VERSION" ]]; then
  echo "::error::Release $RELEASE_VERSION does not match current version $CURRENT_VERSION"
  exit 1
fi

if [[ -n "$NEXT_VERSION_INPUT" ]]; then
  NEXT_VERSION=$NEXT_VERSION_INPUT
else
  IFS='.' read -r MAJOR MINOR PATCH <<< "$RELEASE_VERSION"
  NEXT_VERSION="${MAJOR}.${MINOR}.$((PATCH + 1))-SNAPSHOT"
fi
if ! [[ "$NEXT_VERSION" =~ ^[0-9]+\.[0-9]+\.[0-9]+-SNAPSHOT$ ]]; then
  echo "::error::next_development_version must use X.Y.Z-SNAPSHOT"
  exit 1
fi

verify_metadata() {
  local expected=$1
  local release_mode=$2
  local maven_version
  maven_version=$(./mvnw -q -DforceStdout help:evaluate -Dexpression=project.version)
  test "$maven_version" = "$expected"
  grep -q "^version: \"${expected}\"$" CITATION.cff
  EXPECTED_VERSION="$expected" RELEASE_MODE="$release_mode" python3 - <<'PY'
import json
import os

with open('.zenodo.json', encoding='utf-8') as handle:
    data = json.load(handle)
expected = os.environ['EXPECTED_VERSION']
if data.get('version') != expected:
    raise SystemExit(f'.zenodo.json version {data.get("version")!r} != {expected!r}')
if os.environ['RELEASE_MODE'] == 'true':
    if not data.get('publication_date'):
        raise SystemExit('.zenodo.json publication_date is missing')
else:
    if 'publication_date' in data:
        raise SystemExit('.zenodo.json still contains publication_date')
PY
}

verify_metadata "$CURRENT_VERSION" false
./mvnw -B validate

git fetch origin --tags --force
TAG_EXISTS=false
BRANCH_EXISTS=false
if git rev-parse "${TAG_NAME}^{commit}" >/dev/null 2>&1; then
  TAG_EXISTS=true
fi
if git rev-parse "origin/${RELEASE_BRANCH}^{commit}" >/dev/null 2>&1; then
  BRANCH_EXISTS=true
fi

RELEASE_STATE=$(gh api "repos/${GITHUB_REPOSITORY}/releases?per_page=100" \
  --jq ".[] | select(.tag_name == \"${TAG_NAME}\") | if .draft then \"draft\" else \"published\" end" \
  | head -n 1 || true)

if [[ -n "$RELEASE_STATE" && "$TAG_EXISTS" != "true" ]]; then
  echo "::error::A GitHub release exists for ${TAG_NAME}, but its tag is missing"
  exit 1
fi
if [[ "$TAG_EXISTS" == "false" && "$BRANCH_EXISTS" == "true" ]]; then
  echo "::error::Release branch ${RELEASE_BRANCH} exists without tag ${TAG_NAME}"
  exit 1
fi
if [[ "$TAG_EXISTS" == "true" && "$BRANCH_EXISTS" == "true" ]]; then
  TAG_COMMIT=$(git rev-parse "${TAG_NAME}^{commit}")
  BRANCH_COMMIT=$(git rev-parse "origin/${RELEASE_BRANCH}^{commit}")
  if [[ "$TAG_COMMIT" != "$BRANCH_COMMIT" ]]; then
    echo "::error::${TAG_NAME} and ${RELEASE_BRANCH} point to different commits"
    exit 1
  fi
fi

if [[ -n "$RELEASE_STATE" ]]; then
  STATE=$RELEASE_STATE
elif [[ "$TAG_EXISTS" == "true" ]]; then
  STATE=tagged
else
  STATE=new
fi

echo "Release state: $STATE"
echo "Release version: $RELEASE_VERSION"
echo "Next development version: $NEXT_VERSION"

if [[ "$STATE" == "new" ]]; then
  ./mvnw -B versions:set -DnewVersion="$RELEASE_VERSION" -DgenerateBackupPoms=false
  python3 "$METADATA_HELPER" "$RELEASE_VERSION" --release
  verify_metadata "$RELEASE_VERSION" true
  git add pom.xml */pom.xml CITATION.cff .zenodo.json
  git commit -m "Release version $RELEASE_VERSION"
else
  git checkout --detach "$TAG_NAME"
  verify_metadata "$RELEASE_VERSION" true
fi

if [[ "$SKIP_TESTS" == "true" ]]; then
  ./mvnw -B clean package -DskipTests
else
  ./mvnw -B clean verify
fi

rm -rf target/release-artifacts
mkdir -p target/release-artifacts
find . -path './target' -prune -o -path '*/target/*.jar' -type f \
  ! -name '*-sources.jar' ! -name '*-javadoc.jar' ! -name 'original-*' \
  -exec cp {} target/release-artifacts/ \;
ls -la target/release-artifacts

if [[ "$DRY_RUN" != "true" && "$STATE" == "new" ]]; then
  RELEASE_COMMIT=$(git rev-parse HEAD)
  git push origin "HEAD:refs/heads/${RELEASE_BRANCH}"
  TAG_SHA=$(gh api "repos/${GITHUB_REPOSITORY}/git/tags" \
    --method POST \
    -f tag="$TAG_NAME" \
    -f message="Release version $RELEASE_VERSION" \
    -f object="$RELEASE_COMMIT" \
    -f type="commit" \
    --jq '.sha')
  gh api "repos/${GITHUB_REPOSITORY}/git/refs" \
    --method POST \
    -f ref="refs/tags/${TAG_NAME}" \
    -f sha="$TAG_SHA"
  STATE=tagged
fi

if [[ "$DRY_RUN" != "true" && "$STATE" == "tagged" ]]; then
  gh release create "$TAG_NAME" \
    --verify-tag \
    --draft \
    --title "Audio Analyzer $RELEASE_VERSION" \
    --generate-notes
  STATE=draft
fi

if [[ "$DRY_RUN" != "true" && "$STATE" == "draft" ]]; then
  mapfile -d '' ARTIFACTS < <(find target/release-artifacts -type f -print0)
  if [[ ${#ARTIFACTS[@]} -gt 0 ]]; then
    gh release upload "$TAG_NAME" "${ARTIFACTS[@]}" --clobber
  fi
  gh release edit "$TAG_NAME" --draft=false --latest
  STATE=published
fi

if [[ "$DRY_RUN" != "true" ]]; then
  IS_DRAFT=$(gh release view "$TAG_NAME" --json isDraft --jq '.isDraft')
  test "$IS_DRAFT" = false
fi

./mvnw -B versions:set -DnewVersion="$NEXT_VERSION" -DgenerateBackupPoms=false
python3 "$METADATA_HELPER" "$NEXT_VERSION"
verify_metadata "$NEXT_VERSION" false

NEXT_BRANCH="release/prepare-next-${NEXT_VERSION}"
git switch -C "$NEXT_BRANCH"
git add pom.xml */pom.xml CITATION.cff .zenodo.json
git commit -m "Prepare next development version $NEXT_VERSION"

if [[ "$DRY_RUN" != "true" ]]; then
  REMOTE_SHA=$(git ls-remote --heads origin "refs/heads/${NEXT_BRANCH}" | awk '{print $1}')
  if [[ -n "$REMOTE_SHA" ]]; then
    git push \
      --force-with-lease="refs/heads/${NEXT_BRANCH}:${REMOTE_SHA}" \
      origin "HEAD:refs/heads/${NEXT_BRANCH}"
  else
    git push origin "HEAD:refs/heads/${NEXT_BRANCH}"
  fi

  cat > /tmp/next-development-pr.md <<EOF
Automated follow-up after release ${RELEASE_VERSION}.

## Changes
- Bump all Maven modules to ${NEXT_VERSION}
- Update CITATION.cff to ${NEXT_VERSION}
- Update .zenodo.json to ${NEXT_VERSION}
- Remove release-only date metadata from the development snapshot
EOF

  EXISTING_PR=$(gh pr list --base master --head "$NEXT_BRANCH" \
    --state open --json number --jq '.[0].number // empty')
  if [[ -n "$EXISTING_PR" ]]; then
    gh pr edit "$EXISTING_PR" \
      --title "Prepare next development version ${NEXT_VERSION}" \
      --body-file /tmp/next-development-pr.md
  else
    gh pr create \
      --title "Prepare next development version ${NEXT_VERSION}" \
      --body-file /tmp/next-development-pr.md \
      --base master \
      --head "$NEXT_BRANCH"
  fi
else
  echo 'Dry run completed; no remote refs, release or PR were changed.'
fi

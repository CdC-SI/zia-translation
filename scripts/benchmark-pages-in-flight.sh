#!/usr/bin/env bash
# Times one Markdown translation job end to end (submit -> COMPLETED), to compare zia.translation.pages-in-flight values.
#
# Usage: start the app with the value to test, e.g.
#   ZIA_TRANSLATION_PAGES_IN_FLIGHT=1 mvn spring-boot:run
# then, in another terminal:
#   scripts/benchmark-pages-in-flight.sh document-10-pages.pdf [runs] [base-url]
# Repeat for 1, 2, 4, 8 and report the times in the PR. Uses the vLLM server the app is configured with.
set -euo pipefail

FILE=${1:?usage: $0 <document.pdf> [runs] [base-url]}
RUNS=${2:-3}
BASE=${3:-http://localhost:8080/zia-trad/api/translation}

for run in $(seq 1 "$RUNS"); do
  start=$(date +%s.%N)
  job=$(curl -sf -F "file=@${FILE}" -F "targetLanguage=de" "${BASE}/md" | jq -r .jobId)
  while true; do
    status=$(curl -sf "${BASE}/jobs/${job}/status" | jq -r .status)
    [[ "$status" == COMPLETED || "$status" == FAILED ]] && break
    sleep 0.5
  done
  end=$(date +%s.%N)
  printf 'run %d: %s in %.1f s (job %s)\n' "$run" "$status" "$(echo "$end - $start" | bc)" "$job"
done

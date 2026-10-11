#!/usr/bin/env bash
# Post-deployment integration test against a deployed stack.
#
#   tests/integration/smoke.sh <stack-name> [region]
#
# Signs up a throwaway user (auto-confirmed by the PreSignUp trigger), exercises the
# tasks API end to end, including a task that must be expired by the scheduler
# pipeline, then deletes its tasks and the user. Needs only cloudformation:DescribeStacks;
# everything else goes through the public Cognito API and the tasks API.
set -euo pipefail

STACK=${1:?usage: smoke.sh <stack-name> [region]}
REGION=${2:-eu-west-1}
EXPIRY_TIMEOUT=${EXPIRY_TIMEOUT:-180}

output() {
  aws cloudformation describe-stacks --stack-name "$STACK" --region "$REGION" \
    --query "Stacks[0].Outputs[?OutputKey=='$1'].OutputValue" --output text | tr -d '\r'
}
API=$(output ApiUrl)
CLIENT_ID=$(output UserPoolClientId)
APP_URL=$(output AmplifyAppUrl)

EMAIL="ci-${GITHUB_RUN_ID:-local}-${RANDOM}@example.com"
PASSWORD="Ci-$(openssl rand -hex 8)A1"
ID_TOKEN=""
ACCESS_TOKEN=""
FAILURES=0

pass() { echo "  PASS  $1"; }
fail() { echo "  FAIL  $1"; FAILURES=$((FAILURES + 1)); }
expect() { # <description> <expected> <actual>
  if [[ "$2" == "$3" ]]; then pass "$1"; else fail "$1 (expected '$2', got '$3')"; fi
}

# api <method> <path> [json-body] -> sets STATUS and BODY
api() {
  local args=(-s -o /tmp/smoke-body -w '%{http_code}' -X "$1" "$API$2" -H "Content-Type: application/json")
  [[ -n "$ID_TOKEN" ]] && args+=(-H "Authorization: $ID_TOKEN")
  [[ $# -ge 3 ]] && args+=(-d "$3")
  STATUS=$(curl "${args[@]}")
  BODY=$(cat /tmp/smoke-body)
}

cleanup() {
  if [[ -n "$ID_TOKEN" ]]; then
    api GET /tasks
    for id in $(echo "$BODY" | jq -r '.[]?.taskId'); do api DELETE "/tasks/$id"; done
  fi
  if [[ -n "$ACCESS_TOKEN" ]]; then
    aws cognito-idp delete-user --access-token "$ACCESS_TOKEN" --region "$REGION" && echo "Cleaned up test user"
  fi
}
trap cleanup EXIT

echo "Integration test: stack=$STACK api=$API"

echo "Auth"
api GET /tasks
expect "unauthenticated request is rejected" 401 "$STATUS"

CONFIRMED=$(aws cognito-idp sign-up --region "$REGION" --client-id "$CLIENT_ID" --username "$EMAIL" \
  --password "$PASSWORD" --user-attributes Name=email,Value="$EMAIL" --query UserConfirmed --output text | tr -d '\r')
expect "sign-up is auto-confirmed" True "$CONFIRMED"

read -r ID_TOKEN ACCESS_TOKEN < <(aws cognito-idp initiate-auth --region "$REGION" --client-id "$CLIENT_ID" \
  --auth-flow USER_PASSWORD_AUTH --auth-parameters USERNAME="$EMAIL",PASSWORD="$PASSWORD" \
  --query 'AuthenticationResult.[IdToken,AccessToken]' --output text | tr -d '\r')
[[ -n "$ID_TOKEN" ]] && pass "sign-in returns tokens" || fail "sign-in returns tokens"

echo "CORS"
ORIGIN=$(curl -s -o /dev/null -D - -X OPTIONS "$API/tasks" -H "Access-Control-Request-Method: POST" \
  | tr -d '\r' | awk -F': ' 'tolower($1)=="access-control-allow-origin"{print $2}')
expect "preflight allows the Amplify app origin" "$APP_URL" "$ORIGIN"

echo "CRUD"
api POST /tasks '{"description":"CI default deadline"}'
expect "create task" 201 "$STATUS"
TASK=$(echo "$BODY" | jq -r .taskId)
expect "new task is Pending" Pending "$(echo "$BODY" | jq -r .status)"
LEAD=$(( $(echo "$BODY" | jq -r .deadlineEpoch) - $(date +%s) ))
(( LEAD > 240 && LEAD <= 310 )) && pass "deadline defaults to ~5 minutes ($LEAD s)" || fail "deadline defaults to ~5 minutes (got $LEAD s)"

api GET "/tasks/$TASK"
expect "get task" 200 "$STATUS"

api GET "/tasks?status=Pending"
expect "task listed under Pending" "$TASK" "$(echo "$BODY" | jq -r --arg id "$TASK" '.[] | select(.taskId==$id) | .taskId')"

api PUT "/tasks/$TASK" '{"description":"CI edited"}'
expect "update description" "CI edited" "$(echo "$BODY" | jq -r .description)"

api PUT "/tasks/$TASK" '{"status":"Expired"}'
expect "clients cannot set Expired" 400 "$STATUS"

api PUT "/tasks/$TASK" '{"status":"Completed"}'
expect "complete task" Completed "$(echo "$BODY" | jq -r .status)"

api GET "/tasks?status=Completed"
expect "task listed under Completed" "$TASK" "$(echo "$BODY" | jq -r --arg id "$TASK" '.[] | select(.taskId==$id) | .taskId')"

api DELETE "/tasks/$TASK"
expect "delete task" 204 "$STATUS"
api GET "/tasks/$TASK"
expect "deleted task is gone" 404 "$STATUS"

echo "Expiry pipeline (stream -> SQS FIFO -> scheduler -> expiry)"
DEADLINE=$(date -u -d '+70 seconds' +%Y-%m-%dT%H:%M:%SZ)
api POST /tasks "{\"description\":\"CI expiry\",\"deadline\":\"$DEADLINE\"}"
EXPIRING=$(echo "$BODY" | jq -r .taskId)
api POST /tasks "{\"description\":\"CI cancelled\",\"deadline\":\"$DEADLINE\"}"
CANCELLED=$(echo "$BODY" | jq -r .taskId)
api PUT "/tasks/$CANCELLED" '{"status":"Completed"}'

END=$(( $(date +%s) + 70 + EXPIRY_TIMEOUT ))
STATE=Pending
while (( $(date +%s) < END )); do
  api GET "/tasks/$EXPIRING"
  STATE=$(echo "$BODY" | jq -r .status)
  [[ "$STATE" == Expired ]] && break
  sleep 10
done
expect "task expires after its deadline" Expired "$STATE"
api GET "/tasks/$CANCELLED"
expect "completed task is not expired" Completed "$(echo "$BODY" | jq -r .status)"

echo
if (( FAILURES > 0 )); then
  echo "$FAILURES check(s) failed"
  exit 1
fi
echo "All checks passed"

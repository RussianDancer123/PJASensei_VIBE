#!/bin/sh
# Gives Spring its access to the Gitea API: a technical admin account nobody
# signs in to, and an access token in a volume shared only with Spring.
# Safe to repeat. Run as the git user, somewhere that sees Gitea's data volume.
set -eu

api_url="${1:?usage: provision-api-token.sh <gitea-api-url> <token-file>}"
token_file="${2:?usage: provision-api-token.sh <gitea-api-url> <token-file>}"
account=pjasensei-spring

if [ -s "$token_file" ] &&
    curl -fs -o /dev/null -H "Authorization: token $(cat "$token_file")" "$api_url/api/v1/user"; then
    exit 0
fi

if ! gitea admin user list --admin | awk '{print $2}' | grep -qx "$account"; then
    # The random password goes nowhere: nobody ever signs in with this account.
    gitea admin user create --admin --username "$account" \
        --email "$account@pjasensei.invalid" \
        --random-password --must-change-password=false >/dev/null
fi

umask 077
gitea admin user generate-access-token --username "$account" \
    --token-name "spring-$(date +%s)" --scopes all --raw >"$token_file"

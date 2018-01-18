#!/usr/bin/env bash

STAGING_ACCESS_PEM=${1:?"Pem file for accessing staging"}

HEIMDALL_IP=$(curl "http://masters.staging.witan.mastodonc.net/marathon/v2/apps/kixi.heimdall/tasks" 2> /dev/null | jq '.tasks[].host' | sort -R | head -n 1 | xargs echo)

ssh -oStrictHostKeyChecking=no core@$HEIMDALL_IP -i $STAGING_ACCESS_PEM -L 3002:$HEIMDALL_IP:10010 -N &
HEIMDALL_TUNNEL_PID=$!

function cleanup {

    echo "Tearing down tunnels"
    kill $HEIMDALL_TUNNEL_PID
}
trap cleanup EXIT
echo "Heimdall is now available @ localhost:3002. Ctrl+C to exit..."
while true; do sleep 2; done

#!/bin/bash
# Build the server on the Mac and ship it to the Ray VM, then restart.
# Usage: server/deploy/deploy.sh            (from glucose-app/)
set -euo pipefail
cd "$(dirname "$0")/../.."
HOST=${RAY_HOST:-ubuntu@ray.geoffreychan.com}
SSH="ssh -i $HOME/.ssh/oci_ray_key"
export JAVA_HOME=${JAVA_HOME:-/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home}
./gradlew -q :server:test :server:installDist
rsync -az --delete -e "$SSH" server/build/install/ray/ server/deploy "$HOST:/tmp/ray-new/"
$SSH "$HOST" 'set -e
  sudo rsync -a --delete /tmp/ray-new/ /opt/ray/ && rm -rf /tmp/ray-new
  sudo cp /opt/ray/deploy/ray.service /opt/ray/deploy/ray-backup.service /opt/ray/deploy/ray-backup.timer /etc/systemd/system/
  sudo systemctl daemon-reload
  sudo systemctl enable --now ray-backup.timer >/dev/null
  sudo systemctl enable ray >/dev/null && sudo systemctl restart ray
  sleep 8; systemctl is-active ray; curl -s localhost:8080/status; echo'

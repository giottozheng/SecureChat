#!/bin/bash
# Auto-sync coturn external-ip with current public IP (router redials weekly).
set -u
CONF=/etc/turnserver.conf
LOG=/var/log/turn_ip_check.log

NEWIP=$(curl -s --max-time 10 https://api.ipify.org || true)
if [ -z "$NEWIP" ]; then
  NEWIP=$(getent hosts 1.hackdz.dpdns.org | awk '{print $1}' | grep -E '^[0-9]{1,3}(\.[0-9]{1,3}){3}$' | head -1 || true)
fi

if ! echo "$NEWIP" | grep -qE '^[0-9]{1,3}(\.[0-9]{1,3}){3}$'; then
  echo "$(date) SKIP: could not determine valid public IPv4 (got '$NEWIP')" >> "$LOG"
  exit 0
fi

CUR=$(grep -E '^external-ip=' "$CONF" | head -1 | cut -d= -f2)
if [ -z "$CUR" ]; then
  echo "$(date) ERROR: no 'external-ip=' line found in $CONF; not modifying" >> "$LOG"
  exit 1
fi

if [ "$NEWIP" != "$CUR" ]; then
  sed -i "s/^external-ip=.*/external-ip=$NEWIP/" "$CONF"
  systemctl restart coturn
  if systemctl is-active --quiet coturn; then
    echo "$(date) OK: external-ip $CUR -> $NEWIP, coturn restarted" >> "$LOG"
  else
    echo "$(date) FAIL: external-ip $CUR -> $NEWIP, but coturn NOT active after restart!" >> "$LOG"
  fi
else
  echo "$(date) OK: external-ip=$CUR unchanged" >> "$LOG"
fi

#!/bin/bash
while true; do
  node dist/server.js >> server.log 2>&1
  sleep 3
done

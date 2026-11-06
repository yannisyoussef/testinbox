#!/bin/bash
# WS-B3 SLE prototype: build one ref's harness (./gradlew :benchmark:installDist) in the JDK 25 container. Usage: build.sh <worktree>
exec docker run --rm --name ws-b3-build -u "$(id -u):$(id -g)" -e GRADLE_USER_HOME=/gradle -e HOME=/tmp \
  -v "$1":/src -v /opt/infinity/workspace/ti-delivery-002/stage-b/storage/bench3/gradle-home:/gradle -w /src/backend eclipse-temurin@sha256:201fbb8886b2d273218aa3a192f0afbf7b5ff65ee8cc6ef47f5dce2171f013ea \
  ./gradlew --no-daemon -q :benchmark:installDist

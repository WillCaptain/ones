#!/bin/bash
# Wrapper launched by launchd (~/Library/LaunchAgents/com.twelve.worldone.plist).
# Using a bash wrapper sidesteps launchd's EX_CONFIG when directly exec'ing the
# Homebrew JDK binary (TCC/sandbox rejects some paths for direct launchd spawn).
export JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home
export PATH="$JAVA_HOME/bin:/usr/local/bin:/opt/homebrew/bin:/usr/bin:/bin"
cd /Users/imac/Documents/code/github/ones/world-one
exec java -jar deploy/worldone.jar --server.port=8090 >> deploy/server.log 2>&1

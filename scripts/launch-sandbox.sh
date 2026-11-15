#!/bin/zsh
# Fast sandbox launch for intellij-plugin-v4 (~10s vs ~60s for runIde). macOS (Apple silicon)
# only: it starts the IDE that Gradle extracted (idea-<version>-aarch64) through its bundled JBR,
# because that IDE's native launcher is killed by macOS (its signature breaks on extraction).
# Usage: launch-sandbox.sh [--restart] [--no-build] [--no-project | PROJECT_DIR]
# --restart stops a running sandbox (SIGTERM) and waits for it to exit first. Without it, a
# running sandbox is an error: rebuilding replaces the plugin jars under it, and the IDE's
# auto-reload half-unloads the plugin (stale classes, "Cannot find suitable constructor").
# Rebuilds the plugin into the sandbox, regenerates the JVM argfile from the
# extracted IDE's product-info.json, and starts the IDE in the background.
# Opens PROJECT_DIR, or by default the sandbox's most recently activated project
# (from config/options/recentProjects.xml); --no-project shows the welcome screen.
set -e
BUILD=1; PROJECT=; NO_PROJECT=; RESTART=
for a in "$@"; do
  case $a in
    --no-build) BUILD= ;;
    --restart) RESTART=1 ;;
    --no-project) NO_PROJECT=1 ;;
    *) PROJECT=$a ;;
  esac
done
ROOT=${0:A:h:h}                                                  # repo root (this is scripts/)
cd "$ROOT"
VER=$(sed -n 's/^ideaVersion=//p' gradle.properties)          # e.g. IU-2026.2.1
NUM=${VER#*-}                                                   # 2026.2.1
P=$(ls -d ~/.gradle/caches/*/transforms/*/transformed/idea-$NUM-aarch64 2>/dev/null | head -1)
[[ -d $P ]] || { echo "extracted IDE idea-$NUM not found; run ./gradlew prepareSandbox once"; exit 1; }
SB=$ROOT/.intellijPlatform/sandbox/intellij-plugin-v4/$VER
OUT=${TMPDIR:-/tmp}/antlr-sandbox; mkdir -p "$OUT"

# Any instance of this sandbox: started by this script (args in the argfile) or restarted by
# the IDE itself (args on the command line)
PAT="antlr-sandbox/sandbox\.argfile|idea\.system\.path=$SB/system"
running() { pgrep -f -- "$PAT" ; }
if running >/dev/null; then
  [[ -n $RESTART ]] || { echo "sandbox already running (pid $(running | xargs)); use --restart"; exit 1; }
  pkill -TERM -f -- "$PAT"
  for i in {1..60}; do running >/dev/null || break; sleep 1; done
  running >/dev/null && { echo "sandbox still running after 60s; close it by hand"; exit 1; }
fi

[[ -z $BUILD ]] || ./gradlew -q prepareSandbox

if [[ -z $NO_PROJECT && -z $PROJECT ]]; then
  PROJECT=$(python3 -I - "$SB/config/options/recentProjects.xml" <<'EOF'
import sys, os, xml.etree.ElementTree as ET
try:
    root = ET.parse(sys.argv[1]).getroot()
except (OSError, ET.ParseError):
    sys.exit(0)
best, best_ts = None, -1
for entry in root.iter("entry"):
    path = entry.get("key", "").replace("$USER_HOME$", os.path.expanduser("~"))
    ts = max((int(o.get("value", "0")) for o in entry.iter("option")
              if o.get("name") == "activationTimestamp"), default=0)
    if ts > best_ts and os.path.isdir(path):
        best, best_ts = path, ts
if best:
    print(best)
EOF
)
fi

python3 -I - "$P" "$SB" "$ROOT" "$OUT/sandbox.argfile" <<'EOF'
import json, sys, os
P, SB, ROOT, OUT = sys.argv[1:]
d = json.load(open(f"{P}/Resources/product-info.json"))
l = next(x for x in d["launch"] if x["os"] == "macOS")
app = os.path.dirname(P)  # $APP_PACKAGE/Contents -> P
sub = lambda s: s.replace("$APP_PACKAGE/Contents", P).replace("$IDE_HOME", P)
args = [sub(a) for a in l["additionalJvmArguments"]]
vmf = os.path.normpath(os.path.join(P, "bin", l["vmOptionsFilePath"]))
args += [x.strip() for x in open(vmf) if x.strip() and not x.startswith("#") and not x.startswith("-Xmx")]
args += ["-Xmx8G",
  f"-Didea.config.path={SB}/config", f"-Didea.system.path={SB}/system",
  f"-Didea.plugins.path={SB}/plugins", f"-Didea.log.path={SB}/log",
  f"-Dplugin.path={SB}/plugins/intellij-plugin-v4",
  "-Didea.plugin.in.sandbox.mode=true", "-Didea.required.plugins.id=org.antlr.intellij.plugin",
  "-Didea.is.internal=true", "-Didea.auto.reload.plugins=true",
  "-Didea.classpath.index.enabled=false", "-Didea.smooth.progress=false",
  f"-javaagent:{ROOT}/.intellijPlatform/coroutines-javaagent.jar"]
q = lambda s: '"' + s.replace("\\", "\\\\").replace('"', '\\"') + '"'
cp = ":".join(f"{P}/lib/{j}" for j in l["bootClassPathJarNames"])
open(OUT, "w").write("\n".join([q(a) for a in args] + ["-cp", q(cp), l["mainClass"]]) + "\n")
EOF

[[ -n $DRY ]] && { echo "argfile: $OUT/sandbox.argfile"; exit 0; }
nohup "$P/jbr/Contents/Home/bin/java" @"$OUT/sandbox.argfile" ${PROJECT:+"$PROJECT"} > "$OUT/sandbox-stdout.log" 2>&1 &
echo "sandbox pid $! ; project ${PROJECT:-<none>} ; stdout $OUT/sandbox-stdout.log ; log $SB/log/idea.log"

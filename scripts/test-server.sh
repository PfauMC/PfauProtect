#!/usr/bin/env sh
# The Canvas server for manual runs (.planning/TESTING-*.md), in a Linux container. plugin.yml only
# ships the RocksDB natives production runs on, so a Windows host cannot load the plugin itself; the
# container is also the platform production is.
#
#   scripts/test-server.sh start   build the plugin, copy it into run/plugins and start the server
#   scripts/test-server.sh stop    stop it the way a server is stopped, so onDisable runs
#   scripts/test-server.sh logs    follow the console
#   scripts/test-server.sh cmd "pp lookup player:SPY_me"   run a console command; the answer is in logs
#
# The console reads from a pipe, because RCON returns before an asynchronous command has answered and
# every /pp lookup is asynchronous. RCON listens on 127.0.0.1:25575 only. online-mode is off so the bots of the two-player cases can
# join with the same world and the same player ids as a real client.
set -eu
cd "$(dirname "$0")/.."

NAME=pfauprotect-test
# Must match canvasBuild in build.gradle.kts.
CANVAS_BUILD=956
# Git Bash rewrites anything that looks like a path, the container side included.
export MSYS_NO_PATHCONV=1
HOST_RUN="$(pwd -W 2>/dev/null || pwd)/run"

case "${1:-}" in
start)
    ./gradlew build -q
    mkdir -p run/plugins
    [ -f run/canvas.jar ] || curl -sSfL -o run/canvas.jar \
        "https://jenkins.canvasmc.io/job/Canvas/$CANVAS_BUILD/artifact/canvas-server/build/libs/canvas-build.$CANVAS_BUILD.jar"
    cp build/libs/pfauprotect-*.jar run/plugins/
    # Live checks read what the plugin says in English; a server of players gets Russian by default.
    # PPT_LANG=ru starts it in Russian, to check the Russian texts.
    mkdir -p run/plugins/PfauProtect
    if [ -f run/plugins/PfauProtect/config.yml ]; then
        sed -i "s/^language: .*/language: ${PPT_LANG:-en}/" run/plugins/PfauProtect/config.yml
    else
        echo "language: ${PPT_LANG:-en}" >run/plugins/PfauProtect/config.yml
    fi
    [ -f run/eula.txt ] || echo "eula=true" >run/eula.txt
    [ -f run/server.properties ] || cat >run/server.properties <<'EOF'
online-mode=false
enable-rcon=true
rcon.port=25575
rcon.password=pfauprotect
spawn-protection=0
level-type=minecraft\:flat
gamemode=survival
difficulty=normal
EOF
    docker run -d --rm --name "$NAME" \
        -p 25565:25565 -p 127.0.0.1:25575:25575 \
        -e TZ=Europe/Moscow \
        -v "$HOST_RUN:/server" -w /server \
        eclipse-temurin:25-jre sh -c '
            mkfifo /tmp/console
            # Held open for writing so the server never reads an end of input between two commands.
            sleep infinity >/tmp/console &
            exec java -Xmx4G -jar canvas.jar --nogui </tmp/console'
    ;;
cmd)
    docker exec "$NAME" sh -c 'echo "$1" >/tmp/console' _ "$2"
    ;;
stop)
    # SIGTERM is the server's own shutdown path; the grace period covers draining the ledger.
    docker stop --time 120 "$NAME"
    ;;
logs)
    docker logs -f "$NAME"
    ;;
*)
    echo "usage: $0 start|stop|logs|cmd <command>" >&2
    exit 2
    ;;
esac

#!/bin/sh
# Dev helper for the modded fork.
#
#   ./dev.sh build   compile everything into the three jars
#   ./dev.sh run     build, then launch the game client
#   ./dev.sh server  build, then launch a local global+room server pair
#   ./dev.sh clean   remove build output
#
# The system JRE on this machine is headless and has no compiler, so we
# prefer a local JDK if one is unpacked under ~/tools.

set -e

for candidate in "$HOME"/tools/jdk-*; do
	if [ -x "$candidate/bin/javac" ]; then
		JAVA_HOME="$candidate"
		export JAVA_HOME
		PATH="$JAVA_HOME/bin:$PATH"
		export PATH
		break
	fi
done

if ! command -v javac >/dev/null 2>&1; then
	echo "no javac found: unpack a JDK into ~/tools or install one system-wide" >&2
	exit 1
fi

build()
{
	sh compile.sh
}

case "${1:-build}" in
	build)
		build
		;;
	run)
		build
		java -jar graphwar.jar "$2"
		;;
	server)
		build
		ip="${2:-127.0.0.1}"
		java -jar globalServer.jar "$ip" &
		java -jar roomServer.jar "$ip" &
		wait
		;;
	clean)
		rm -rf bin Graphwar GraphServer GlobalServer RoomServer
		rm -f graphwar.jar roomServer.jar globalServer.jar
		;;
	*)
		echo "usage: $0 {build|run|server|clean}" >&2
		exit 1
		;;
esac

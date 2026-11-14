#!/bin/sh
# Avvio del servizio trailer. Da usare nell'Utilita' di pianificazione di DSM
# (attivita' attivata all'avvio, con il tuo utente). Se e' gia' in esecuzione non fa nulla.
DIR="$(cd "$(dirname "$0")/.." && pwd)"
PIDFILE="$DIR/logs/agent.pid"
mkdir -p "$DIR/logs"
if [ -f "$PIDFILE" ] && kill -0 "$(cat "$PIDFILE")" 2>/dev/null; then
    echo "gia' in esecuzione (pid $(cat "$PIDFILE"))"
    exit 0
fi
cd "$DIR"
nohup nice -n 10 /usr/bin/python3 "$DIR/agent/agent.py" run >> "$DIR/logs/stdout.log" 2>&1 &
echo $! > "$PIDFILE"
echo "avviato (pid $!)"

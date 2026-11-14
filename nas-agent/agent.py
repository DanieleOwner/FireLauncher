#!/usr/bin/env python3
"""
FireLauncher - servizio trailer per Synology (Python 3.8, solo libreria standard).

Cosa fa:
  * ogni notte chiede a TMDB i film piu' popolari usciti negli ultimi N mesi
    (divisi in finestre di 3 mesi, cosi' anche i film meno recenti sono coperti);
  * per ognuno scarica con yt-dlp il trailer (video 1080p H.264 e audio separati,
    senza ffmpeg: li unisce la Fire Stick in riproduzione), la locandina orizzontale
    e una miniatura;
  * cancella i trailer che non servono piu';
  * serve tutto in rete locale su http://<nas>:8787 (con supporto Range, per la cache del player).

Struttura della cartella (BASE = cartella che contiene agent/):
  agent/agent.py      questo file
  config/tmdb.key     chiave TMDB (v3 o token v4), solo la chiave
  config/agent.json   impostazioni opzionali (vedi DEFAULTS)
  tools/yt-dlp        eseguibile yt-dlp per aarch64
  www/manifest.json   elenco letto dal launcher
  www/media/<id>/     video.mp4, audio.m4a, backdrop.jpg, thumb.jpg
  logs/agent.log

Uso:
  python3 agent.py run       server + aggiornamento automatico ogni notte
  python3 agent.py refresh   solo un aggiornamento, poi esce
"""

import datetime as dt
import http.server
import json
import logging
import logging.handlers
import os
import re
import shutil
import socketserver
import subprocess
import sys
import threading
import time
import urllib.parse
import urllib.request

BASE = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
CONFIG_DIR = os.path.join(BASE, "config")
WWW = os.path.join(BASE, "www")
MEDIA = os.path.join(WWW, "media")
TOOLS = os.path.join(BASE, "tools")
LOGS = os.path.join(BASE, "logs")
YTDLP = os.path.join(TOOLS, "yt-dlp")

DEFAULTS = {
    "port": 8787,
    "months_back": 24,          # quanto indietro guardare (il launcher filtra poi il suo intervallo)
    "bucket_months": 3,         # ampiezza di ogni finestra
    "per_bucket": 125,          # film per finestra: 8 finestre x 125 = 1000 trailer in rotazione
    "download_pause_s": 4,      # pausa tra un download e l'altro
    "language": "it-IT",
    "region": "IT",
    "max_height": 1080,
    "refresh_hour": 4,          # ora dell'aggiornamento notturno
    "ytdlp_update_days": 3,     # ogni quanti giorni aggiornare yt-dlp
}

TMDB = "https://api.themoviedb.org/3"
IMG = "https://image.tmdb.org/t/p"

log = logging.getLogger("agent")
refresh_lock = threading.Lock()


# --------------------------------------------------------------------------- config

def load_config():
    cfg = dict(DEFAULTS)
    path = os.path.join(CONFIG_DIR, "agent.json")
    if os.path.exists(path):
        with open(path, encoding="utf-8") as f:
            cfg.update(json.load(f))
    return cfg


def load_key():
    with open(os.path.join(CONFIG_DIR, "tmdb.key"), encoding="utf-8") as f:
        return f.read().strip()


def setup_logging():
    os.makedirs(LOGS, exist_ok=True)
    handler = logging.handlers.RotatingFileHandler(
        os.path.join(LOGS, "agent.log"), maxBytes=2_000_000, backupCount=3, encoding="utf-8")
    fmt = logging.Formatter("%(asctime)s %(levelname)s %(message)s")
    handler.setFormatter(fmt)
    console = logging.StreamHandler(sys.stdout)
    console.setFormatter(fmt)
    log.addHandler(handler)
    log.addHandler(console)
    log.setLevel(logging.INFO)


# --------------------------------------------------------------------------- TMDB

class Tmdb:
    def __init__(self, key, cfg):
        self.key = key
        self.cfg = cfg
        # Le chiavi v3 sono 32 caratteri esadecimali; i token v4 sono JWT lunghi.
        self.bearer = len(key) > 40

    def get(self, path, **params):
        if not self.bearer:
            params["api_key"] = self.key
        url = TMDB + path + "?" + urllib.parse.urlencode(params)
        req = urllib.request.Request(url, headers={"Accept": "application/json"})
        if self.bearer:
            req.add_header("Authorization", "Bearer " + self.key)
        for attempt in range(3):
            try:
                with urllib.request.urlopen(req, timeout=20) as r:
                    return json.loads(r.read().decode("utf-8"))
            except urllib.error.HTTPError as e:
                if e.code == 429:
                    time.sleep(2 + attempt * 3)
                    continue
                # Non loggo l'URL: conterrebbe la chiave.
                raise RuntimeError("TMDB %s su %s" % (e.code, path)) from None
            except urllib.error.URLError:
                time.sleep(2 + attempt * 3)
        raise RuntimeError("TMDB non raggiungibile (%s)" % path)

    def candidates(self):
        """Film popolari, finestra per finestra, dal piu' recente al piu' vecchio."""
        today = dt.date.today()
        months_back = self.cfg["months_back"]
        step = self.cfg["bucket_months"]
        seen, out = set(), []
        for start in range(0, months_back, step):
            hi = add_months(today, -start)
            lo = add_months(today, -(start + step)) + dt.timedelta(days=1)
            got = 0
            # Un 30% di riserva: non tutti i film hanno un trailer.
            want = int(self.cfg["per_bucket"] * 1.3) + 4
            for page in range(1, want // 20 + 2):
                data = self.get(
                    "/discover/movie",
                    language=self.cfg["language"],
                    region=self.cfg["region"],
                    sort_by="popularity.desc",
                    include_adult="false",
                    include_video="false",
                    **{"primary_release_date.gte": lo.isoformat(),
                       "primary_release_date.lte": hi.isoformat(),
                       "with_release_type": "2|3",
                       "page": page},
                )
                for m in data.get("results", []):
                    if m["id"] in seen or not m.get("backdrop_path"):
                        continue
                    seen.add(m["id"])
                    out.append(m)
                    got += 1
                    if got >= want:
                        break
                if got >= want or page >= data.get("total_pages", 0):
                    break
        return out

    def details(self, movie_id):
        return self.get(
            "/movie/%d" % movie_id,
            language=self.cfg["language"],
            append_to_response="videos",
            include_video_language="it,en,null",
        )


def add_months(d, months):
    y, m = divmod(d.month - 1 + months, 12)
    year, month = d.year + y, m + 1
    last = [31, 29 if year % 4 == 0 and (year % 100 != 0 or year % 400 == 0) else 28,
            31, 30, 31, 30, 31, 31, 30, 31, 30, 31][month - 1]
    return dt.date(year, month, min(d.day, last))


def pick_trailer(videos):
    ranked = rank_trailers(videos)
    return ranked[0] if ranked else None


def rank_trailers(videos):
    """Trailer ufficiale in italiano, poi in inglese, poi qualsiasi trailer YouTube: dal migliore al peggiore."""
    vids = [v for v in videos if v.get("site") == "YouTube" and v.get("type") in ("Trailer", "Teaser")]

    def score(v):
        lang = v.get("iso_639_1")
        return (
            0 if lang == "it" else 1 if lang == "en" else 2,
            0 if v.get("type") == "Trailer" else 1,
            0 if v.get("official") else 1,
            -(v.get("size") or 0),
        )
    vids.sort(key=score)
    return vids


# --------------------------------------------------------------------------- download

def run(cmd, timeout):
    # yt-dlp e' un eseguibile PyInstaller: si scompatta in TMPDIR, e su Synology /tmp e' noexec.
    tmp = os.path.join(TOOLS, "tmp")
    os.makedirs(tmp, exist_ok=True)
    env = dict(os.environ, TMPDIR=tmp)
    return subprocess.run(cmd, stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                          timeout=timeout, universal_newlines=True, env=env)


def ytdlp_update(cfg):
    stamp = os.path.join(TOOLS, ".last-update")
    days = cfg["ytdlp_update_days"]
    if os.path.exists(stamp) and time.time() - os.path.getmtime(stamp) < days * 86400:
        return
    r = run([YTDLP, "-U"], 300)
    log.info("yt-dlp -U: %s", r.stdout.strip().splitlines()[-1:] if r.stdout else "")
    with open(stamp, "w") as f:
        f.write(str(time.time()))


def download_trailer(youtube_key, folder, cfg):
    url = "https://www.youtube.com/watch?v=" + youtube_key
    h = cfg["max_height"]
    jobs = [
        ("video.mp4", "bv*[vcodec^=avc1][height<=%d][ext=mp4]/bv*[height<=%d][ext=mp4]" % (h, h)),
        ("audio.m4a", "ba[ext=m4a]/ba"),
    ]
    for name, fmt in jobs:
        target = os.path.join(folder, name)
        if os.path.exists(target):
            continue
        tmp = target + ".part-dl"
        if os.path.exists(tmp):
            os.remove(tmp)  # resto di un download interrotto: yt-dlp lo prenderebbe per completo
        r = run([YTDLP, "-q", "--no-warnings", "--no-playlist", "--no-part",
                 "-f", fmt, "-o", tmp, url], 900)
        if r.returncode != 0 or not os.path.exists(tmp):
            for leftover in (tmp,):
                if os.path.exists(leftover):
                    os.remove(leftover)
            raise RuntimeError("yt-dlp: " + (r.stdout or "").strip()[-300:])
        os.replace(tmp, target)


def fetch_file(url, target):
    if os.path.exists(target):
        return
    tmp = target + ".tmp"
    with urllib.request.urlopen(url, timeout=30) as r, open(tmp, "wb") as f:
        shutil.copyfileobj(r, f)
    os.replace(tmp, target)


# --------------------------------------------------------------------------- refresh

def refresh():
    if not refresh_lock.acquire(blocking=False):
        log.info("Aggiornamento gia' in corso")
        return
    try:
        _refresh()
    except Exception:
        log.exception("Aggiornamento fallito")
    finally:
        refresh_lock.release()


def _refresh():
    cfg = load_config()
    tmdb = Tmdb(load_key(), cfg)
    os.makedirs(MEDIA, exist_ok=True)
    try:
        ytdlp_update(cfg)
    except Exception as e:
        log.warning("Aggiornamento yt-dlp non riuscito: %s", e)

    started = time.time()
    candidates = tmdb.candidates()
    log.info("Candidati da TMDB: %d", len(candidates))

    # Alterno le finestre di date (una per ognuna, a giro): cosi' ogni periodo si riempie subito
    # invece di aspettare che finiscano i film piu' recenti.
    by_bucket = {}
    for m in candidates:
        by_bucket.setdefault(bucket_of(m.get("release_date") or "", cfg), []).append(m)
    queues = [by_bucket[b] for b in sorted(by_bucket)]
    candidates = []
    while any(queues):
        for q in queues:
            if q:
                candidates.append(q.pop(0))

    write_manifest()  # subito, con quello che c'e' gia' su disco
    prefs = load_prefs()
    nope = set(prefs["nope"])
    keep, failed, added = set(), 0, 0
    per_bucket_count = {}
    for m in candidates:
        bucket = bucket_of(m.get("release_date") or "", cfg)
        if per_bucket_count.get(bucket, 0) >= cfg["per_bucket"] or m["id"] in nope:
            continue
        folder = os.path.join(MEDIA, str(m["id"]))
        if is_complete(folder):
            # Gia' scaricato: non richiedo nulla, lo conto e basta.
            keep.add(str(m["id"]))
            per_bucket_count[bucket] = per_bucket_count.get(bucket, 0) + 1
            continue
        try:
            d = tmdb.details(m["id"])
            if 0 < (d.get("runtime") or 0) < 60:
                continue  # corti, special TV e simili
            options = rank_trailers(d.get("videos", {}).get("results", []))[:3]
            if not options:
                continue
            os.makedirs(folder, exist_ok=True)
            marker = os.path.join(folder, "source.txt")
            trailer, last_error = None, None
            # Alcuni video non sono disponibili (bloccati, privati): provo il prossimo trailer in classifica.
            for opt in options:
                if os.path.exists(marker) and open(marker).read().strip() != opt["key"]:
                    for f in ("video.mp4", "audio.m4a"):
                        p = os.path.join(folder, f)
                        if os.path.exists(p):
                            os.remove(p)
                try:
                    download_trailer(opt["key"], folder, cfg)
                    trailer = opt
                    break
                except Exception as e:
                    last_error = e
                    with open(marker, "w") as f:
                        f.write(opt["key"])
            if trailer is None:
                shutil.rmtree(folder, ignore_errors=True)
                raise last_error or RuntimeError("nessun trailer scaricabile")
            with open(marker, "w") as f:
                f.write(trailer["key"])
            fetch_file(IMG + "/w1280" + d["backdrop_path"], os.path.join(folder, "backdrop.jpg"))
            fetch_file(IMG + "/w300" + d["backdrop_path"], os.path.join(folder, "thumb.jpg"))
        except Exception as e:
            failed += 1
            log.warning("Film %s (%s) saltato: %s", m["id"], m.get("title"), e)
            continue

        base = "media/%d/" % m["id"]
        keep.add(str(m["id"]))
        per_bucket_count[bucket] = per_bucket_count.get(bucket, 0) + 1
        write_json(os.path.join(folder, "meta.json"), {
            "id": m["id"],
            "title": d.get("title") or m.get("title"),
            "original_title": d.get("original_title"),
            "overview": d.get("overview") or "",
            "release_date": d.get("release_date") or m.get("release_date"),
            "rating": round(d.get("vote_average") or 0, 1),
            "runtime": d.get("runtime") or 0,
            "genres": [g["name"] for g in d.get("genres", [])],
            "popularity": d.get("popularity") or m.get("popularity") or 0,
            "lang": trailer.get("iso_639_1") or "",
            "video": base + "video.mp4",
            "audio": base + "audio.m4a",
            "thumb": base + "thumb.jpg",
            "backdrop": base + "backdrop.jpg",
        })
        added += 1
        # Ricostruisco il manifest ogni 5 trailer: il launcher li vede appena pronti.
        if added % 5 == 1:
            write_manifest()
        time.sleep(cfg["download_pause_s"])  # con calma, per non farsi limitare da YouTube

    removed = cleanup(keep)
    write_manifest()
    log.info("Fatto in %ds: %d trailer (%d nuovi), %d saltati, %d cartelle rimosse",
             time.time() - started, len(keep), added, failed, removed)


def bucket_of(release_date, cfg):
    try:
        d = dt.date.fromisoformat(release_date)
    except ValueError:
        return -1
    today = dt.date.today()
    months = (today.year - d.year) * 12 + today.month - d.month
    return months // cfg["bucket_months"]


REQUIRED = ("video.mp4", "audio.m4a", "backdrop.jpg", "thumb.jpg", "meta.json")
manifest_lock = threading.Lock()


def is_complete(folder):
    return all(os.path.exists(os.path.join(folder, f)) for f in REQUIRED)


def write_json(path, data):
    tmp = path + ".tmp"
    with open(tmp, "w", encoding="utf-8") as f:
        json.dump(data, f, ensure_ascii=False)
    os.replace(tmp, path)


def write_manifest():
    """Il manifest e' costruito dai meta.json su disco: preferiti inclusi, scartati esclusi."""
    with manifest_lock:
        prefs = load_prefs()
        liked, nope = set(prefs["like"]), set(prefs["nope"])
        items = []
        for name in os.listdir(MEDIA):
            folder = os.path.join(MEDIA, name)
            if not name.isdigit() or int(name) in nope or not is_complete(folder):
                continue
            try:
                with open(os.path.join(folder, "meta.json"), encoding="utf-8") as f:
                    meta = json.load(f)
            except (OSError, ValueError):
                continue
            meta["liked"] = meta["id"] in liked
            items.append(meta)
        items.sort(key=lambda i: -(i.get("popularity") or 0))
        write_json(os.path.join(WWW, "manifest.json"), {
            "generated": dt.datetime.now().replace(microsecond=0).isoformat(),
            "count": len(items),
            "items": items,
        })


def cleanup(keep):
    """Cancella i trailer non piu' in elenco. I preferiti non si toccano mai."""
    liked = {str(i) for i in load_prefs()["like"]}
    removed = 0
    for name in os.listdir(MEDIA):
        if name not in keep and name not in liked:
            shutil.rmtree(os.path.join(MEDIA, name), ignore_errors=True)
            removed += 1
    return removed


# --------------------------------------------------------------------------- preferiti

PREFS = os.path.join(CONFIG_DIR, "prefs.json")
prefs_lock = threading.Lock()


def load_prefs():
    try:
        with open(PREFS, encoding="utf-8") as f:
            p = json.load(f)
    except (OSError, ValueError):
        p = {}
    return {"like": list(p.get("like", [])), "nope": list(p.get("nope", []))}


def set_pref(movie_id, state):
    """state: like (cuore), nope (X) oppure none (annulla)."""
    with prefs_lock:
        p = load_prefs()
        like = [i for i in p["like"] if i != movie_id]
        nope = [i for i in p["nope"] if i != movie_id]
        if state == "like":
            like.append(movie_id)
        elif state == "nope":
            nope.append(movie_id)
        write_json(PREFS, {"like": like, "nope": nope})
    if state == "nope":
        # Lo scartato sparisce subito anche dal disco.
        liked_now = movie_id in like
        if not liked_now:
            shutil.rmtree(os.path.join(MEDIA, str(movie_id)), ignore_errors=True)
    write_manifest()
    return {"like": like, "nope": nope}


# --------------------------------------------------------------------------- HTTP

class Handler(http.server.SimpleHTTPRequestHandler):
    """File statici da www/ con supporto alle richieste Range (necessarie al player)."""

    def __init__(self, *a, **kw):
        super().__init__(*a, directory=WWW, **kw)

    def log_message(self, fmt, *args):
        pass

    def end_headers(self):
        self.send_header("Accept-Ranges", "bytes")
        if self.path.endswith("manifest.json"):
            self.send_header("Cache-Control", "no-cache")
        super().end_headers()

    def send_json(self, code, data):
        body = json.dumps(data).encode("utf-8")
        self.send_response(code)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def do_POST(self):
        if self.path.rstrip("/") == "/api/prefs":
            try:
                length = int(self.headers.get("Content-Length") or 0)
                req = json.loads(self.rfile.read(length).decode("utf-8"))
                state = req.get("state")
                if state not in ("like", "nope", "none"):
                    raise ValueError("state")
                return self.send_json(200, set_pref(int(req["id"]), state))
            except (ValueError, KeyError, TypeError):
                return self.send_json(400, {"error": "richiesta non valida"})
        if self.path.rstrip("/") == "/api/refresh":
            threading.Thread(target=refresh, daemon=True).start()
            return self.send_json(202, {"ok": True})
        self.send_json(404, {"error": "non trovato"})

    def do_GET(self):
        if self.path.rstrip("/") == "/api/prefs":
            return self.send_json(200, load_prefs())
        if self.path.rstrip("/") == "/api/status":
            return self.send_json(200, {"refreshing": refresh_lock.locked()})
        rng = self.headers.get("Range")
        path = self.translate_path(self.path)
        if not rng or not os.path.isfile(path):
            return super().do_GET()
        m = re.match(r"bytes=(\d*)-(\d*)$", rng.strip())
        size = os.path.getsize(path)
        if not m or (m.group(1) == "" and m.group(2) == ""):
            return super().do_GET()
        if m.group(1) == "":
            start, end = max(0, size - int(m.group(2))), size - 1
        else:
            start = int(m.group(1))
            end = int(m.group(2)) if m.group(2) else size - 1
        end = min(end, size - 1)
        if start >= size or start > end:
            self.send_response(416)
            self.send_header("Content-Range", "bytes */%d" % size)
            self.end_headers()
            return
        length = end - start + 1
        self.send_response(206)
        self.send_header("Content-Type", self.guess_type(path))
        self.send_header("Content-Range", "bytes %d-%d/%d" % (start, end, size))
        self.send_header("Content-Length", str(length))
        self.end_headers()
        with open(path, "rb") as f:
            f.seek(start)
            remaining = length
            try:
                while remaining > 0:
                    chunk = f.read(min(256 * 1024, remaining))
                    if not chunk:
                        break
                    self.wfile.write(chunk)
                    remaining -= len(chunk)
            except (BrokenPipeError, ConnectionResetError):
                pass


Handler.extensions_map.update({".mp4": "video/mp4", ".m4a": "audio/mp4", ".json": "application/json"})


class Server(socketserver.ThreadingMixIn, http.server.HTTPServer):
    daemon_threads = True
    allow_reuse_address = True


# --------------------------------------------------------------------------- main

def scheduler(cfg):
    manifest = os.path.join(WWW, "manifest.json")
    if not os.path.exists(manifest) or time.time() - os.path.getmtime(manifest) > 86400:
        refresh()
    while True:
        now = dt.datetime.now()
        nxt = now.replace(hour=cfg["refresh_hour"], minute=0, second=0, microsecond=0)
        if nxt <= now:
            nxt += dt.timedelta(days=1)
        time.sleep((nxt - now).total_seconds())
        refresh()


def main():
    setup_logging()
    os.makedirs(MEDIA, exist_ok=True)
    cmd = sys.argv[1] if len(sys.argv) > 1 else "run"
    if cmd == "refresh":
        refresh()
        return
    cfg = load_config()
    threading.Thread(target=scheduler, args=(cfg,), daemon=True).start()
    log.info("In ascolto su :%d, cartella %s", cfg["port"], WWW)
    Server(("0.0.0.0", cfg["port"]), Handler).serve_forever()


if __name__ == "__main__":
    main()

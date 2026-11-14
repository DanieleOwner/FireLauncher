# FireLauncher

Una home alternativa per **Fire TV Stick** (Fire OS 6+), con uno slider di **trailer dei film usciti di recente** che partono all'istante, una **watchlist** in stile "Tinder" (cuore / X) e il meteo.

I trailer non arrivano da YouTube in tempo reale: un piccolo servizio sul **NAS di casa** (pensato per Synology, ma va su qualsiasi Linux con Python 3.8+) li scarica ogni notte e li serve in rete locale. Così la Fire Stick li fa partire in meno di un secondo.

```
 TMDB ──► servizio NAS (Python) ──► /manifest.json + video/audio/immagini ──► FireLauncher (Fire TV)
 YouTube ─┘   (yt-dlp, ogni notte)          rete locale, porta 8787              (ExoPlayer, Compose for TV)
```

## Funzioni

**Home**
- Le app preferite in basso, con le icone originali, in ordine personalizzabile.
- Accesso a tutte le app, all'Appstore e alle impostazioni.
- Saluto, ora e meteo (Open-Meteo, senza chiave) in alto.
- Sfondo che cambia con il film selezionato.

**Trailer**
- Slider di film usciti in un intervallo configurabile (default: da 18 a 6 mesi fa), ordinati per popolarità.
- Titolo, trama, generi, durata e voto in italiano da TMDB.
- Riproduzione nella finestrina della home o a tutto schermo.
- Partenza istantanea: il trailer selezionato viene caricato appena ci arrivi col focus, e l'inizio dei trailer vicini è già in cache.
- "Cerca su Stremio" apre Stremio sulla ricerca del film.

**Watchlist**
| Tasto del telecomando | Azione |
|---|---|
| pulsante **Watchlist** sotto la trama (o ⏩) | cuore: aggiunge o toglie dalla watchlist |
| pulsante **Scarta** (o ⏪) | X: non mostrarlo più (sparisce anche dal NAS) |
| schede **Novità / Watchlist** sopra i trailer (o ≡) | passa dai trailer recenti alla watchlist |
| OK | riproduce; un secondo OK passa a tutto schermo |
| ‹ › a tutto schermo | trailer precedente o successivo |

I trailer col cuore **non vengono mai cancellati** dalla rotazione del NAS. Cuori e X sono salvati sul NAS, quindi sopravvivono alla reinstallazione dell'app.

**Impostazioni**, dalla home oppure dall'icona "Impostazioni launcher":
- Trailer attivi o disattivi, intervallo di mesi, quanti mostrare (5–100 oppure "Tutti"), finestrina o tutto schermo, audio in finestrina.
- App in home: aggiungi, togli, riordina.
- Città per il meteo (default: Terni).

## Struttura

```
app/                          app Android (Kotlin, Jetpack Compose for TV, Media3 ExoPlayer, Coil)
  src/main/java/dev/firelauncher/
    MainActivity.kt           navigazione tra le schermate e collegamenti
    HomeWatcherService.kt     intercetta l'apertura della home Amazon e apre FireLauncher
    HomeOverlay.kt            schermata di copertura durante l'attesa imposta da Fire OS
    data/                     app installate, impostazioni, trailer e preferenze, meteo
    player/TrailerPlayer.kt   ExoPlayer unico, preparazione anticipata e cache su disco
    ui/                       home, tutte le app, impostazioni, player a tutto schermo
nas-agent/
  agent.py                    servizio trailer (solo libreria standard di Python 3.8)
  start.sh                    avvio in background (da usare all'accensione del NAS)
  agent.example.json          impostazioni opzionali del servizio
```

## Installazione

### 1. Servizio trailer sul NAS

Requisiti:
- Python 3.8 o superiore (sul Synology DSM 7 c'è già, in `/usr/bin/python3`).
- Una **chiave API di TMDB** gratuita: su themoviedb.org vai in Impostazioni → API.

Su Synology crea una cartella condivisa, per esempio `firelauncher` su Volume 1, e copia lì dentro i file in questa struttura:

```
/volume1/firelauncher/
  agent/agent.py
  agent/start.sh
  config/tmdb.key        # contiene SOLO la chiave TMDB (v3 o token v4); non va mai committata
  config/agent.json      # opzionale, vedi nas-agent/agent.example.json
  tools/yt-dlp           # eseguibile yt-dlp per la tua architettura
```

`yt-dlp` per Synology ARM a 64 bit (es. DS220j):

```bash
curl -L -o /volume1/firelauncher/tools/yt-dlp https://github.com/yt-dlp/yt-dlp/releases/latest/download/yt-dlp_linux_aarch64
chmod +x /volume1/firelauncher/tools/yt-dlp
```

Proteggi la chiave e avvia il servizio:

```bash
chmod 700 /volume1/firelauncher/config && chmod 600 /volume1/firelauncher/config/tmdb.key
sh /volume1/firelauncher/agent/start.sh
```

Avvio automatico in DSM: **Pannello di controllo → Utilità di pianificazione → Crea → Attività attivata → Script definito dall'utente**, con evento "Avvio", il tuo utente e come script `sh /volume1/firelauncher/agent/start.sh`.

Il servizio:
- ascolta sulla porta **8787** e aggiorna l'elenco ogni notte alle 4;
- tiene fino a **1000 trailer**: 125 per ognuna delle 8 finestre di 3 mesi, sugli ultimi 24 mesi;
- scarica video 1080p H.264 e audio separati, così non serve ffmpeg (li unisce il player);
- a regime occupa circa 30 MB a trailer;
- scrive il registro in `logs/agent.log`.

Endpoint HTTP:

| Metodo | Percorso | Descrizione |
|---|---|---|
| GET | `/manifest.json` | elenco dei trailer pronti |
| GET | `/media/<id>/…` | video, audio, immagini (con supporto Range) |
| GET | `/api/prefs` | `{"like": [...], "nope": [...]}` |
| POST | `/api/prefs` | `{"id": 123, "state": "like" \| "nope" \| "none"}` |
| POST | `/api/refresh` | avvia subito un aggiornamento |
| GET | `/api/status` | `{"refreshing": true/false}` |

> Nota: su Synology `/tmp` non permette di eseguire programmi, quindi il servizio fa scompattare `yt-dlp` in `tools/tmp`.

### 2. App sulla Fire TV

1. Sulla Fire TV vai in Impostazioni → La mia Fire TV → Informazioni e premi 7 volte sul nome per sbloccare le **Opzioni sviluppatore**. Attiva **Debug ADB** e **App da origini sconosciute**.
2. Sul PC serve l'Android SDK, per esempio installando Android Studio. Prima di compilare imposta l'indirizzo del NAS in `LauncherSettings.DEFAULT_SERVER` ([Settings.kt](app/src/main/java/dev/firelauncher/data/Settings.kt)).
3. Compila e installa:

```bash
./gradlew assembleRelease
adb connect <ip-fire-tv>:5555
adb install -r app/build/outputs/apk/release/app-release.apk
```

4. Concedi i permessi per il tasto Home. Fire OS li permette solo via ADB:

```bash
adb shell appops set dev.firelauncher GET_USAGE_STATS allow
adb shell pm grant dev.firelauncher android.permission.READ_LOGS
adb shell appops set dev.firelauncher SYSTEM_ALERT_WINDOW allow
```

La build `release` è firmata con la chiave di debug locale: va bene per uso personale (sideload).

## Il tasto Home su Fire OS

Amazon non permette di cambiare launcher:
- non lascia scegliere un'altra app per la home;
- blocca i servizi di accessibilità di terze parti;
- impedisce di disattivare la sua home ("protected package").

FireLauncher fa così:
1. Legge il registro di sistema (`READ_LOGS`) e vede la richiesta di aprire la home Amazon nell'istante in cui parte.
2. Copre subito lo schermo con una schermata nello stile del launcher (`SYSTEM_ALERT_WINDOW`).
3. Riapre FireLauncher.

Dopo la pressione di Home, però, Android impedisce alle app di aprirsi per **circa 5 secondi**. Quell'attesa resta, ma non si vede la home Amazon. All'accensione FireLauncher parte direttamente.

## Limiti e note

- Alcuni trailer YouTube non sono disponibili in Italia o sono privati: il servizio prova il trailer successivo di TMDB, e se nessuno funziona salta il film.
- Scaricare video da YouTube con `yt-dlp` va contro i termini di servizio di YouTube: il progetto è pensato per uso personale, a tuo rischio.
- I dati dei film vengono da [TMDB](https://www.themoviedb.org/). Questo prodotto usa l'API di TMDB ma non è approvato né certificato da TMDB.
- Il meteo viene da [Open-Meteo](https://open-meteo.com/).

## Licenza

MIT. Vedi [LICENSE](LICENSE).

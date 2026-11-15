# USB Trackpad

Trasforma un telefono Android nel trackpad del tuo Mac o PC Windows, collegato con il cavo USB.
Colleghi il telefono e il trackpad si apre da solo: niente Wi-Fi, niente Bluetooth, niente app da avviare.

- **Plug and play:** il computer riconosce il telefono, installa o aggiorna l'app e apre il trackpad, anche sopra la schermata di blocco.
- **Mac e Windows:** Mac Apple Silicon e PC Windows x64 o ARM64.
- **Gesti come su un trackpad vero:** clic, clic destro, doppio clic, trascinamento e scorrimento a due dita in verticale e in orizzontale, con inerzia.
- **Tema sincronizzato con il computer:** chiaro o scuro in tempo reale, con una dissolvenza come quella di macOS e senza mai interrompere il trackpad. Sul Mac, con l'aspetto "Automatico", cambia all'alba e al tramonto insieme al Mac.
- **Protezione OLED/AMOLED:** sugli schermi che soffrono di burn-in il trackpad è sempre nero assoluto e non lascia testo fisso sullo schermo.
- **Limitatore di carica (telefoni con root):** ferma la ricarica al 60% e la riprende al 50%, per allungare la vita della batteria di un telefono sempre collegato.
- **Privato e sicuro:** tutto passa dal cavo USB. Il programma sul computer accetta connessioni solo in locale, quindi nessuno può muovere il tuo mouse dalla rete.

## Requisiti

| | |
|---|---|
| **Mac** | Apple Silicon (M1 o successivo), macOS 11 Big Sur o successivo |
| **Windows** | Windows 10 o 11 a 64 bit, x64 oppure ARM64 (su ARM64 serve Windows 11) |
| **Telefono** | Android 5.0 o successivo, con il Debug USB attivo |
| **Cavo** | un cavo USB che trasferisca dati, non solo ricarica |

Non servono Xcode, Android Studio o permessi da amministratore. Se manca `adb`, l'installazione scarica gli Android platform-tools ufficiali da Google (circa 15 MB).

## Installazione

Scarica l'ultima versione dalla pagina **Releases** ed estrai la cartella del tuo sistema.

### Mac

1. Nel Terminale, dentro la cartella `mac`:
   ```bash
   ./install.sh
   ```
2. In **Impostazioni di Sistema → Privacy e sicurezza → Accessibilità** abilita `usbtrackpad`.
   Senza questo permesso macOS non permette di muovere il cursore. Se non compare in elenco, aggiungilo con **+** da
   `~/Library/Application Support/USBTrackpad/usbtrackpad`.

### Windows

1. Nella cartella `windows`, apri **`install.cmd`** con un doppio clic.
   Lo script sceglie da solo la versione giusta (x64 o ARM64) e attiva l'avvio automatico all'accesso.
2. Se Windows SmartScreen avvisa che il programma non è riconosciuto, scegli "Ulteriori informazioni" e poi "Esegui comunque".
3. Su Windows 10 e 11 il driver USB del telefono di solito si installa da solo. Se il telefono non viene riconosciuto,
   installa il driver del produttore (per Pixel e Nexus: Google USB Driver).

### Telefono

1. Attiva le **Opzioni sviluppatore**: Impostazioni → Info sul telefono → tocca 7 volte "Numero build".
   Su alcuni telefoni "Numero build" è dentro Info sul telefono → Informazioni software.
2. Attiva il **Debug USB**: Impostazioni → Sistema → Opzioni sviluppatore → Debug USB.
   Il Debug USB deve restare attivo: è il canale che il computer usa per parlare con il telefono.

## Primo collegamento

1. Sul computer USB Trackpad deve essere installato (vedi sopra). Parte da solo a ogni accesso.
2. Sblocca il telefono e collegalo al computer con il cavo.
3. Sul telefono compare **"Consentire il debug USB?"**: spunta **"Consenti sempre da questo computer"** e tocca **OK**.
   Se non compare, scollega e ricollega il cavo. Su alcuni telefoni bisogna anche scegliere la modalità USB
   "Trasferimento file" dalla notifica USB.
4. Entro pochi secondi il computer installa l'app **Trackpad** sul telefono e la apre.
5. In alto sul telefono compare il **nome del computer** con il puntino **verde**: è tutto pronto.
   - Puntino **rosso** con "Mac non autorizzato": manca il permesso Accessibilità (vedi Installazione → Mac).
   - Puntino **rosso**: il computer non è collegato; controlla il registro (vedi Risoluzione dei problemi).

Dalla volta successiva basta collegare il cavo: il trackpad si apre da solo, anche con il telefono bloccato.

## Uso

Lo schermo del telefono è tutto trackpad. In alto c'è solo lo stato, seguito dalla carica della batteria del telefono:

| Puntino | Testo | Significato |
|---|---|---|
| 🟢 verde | nome del computer | collegato, tutto ok |
| 🔴 rosso | Mac non autorizzato | collegato, ma il Mac non riesce a muovere il cursore (manca il permesso Accessibilità) |
| 🔴 rosso | Computer non collegato | in attesa del computer |

Il Mac controlla di continuo che i movimenti abbiano davvero effetto: se il permesso viene tolto, l'app passa
subito a "Mac non autorizzato" e torna verde da sola appena il permesso viene ridato.

Il nome è quello del computer (sul Mac: Impostazioni → Generali → Condivisione; su Windows: il nome del PC).
I nomi troppo lunghi vengono tagliati con una sfumatura a destra.

### Gesti

| Gesto | Azione |
|---|---|
| 1 dito che scorre | muove il cursore, con accelerazione |
| tocco | clic |
| doppio tocco | doppio clic |
| tocco con 2 dita | clic destro |
| tocco con 3 dita | clic centrale |
| 2 dita che scorrono | scorrimento verticale e orizzontale, con inerzia |
| doppio tocco tenuto e trascinato | trascina finestre e file, seleziona testo |

Lo scorrimento a due dita all'inizio del gesto si blocca sull'asse principale, come sui trackpad Apple.
Sul Mac segue l'impostazione "Scorrimento naturale" e invia le fasi di scorrimento native (inizio, fine e inerzia),
così le app reagiscono come con un trackpad vero. Su Windows usa la rotella verticale e orizzontale ad alta risoluzione.

### Gesture del Mac

Sul Mac il trackpad del telefono segue **le stesse impostazioni del trackpad del Mac**
(Impostazioni di Sistema → Trackpad, e Accessibilità → Controllo puntatore per il trascinamento con tre dita).
Se cambi un'impostazione, vale subito anche per il telefono.

| Impostazione del Mac | Sul telefono |
|---|---|
| Mission Control (3 o 4 dita in su) | scorrimento in su con 3 o 4 dita |
| Exposé dell'app (3 o 4 dita in giù) | scorrimento in giù con 3 o 4 dita |
| Scorri tra app a tutto schermo | 3 o 4 dita a destra o sinistra: scrivania precedente o successiva |
| Scorri tra le pagine (3 dita) | 3 dita a destra o sinistra: pagina indietro o avanti |
| Trascinamento con tre dita | 3 dita trascinano finestre e selezionano |
| Clic secondario con due dita | tocco con 2 dita |
| Clic secondario nell'angolo | tocco nell'angolo in basso a destra o a sinistra |
| Cerca e rileva (tocco con tre dita) | tocco con 3 dita (altrimenti clic centrale) |
| Ingrandisci o riduci con il pizzico | pizzico con 2 dita (⌘+ e ⌘-) |
| Launchpad / Mostra scrivania | pizzico con 4 dita verso l'interno o verso l'esterno |

macOS non permette ai programmi di generare le vere gesture del trackpad: USB Trackpad ottiene lo stesso effetto
con le scorciatoie e le app di sistema. Lo scorrimento tra scrivanie usa ⌃← e ⌃→, quindi quelle scorciatoie devono
essere attive (Impostazioni di Sistema → Tastiera → Abbreviazioni → Mission Control). Su Windows i gesti a 3 o più
dita e il pizzico per ora non fanno nulla.

### Schermi OLED e AMOLED

Gli schermi OLED possono "bruciarsi" se mostrano a lungo le stesse immagini. Su questi telefoni USB Trackpad:

- usa sempre il tema scuro con nero assoluto (i pixel restano spenti), qualunque sia il tema del computer;
- quando il computer si collega mostra il suo nome per tre secondi, poi lo fa sparire;
- quando il computer non è collegato fa lampeggiare lo stato, invece di lasciarlo fisso;
- sposta lo stato di qualche pixel a ogni cambio, per non consumare sempre gli stessi punti.

Android non dice direttamente se lo schermo è OLED. L'app lo capisce dal supporto all'always-on display e da un elenco di modelli noti. Se sbaglia, puoi forzare la scelta con `TRACKPAD_OLED` (vedi sotto). Il registro mostra cosa è stato rilevato:

```
LGE Nexus 5X · Android 6.0.1 · OLED non rilevato
```

Sugli schermi LCD lo stato resta sempre visibile e il tema segue il computer.

### Limitatore di carica

Un telefono usato come trackpad resta sempre attaccato al cavo, e tenere la batteria sempre al 100% la consuma.
Sui telefoni **con root** l'app ferma la ricarica quando la batteria arriva al **60%** e la fa ripartire solo quando scende al **50%**.

- Funziona solo con il root: Android non permette alle app normali di controllare la ricarica. Senza root il limitatore resta spento e non compare nessuna richiesta.
- La prima volta il gestore del root (per esempio Magisk) chiede di autorizzare l'app.
- Il nome del comando che ferma la ricarica cambia da telefono a telefono: l'app prova quelli più diffusi (Qualcomm, Pixel, Samsung, OnePlus, Motorola e altri).
- Quando l'app viene chiusa la ricarica riparte sempre, così il telefono non resta mai senza carica.
- Il registro del computer mostra lo stato, per esempio `limitatore di carica: attivo 60/50%` oppure `non disponibile (serve il root)`, e ogni pausa o ripresa.

## Impostazioni

| Variabile | Predefinito | Effetto |
|---|---|---|
| `TRACKPAD_SPEED` | `1.6` | velocità del cursore |
| `TRACKPAD_ACCEL` | `0.07` | accelerazione del cursore |
| `TRACKPAD_SCROLL` | `1.8` | velocità di scorrimento |
| `TRACKPAD_NATURAL` | Mac: come il Mac; Windows: naturale | `1` scorrimento naturale, `0` tradizionale |
| `TRACKPAD_OLED` | automatico | `1` forza la modalità OLED, `0` la disattiva |
| `TRACKPAD_CHARGE` | `60/50` | soglie del limitatore di carica (massimo/minimo), oppure `off` |
| `ADB` | automatico | percorso di un `adb` specifico |

- **Mac:** aggiungile in `~/Library/LaunchAgents/it.usbtrackpad.agent.plist`, nella sezione `EnvironmentVariables`, poi riavvia con `./install.sh`.
- **Windows:** impostale come variabili d'ambiente dell'utente (Impostazioni → Sistema → Informazioni → Impostazioni di sistema avanzate → Variabili d'ambiente), poi esci e rientra in Windows.

## Disinstallazione

- **Mac:** `./uninstall.sh`, poi togli `usbtrackpad` da Accessibilità.
- **Windows:** doppio clic su `uninstall.cmd`.

Sul telefono disinstalla l'app "Trackpad".

## Risoluzione dei problemi

Registro: Mac `~/Library/Logs/USBTrackpad.log`, Windows `%LOCALAPPDATA%\USBTrackpad\USBTrackpad.log`.

| Problema | Soluzione |
|---|---|
| "Mac non autorizzato" | abilita `usbtrackpad` in Accessibilità; se era già abilitato dopo un aggiornamento, toglilo e rimettilo |
| Il trackpad non si apre al collegamento | controlla che il Debug USB sia attivo e che il cavo trasferisca dati; nel registro deve comparire "Telefono collegato via USB" |
| "Sul telefono accetta Consentire il debug USB?" | sblocca il telefono e accetta l'autorizzazione |
| Windows non vede il telefono | installa il driver USB del produttore del telefono |
| Su Windows il cursore non si muove sopra alcune finestre | succede con le app avviate come amministratore: Windows blocca l'input dai programmi normali |
| Lo scorrimento va al contrario | imposta `TRACKPAD_NATURAL` a `0` o `1` |
| Il telefono è OLED ma il tema segue il computer | imposta `TRACKPAD_OLED=1` |

## Sicurezza

- **Solo cavo USB.** Il programma sul computer accetta connessioni solo in locale (`127.0.0.1`): dalla rete Wi-Fi o da internet non è raggiungibile.
- **Chiave segreta.** Il tunnel USB rende la porta del computer raggiungibile anche dalle altre app del telefono. Per questo il computer genera una chiave casuale di 256 bit, la salva con permessi riservati all'utente e la consegna all'app tramite adb, un canale a cui le altre app non hanno accesso. Ogni collegamento senza la chiave giusta viene chiuso subito; il confronto avviene a tempo costante.
- **Comandi controllati.** Valori anomali (NaN, infinito, numeri enormi) vengono scartati o limitati, e le righe troppo lunghe chiudono la connessione.
- **Nessun dato raccolto.** L'app non usa internet: il permesso di rete serve solo per il collegamento locale attraverso il cavo. Nessuna statistica, nessun account, nessun backup dei dati dell'app.
- **Root.** Sui telefoni con root l'app esegue solo comandi fissi per mettere in pausa o riprendere la ricarica.
- **Download.** L'installazione scarica adb solo dai server ufficiali di Google, in HTTPS.
- **Debug USB.** Lasciare il Debug USB attivo permette a un computer autorizzato di controllare il telefono: autorizza solo i tuoi computer. Puoi revocare le autorizzazioni in Opzioni sviluppatore → Revoca autorizzazioni debug USB.

Se trovi un problema di sicurezza, apri una segnalazione nella pagina **Issues** del progetto.

## Compilare dal codice

Serve un Mac Apple Silicon con:
- Swift (Xcode o Command Line Tools)
- Go 1.27 o successivo (per la versione Windows)
- Android SDK in `~/Library/Android/sdk` con `build-tools;36.1.0` e `platforms;android-36`
- un JDK 17 o successivo

```bash
./build.sh
```

I pacchetti pronti finiscono in `dist/mac` e `dist/windows`. L'app Android si compila senza Gradle, direttamente con gli strumenti dell'SDK (`aapt2`, `javac`, `d8`, `apksigner`). Per ricompilare solo l'APK: `./build-apk.sh`.

### Struttura

```
src/main.swift                    programma per Mac: USB, eventi mouse, tema
windows/main.go                   programma per Windows (x64 e ARM64): USB, SendInput, tema
android/src/it/usbtrackpad/       app Android: trackpad, gesti, rilevamento OLED, limitatore di carica
android/res/                      icona
scripts/install.sh, uninstall.sh  installazione su Mac (LaunchAgent)
scripts/windows/                  installazione su Windows (avvio automatico all'accesso)
build.sh, build-apk.sh            compilazione
```

### Come funziona

1. Il programma sul computer parte all'accesso e ogni due secondi controlla con `adb` se c'è un telefono collegato.
2. Quando lo trova, installa l'app se manca o è vecchia, crea un tunnel `adb reverse` sulla porta 27182 e apre il trackpad.
3. L'app si collega a `localhost:27182` sul telefono; il tunnel la porta, attraverso il cavo, al computer.
4. I gesti viaggiano come righe di testo (`m dx dy`, `s dx dy fase`, `c l`, …). Il computer le trasforma in eventi mouse (CoreGraphics sul Mac, SendInput su Windows).
5. Il computer invia all'app il proprio nome, il tema attuale, lo stato dei permessi, la modalità OLED e le soglie del limitatore di carica, appena cambiano e comunque ogni tre secondi.

## Crediti

Palette colori ispirata a [emanuelebenassi.com](https://www.emanuelebenassi.com).

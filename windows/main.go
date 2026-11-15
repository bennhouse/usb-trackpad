//go:build windows

// USBTrackpad: programma per Windows (x64 e ARM64).
// Fa lo stesso lavoro della versione per Mac:
//   - sorveglia il cavo USB: quando il telefono compare installa/aggiorna l'app,
//     crea il collegamento `adb reverse` e apre il trackpad (plug and play);
//   - riceve i gesti dall'app (TCP solo su loopback) e li trasforma in eventi
//     mouse e rotella reali con SendInput;
//   - invia all'app il tema di Windows (chiaro/scuro).
// Compilato come applicazione senza finestra: il registro è in
// %LOCALAPPDATA%\USBTrackpad\USBTrackpad.log
package main

import (
	"bufio"
	"context"
	"crypto/rand"
	"crypto/subtle"
	"encoding/hex"
	"fmt"
	"io"
	"log"
	"math"
	"net"
	"os"
	"os/exec"
	"path/filepath"
	"strconv"
	"strings"
	"sync"
	"syscall"
	"time"
	"unsafe"
)

// ------------------------------------------------------------------ configurazione

const appPackage = "it.usbtrackpad"

var (
	port        = envInt("TRACKPAD_PORT", 27182)
	speed       = envFloat("TRACKPAD_SPEED", 1.6)
	accel       = envFloat("TRACKPAD_ACCEL", 0.07)
	scrollSpeed = envFloat("TRACKPAD_SCROLL", 1.8)
	natural     = os.Getenv("TRACKPAD_NATURAL") != "0" // predefinito: naturale, come i touchpad di Windows
	oledMode    = parseOled(os.Getenv("TRACKPAD_OLED"))
	chargeLine  = parseCharge(envStr("TRACKPAD_CHARGE", "60/50"))
	exeDir      = func() string { p, _ := os.Executable(); return filepath.Dir(p) }()
	apkPath     = envStr("TRACKPAD_APK", filepath.Join(exeDir, "USBTrackpad.apk"))
)

func envStr(k, def string) string {
	if v := os.Getenv(k); v != "" {
		return v
	}
	return def
}
func envInt(k string, def int) int {
	if v, err := strconv.Atoi(os.Getenv(k)); err == nil {
		return v
	}
	return def
}
func envFloat(k string, def float64) float64 {
	if v, err := strconv.ParseFloat(os.Getenv(k), 64); err == nil {
		return v
	}
	return def
}
// Limitatore di carica sui telefoni con root: "60/50" (massimo/minimo) oppure "off".
func parseCharge(v string) string {
	p := strings.Split(strings.ToLower(v), "/")
	if len(p) == 2 {
		max, e1 := strconv.Atoi(p[0])
		min, e2 := strconv.Atoi(p[1])
		if e1 == nil && e2 == nil {
			return fmt.Sprintf("charge %d %d", max, min)
		}
	}
	return "charge off"
}

func parseOled(v string) string {
	switch strings.ToLower(v) {
	case "1", "on":
		return "1"
	case "0", "off":
		return "0"
	}
	return "auto"
}

// ------------------------------------------------------------------ Win32

var (
	user32   = syscall.NewLazyDLL("user32.dll")
	shcore   = syscall.NewLazyDLL("shcore.dll")
	advapi32 = syscall.NewLazyDLL("advapi32.dll")

	pSendInput                     = user32.NewProc("SendInput")
	pGetCursorPos                  = user32.NewProc("GetCursorPos")
	pGetSystemMetrics              = user32.NewProc("GetSystemMetrics")
	pMonitorFromPoint              = user32.NewProc("MonitorFromPoint")
	pGetMonitorInfoW               = user32.NewProc("GetMonitorInfoW")
	pSetProcessDpiAwarenessContext = user32.NewProc("SetProcessDpiAwarenessContext")
	pSetProcessDPIAware            = user32.NewProc("SetProcessDPIAware")
	pGetDpiForMonitor              = shcore.NewProc("GetDpiForMonitor")
	pRegGetValueW                  = advapi32.NewProc("RegGetValueW")
)

const (
	inputMouse = 0

	mouseMove       = 0x0001
	mouseLeftDown   = 0x0002
	mouseLeftUp     = 0x0004
	mouseRightDown  = 0x0008
	mouseRightUp    = 0x0010
	mouseMiddleDown = 0x0020
	mouseMiddleUp   = 0x0040
	mouseWheel      = 0x0800
	mouseHWheel     = 0x1000
	mouseVirtual    = 0x4000
	mouseAbsolute   = 0x8000

	smXVirtual  = 76
	smYVirtual  = 77
	smCXVirtual = 78
	smCYVirtual = 79

	monitorDefaultToNull    = 0
	monitorDefaultToNearest = 2
)

type point struct{ X, Y int32 }
type rect struct{ Left, Top, Right, Bottom int32 }

type monitorInfo struct {
	Size    uint32
	Monitor rect
	Work    rect
	Flags   uint32
}

type mouseInput struct {
	Dx, Dy    int32
	MouseData uint32
	Flags     uint32
	Time      uint32
	ExtraInfo uintptr
}

type input struct {
	Type uint32
	Mi   mouseInput
}

func sendMouse(flags uint32, dx, dy int32, data int32) {
	in := input{Type: inputMouse, Mi: mouseInput{Dx: dx, Dy: dy, MouseData: uint32(data), Flags: flags}}
	pSendInput.Call(1, uintptr(unsafe.Pointer(&in)), unsafe.Sizeof(in))
}

func cursorPos() point {
	var p point
	pGetCursorPos.Call(uintptr(unsafe.Pointer(&p)))
	return p
}

func metric(i int) int32 {
	r, _, _ := pGetSystemMetrics.Call(uintptr(i))
	return int32(r)
}

func pointArg(p point) uintptr { return uintptr(uint32(p.X)) | uintptr(uint32(p.Y))<<32 }

func monitorAt(p point, flags uintptr) uintptr {
	h, _, _ := pMonitorFromPoint.Call(pointArg(p), flags)
	return h
}

func monitorRect(h uintptr) rect {
	mi := monitorInfo{Size: uint32(unsafe.Sizeof(monitorInfo{}))}
	pGetMonitorInfoW.Call(h, uintptr(unsafe.Pointer(&mi)))
	return mi.Monitor
}

/// Fattore di scala dello schermo (100% = 1, 150% = 1.5): il cursore va alla stessa velocità ovunque.
func monitorScale(h uintptr) float64 {
	if pGetDpiForMonitor.Find() != nil {
		return 1
	}
	var x, y uint32
	if r, _, _ := pGetDpiForMonitor.Call(h, 0, uintptr(unsafe.Pointer(&x)), uintptr(unsafe.Pointer(&y))); r != 0 || x == 0 {
		return 1
	}
	return float64(x) / 96
}

func regDWORD(path, name string) (uint32, bool) {
	if pRegGetValueW.Find() != nil {
		return 0, false
	}
	const hkcu = 0x80000001
	const rrfRtDword = 0x00000010
	p, _ := syscall.UTF16PtrFromString(path)
	n, _ := syscall.UTF16PtrFromString(name)
	var data, size uint32 = 0, 4
	r, _, _ := pRegGetValueW.Call(hkcu, uintptr(unsafe.Pointer(p)), uintptr(unsafe.Pointer(n)),
		rrfRtDword, 0, uintptr(unsafe.Pointer(&data)), uintptr(unsafe.Pointer(&size)))
	return data, r == 0
}

func windowsIsDark() bool {
	v, ok := regDWORD(`Software\Microsoft\Windows\CurrentVersion\Themes\Personalize`, "AppsUseLightTheme")
	return ok && v == 0
}

// ------------------------------------------------------------------ mouse

type mouse struct {
	mu         sync.Mutex
	remX, remY float64 // frazioni di pixel non ancora applicate
	wheelV     float64 // rotella verticale accumulata
	wheelH     float64 // rotella orizzontale accumulata
	pressed    map[byte]bool
}

var ms = &mouse{pressed: map[byte]bool{}}

func (m *mouse) move(dx, dy float64) {
	v := math.Hypot(dx, dy)
	gain := speed * (1 + math.Min(accel*v, 4))
	old := cursorPos()
	scale := monitorScale(monitorAt(old, monitorDefaultToNearest))
	m.remX += dx * gain * scale
	m.remY += dy * gain * scale
	ix, iy := math.Trunc(m.remX), math.Trunc(m.remY)
	if ix == 0 && iy == 0 {
		return
	}
	m.remX -= ix
	m.remY -= iy
	p := point{old.X + int32(ix), old.Y + int32(iy)}

	// resta dentro gli schermi, anche con più monitor
	if monitorAt(p, monitorDefaultToNull) == 0 {
		r := monitorRect(monitorAt(old, monitorDefaultToNearest))
		p.X = min(max(p.X, r.Left), r.Right-1)
		p.Y = min(max(p.Y, r.Top), r.Bottom-1)
	}

	// posizione assoluta sul desktop virtuale (0..65535): niente accelerazione di Windows
	vx, vy := metric(smXVirtual), metric(smYVirtual)
	vw, vh := metric(smCXVirtual), metric(smCYVirtual)
	if vw < 2 || vh < 2 {
		return
	}
	nx := int32(math.Round(float64(p.X-vx) * 65535 / float64(vw-1)))
	ny := int32(math.Round(float64(p.Y-vy) * 65535 / float64(vh-1)))
	sendMouse(mouseMove|mouseAbsolute|mouseVirtual, nx, ny, 0)
}

func buttonFlags(b byte) (down, up uint32) {
	switch b {
	case 'r':
		return mouseRightDown, mouseRightUp
	case 'm':
		return mouseMiddleDown, mouseMiddleUp
	}
	return mouseLeftDown, mouseLeftUp
}

// Il doppio clic lo riconosce Windows da solo, in base al tempo tra due clic.
func (m *mouse) down(b byte) {
	d, _ := buttonFlags(b)
	m.pressed[b] = true
	sendMouse(d, 0, 0, 0)
}

func (m *mouse) up(b byte) {
	if !m.pressed[b] {
		return
	}
	delete(m.pressed, b)
	_, u := buttonFlags(b)
	sendMouse(u, 0, 0, 0)
}

// Scorrimento a 2 dita, verticale e orizzontale. Windows non ha le fasi
// di scorrimento del Mac: arrivano solo gli spostamenti (inerzia compresa).
func (m *mouse) scroll(dx, dy float64, phase string) {
	if phase == "b" {
		m.wheelV, m.wheelH = 0, 0
	}
	sign := 1.0
	if !natural {
		sign = -1
	}
	// 120 = uno scatto di rotella; le app moderne accettano anche frazioni di scatto
	m.wheelV += dy * scrollSpeed * 1.2 * sign
	m.wheelH -= dx * scrollSpeed * 1.2 * sign
	const step = 8
	if math.Abs(m.wheelV) >= step {
		n := math.Trunc(m.wheelV)
		m.wheelV -= n
		sendMouse(mouseWheel, 0, 0, int32(n))
	}
	if math.Abs(m.wheelH) >= step {
		n := math.Trunc(m.wheelH)
		m.wheelH -= n
		sendMouse(mouseHWheel, 0, 0, int32(n))
	}
}

func (m *mouse) releaseAll() {
	m.mu.Lock()
	defer m.mu.Unlock()
	for b := range m.pressed {
		m.up(b)
	}
}

// Protocollo (una riga per comando):
//
//	m dx dy | s dx dy fase | c b | d b [n] | u b | i testo      (b = l/r/m)
func (m *mouse) handle(line string) {
	t := strings.Fields(line)
	if len(t) == 0 {
		return
	}
	// valori anomali (NaN, infinito, enormi) scartati o limitati
	num := func(i int) float64 {
		if i < len(t) {
			f, err := strconv.ParseFloat(t[i], 64)
			if err != nil || math.IsNaN(f) || math.IsInf(f, 0) {
				return 0
			}
			return math.Max(-2000, math.Min(2000, f))
		}
		return 0
	}
	btn := func() byte {
		if len(t) > 1 && len(t[1]) > 0 {
			return t[1][0]
		}
		return 'l'
	}
	m.mu.Lock()
	defer m.mu.Unlock()
	switch t[0] {
	case "m":
		m.move(num(1), num(2))
	case "s":
		phase := "c"
		if len(t) > 3 {
			phase = t[3]
		}
		m.scroll(num(1), num(2), phase)
	case "c":
		m.down(btn())
		m.up(btn())
	case "d":
		m.down(btn())
	case "u":
		m.up(btn())
	case "i":
		log.Printf("   %s", strings.TrimPrefix(line, "i "))
	}
}

// ------------------------------------------------------------------ chiave segreta

// Chiave condivisa con l'app. Arriva al telefono solo tramite adb (am start --es token):
// le altre app del telefono, che pure raggiungono la porta tramite adb reverse, non la conoscono.
var token = func() string {
	dir := filepath.Join(os.Getenv("LOCALAPPDATA"), "USBTrackpad")
	path := filepath.Join(dir, "token")
	if b, err := os.ReadFile(path); err == nil && len(strings.TrimSpace(string(b))) >= 32 {
		return strings.TrimSpace(string(b))
	}
	buf := make([]byte, 32)
	rand.Read(buf)
	t := hex.EncodeToString(buf)
	os.MkdirAll(dir, 0o700)
	os.WriteFile(path, []byte(t), 0o600)
	return t
}()

// ------------------------------------------------------------------ server TCP

// Nome del PC (Impostazioni → Sistema → Informazioni), mostrato in alto sul telefono.
var computerName = func() string {
	if n, err := os.Hostname(); err == nil && n != "" {
		return n
	}
	return "PC"
}()

var (
	clientsMu sync.Mutex
	clients   = map[net.Conn]bool{}
)

func statusLines() string {
	theme := "light"
	if windowsIsDark() {
		theme = "dark"
	}
	// su Windows non serve nessun permesso speciale per muovere il mouse
	return fmt.Sprintf("host %s\ntheme %s\nax 1\noled %s\n%s\n", computerName, theme, oledMode, chargeLine)
}

func broadcast(s string) {
	clientsMu.Lock()
	defer clientsMu.Unlock()
	for c := range clients {
		c.SetWriteDeadline(time.Now().Add(2 * time.Second))
		c.Write([]byte(s))
	}
}

func serve(c net.Conn) {
	if tc, ok := c.(*net.TCPConn); ok {
		tc.SetNoDelay(true)
	}
	sc := bufio.NewScanner(c) // righe oltre 64 KB: la lettura si ferma e la connessione si chiude

	// la prima riga deve essere la chiave giusta, entro 10 secondi
	c.SetReadDeadline(time.Now().Add(10 * time.Second))
	if !sc.Scan() || !strings.HasPrefix(sc.Text(), "auth ") ||
		subtle.ConstantTimeCompare([]byte(strings.TrimPrefix(sc.Text(), "auth ")), []byte(token)) != 1 {
		log.Printf("⛔️ Connessione rifiutata: chiave non valida")
		c.Close()
		return
	}
	c.SetReadDeadline(time.Time{})

	clientsMu.Lock()
	clients[c] = true
	clientsMu.Unlock()
	c.Write([]byte(statusLines()))
	log.Printf("📱 Trackpad connesso")

	for sc.Scan() {
		ms.handle(sc.Text())
	}

	clientsMu.Lock()
	delete(clients, c)
	clientsMu.Unlock()
	c.Close()
	ms.releaseAll()
	log.Printf("🔌 Trackpad disconnesso")
}

// ------------------------------------------------------------------ USB / adb (plug and play)

var adbPath = findAdb()

func findAdb() string {
	cands := []string{os.Getenv("ADB"), filepath.Join(exeDir, "platform-tools", "adb.exe")}
	if h := os.Getenv("ANDROID_HOME"); h != "" {
		cands = append(cands, filepath.Join(h, "platform-tools", "adb.exe"))
	}
	cands = append(cands, filepath.Join(os.Getenv("LOCALAPPDATA"), "Android", "Sdk", "platform-tools", "adb.exe"))
	for _, c := range cands {
		if c == "" {
			continue
		}
		if st, err := os.Stat(c); err == nil && !st.IsDir() {
			return c
		}
	}
	if p, err := exec.LookPath("adb"); err == nil {
		return p
	}
	return ""
}

func adbCmd(ctx context.Context, args ...string) *exec.Cmd {
	cmd := exec.CommandContext(ctx, adbPath, args...)
	// nessuna finestra del prompt dei comandi a ogni controllo
	cmd.SysProcAttr = &syscall.SysProcAttr{HideWindow: true, CreationFlags: 0x08000000} // CREATE_NO_WINDOW
	return cmd
}

func adb(serial string, args ...string) (string, bool) {
	if adbPath == "" {
		return "", false
	}
	if serial != "" {
		args = append([]string{"-s", serial}, args...)
	}
	ctx, cancel := context.WithTimeout(context.Background(), 60*time.Second)
	defer cancel()
	out, err := adbCmd(ctx, args...).CombinedOutput()
	return string(out), err == nil
}

// Avvia il server adb senza collegarne l'output: su Windows il demone
// erediterebbe le pipe e bloccherebbe la lettura dei comandi successivi.
func startAdbServer() {
	if adbPath == "" {
		return
	}
	ctx, cancel := context.WithTimeout(context.Background(), 30*time.Second)
	defer cancel()
	cmd := adbCmd(ctx, "start-server")
	cmd.Stdout, cmd.Stderr = nil, nil
	cmd.Run()
}

func ensureApp(serial string) {
	st, err := os.Stat(apkPath)
	if err != nil {
		log.Printf("⚠️  APK non trovato in %s", apkPath)
		return
	}
	stamp := fmt.Sprintf("%d-%d", st.Size(), st.ModTime().Unix())
	marker := filepath.Join(exeDir, ".installed-"+serial)
	out, _ := adb(serial, "shell", "pm", "path", appPackage)
	if prev, _ := os.ReadFile(marker); strings.Contains(out, "package:") && string(prev) == stamp {
		return
	}
	log.Printf("📲 Installo l'app sul telefono…")
	if out, ok := adb(serial, "install", "-r", apkPath); ok && strings.Contains(out, "Success") {
		os.WriteFile(marker, []byte(stamp), 0o644)
		log.Printf("   installata")
	} else {
		log.Printf("   installazione non riuscita: %s", strings.TrimSpace(out))
	}
}

func watchUSB() {
	startAdbServer()
	state := ""
	for {
		out, _ := adb("", "devices")
		serial, unauthorized := "", false
		for _, l := range strings.Split(out, "\n")[1:] {
			f := strings.Fields(l)
			if len(f) < 2 {
				continue
			}
			if f[1] == "device" && serial == "" {
				serial = f[0]
			}
			if f[1] == "unauthorized" {
				unauthorized = true
			}
		}
		if serial == "" {
			s := "none"
			if unauthorized {
				s = "unauthorized"
			}
			if s != state {
				if s == "unauthorized" {
					log.Printf("🔐 Sul telefono accetta \"Consentire il debug USB?\"")
				} else if state == "device" {
					log.Printf("⏳ Telefono scollegato, in attesa…")
				}
				state = s
			}
		} else {
			rev, _ := adb(serial, "reverse", "--list")
			if !strings.Contains(rev, fmt.Sprintf("tcp:%d", port)) || state != "device" {
				state = "device"
				log.Printf("🔗 Telefono collegato via USB")
				ensureApp(serial)
				adb(serial, "reverse", fmt.Sprintf("tcp:%d", port), fmt.Sprintf("tcp:%d", port))
				adb(serial, "shell", "am", "start", "-n", appPackage+"/.MainActivity", "--es", "token", token)
			}
		}
		time.Sleep(2 * time.Second)
	}
}

// ------------------------------------------------------------------ avvio

func setupLog() {
	dir := filepath.Join(os.Getenv("LOCALAPPDATA"), "USBTrackpad")
	os.MkdirAll(dir, 0o755)
	f, err := os.OpenFile(filepath.Join(dir, "USBTrackpad.log"), os.O_CREATE|os.O_WRONLY|os.O_APPEND, 0o644)
	if err != nil {
		return
	}
	log.SetOutput(io.MultiWriter(f, os.Stdout))
	log.SetFlags(log.Ltime)
}

func main() {
	setupLog()

	// coordinate reali dei pixel anche con il ridimensionamento al 125/150%
	if pSetProcessDpiAwarenessContext.Find() == nil {
		pSetProcessDpiAwarenessContext.Call(^uintptr(3)) // DPI_AWARENESS_CONTEXT_PER_MONITOR_AWARE_V2 (-4)
	} else {
		pSetProcessDPIAware.Call()
	}

	ln, err := net.Listen("tcp", fmt.Sprintf("127.0.0.1:%d", port)) // solo via cavo (adb reverse)
	if err != nil {
		log.Printf("❌ Impossibile avviare il server (è già in esecuzione?): %v", err)
		os.Exit(1)
	}
	dark := windowsIsDark()
	log.Printf("✅ USBTrackpad attivo su localhost:%d · tema Windows: %s · scorrimento naturale: %v",
		port, map[bool]string{true: "scuro", false: "chiaro"}[dark], natural)
	if adbPath == "" {
		log.Printf("⚠️  adb non trovato: reinstalla con install.cmd oppure imposta ADB=percorso\\adb.exe")
	}

	// tema: controllo ogni secondo (cambio immediato) e stato completo ogni 3 s
	go func() {
		last, tick := dark, 0
		for range time.Tick(time.Second) {
			tick++
			if d := windowsIsDark(); d != last {
				last = d
				log.Printf("🎨 Tema di Windows: %s", map[bool]string{true: "scuro", false: "chiaro"}[d])
				broadcast(statusLines())
			} else if tick%3 == 0 {
				broadcast(statusLines())
			}
		}
	}()
	go watchUSB()

	for {
		c, err := ln.Accept()
		if err != nil {
			continue
		}
		go serve(c)
	}
}

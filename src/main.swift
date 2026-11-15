// USBTrackpad: server per Mac.
// - Sorveglia il cavo USB: quando il telefono compare installa/aggiorna l'app,
//   crea il collegamento `adb reverse` e apre il trackpad (plug and play).
// - Riceve i gesti dall'app (TCP solo su loopback) e li trasforma in eventi
//   mouse e scorrimento reali, con le fasi native dei trackpad Apple.
// - Invia all'app il tema del Mac (chiaro/scuro) e lo stato del permesso Accessibilità.

import AppKit
import ApplicationServices
import CoreGraphics
import Foundation
import Network
import Security
import SystemConfiguration

// MARK: - Configurazione

let env = ProcessInfo.processInfo.environment
let port = UInt16(env["TRACKPAD_PORT"] ?? "") ?? 27182
let speed = Double(env["TRACKPAD_SPEED"] ?? "") ?? 1.6          // velocità base
let accel = Double(env["TRACKPAD_ACCEL"] ?? "") ?? 0.07         // accelerazione
let scrollSpeed = Double(env["TRACKPAD_SCROLL"] ?? "") ?? 1.8
let appPackage = "it.usbtrackpad"
/// Modalità OLED sul telefono: "1" forza, "0" disattiva, assente = rilevamento automatico.
/// Limitatore di carica sui telefoni con root: "60/50" (massimo/minimo) oppure "off".
let chargeLine: String = {
    let v = (env["TRACKPAD_CHARGE"] ?? "60/50").lowercased()
    let p = v.split(separator: "/").compactMap { Int($0) }
    return v == "off" || p.count != 2 ? "charge off" : "charge \(p[0]) \(p[1])"
}()
let oledMode = env["TRACKPAD_OLED"].map { $0 == "1" || $0.lowercased() == "on" ? "1" : $0 == "0" || $0.lowercased() == "off" ? "0" : "auto" } ?? "auto"
let exeDir = URL(fileURLWithPath: CommandLine.arguments[0]).resolvingSymlinksInPath().deletingLastPathComponent()
let apkPath = env["TRACKPAD_APK"] ?? exeDir.appendingPathComponent("USBTrackpad.apk").path

/// Chiave segreta condivisa con l'app. Arriva al telefono solo tramite adb (`am start --es token`):
/// le altre app del telefono, che pure possono raggiungere la porta tramite `adb reverse`, non la conoscono.
let token: String = {
    let dir = NSHomeDirectory() + "/Library/Application Support/USBTrackpad"
    let path = dir + "/token"
    if let t = try? String(contentsOfFile: path, encoding: .utf8).trimmingCharacters(in: .whitespacesAndNewlines), t.count >= 32 {
        return t
    }
    var bytes = [UInt8](repeating: 0, count: 32)
    _ = SecRandomCopyBytes(kSecRandomDefault, bytes.count, &bytes)
    let t = bytes.map { String(format: "%02x", $0) }.joined()
    try? FileManager.default.createDirectory(atPath: dir, withIntermediateDirectories: true)
    FileManager.default.createFile(atPath: path, contents: Data(t.utf8), attributes: [.posixPermissions: 0o600])
    return t
}()

/// Confronto a tempo costante (non rivela quanti caratteri sono giusti).
func sameSecret(_ a: String, _ b: String) -> Bool {
    let x = Array(a.utf8), y = Array(b.utf8)
    guard x.count == y.count else { return false }
    var d: UInt8 = 0
    for i in 0..<x.count { d |= x[i] ^ y[i] }
    return d == 0
}

func log(_ s: String) {
    let f = DateFormatter(); f.dateFormat = "HH:mm:ss"
    print("[\(f.string(from: Date()))] \(s)")
    fflush(stdout)
}

func globalPref(_ key: String) -> Any? {
    CFPreferencesAppSynchronize(kCFPreferencesAnyApplication)
    return CFPreferencesCopyAppValue(key as CFString, kCFPreferencesAnyApplication)
}

/// Scorrimento naturale: come impostato sul Mac, salvo TRACKPAD_NATURAL=0/1.
func naturalScroll() -> Bool {
    if let v = env["TRACKPAD_NATURAL"] { return v != "0" }
    return (globalPref("com.apple.swipescrolldirection") as? Bool) ?? true
}

// MARK: - Mouse

final class Mouse {
    private let src = CGEventSource(stateID: .hidSystemState)
    private var pressed = Set<UInt32>()               // pulsanti tenuti premuti
    private var lastClickTime: TimeInterval = 0
    private var lastClickButton: CGMouseButton = .left
    private var lastClickPos = CGPoint.zero
    private var clickCount: Int64 = 1
    private var scrollRemX = 0.0, scrollRemY = 0.0
    private var natural = naturalScroll()

    private var location: CGPoint { CGEvent(source: nil)?.location ?? .zero }

    private func displays() -> [CGRect] {
        var ids = [CGDirectDisplayID](repeating: 0, count: 16)
        var n: UInt32 = 0
        CGGetActiveDisplayList(16, &ids, &n)
        return ids.prefix(Int(n)).map { CGDisplayBounds($0) }
    }

    /// Tiene il cursore dentro gli schermi (anche con più monitor).
    private func clamp(_ p: CGPoint, from old: CGPoint) -> CGPoint {
        let rects = displays()
        if rects.contains(where: { $0.contains(p) }) { return p }
        let home = rects.first(where: { $0.contains(old) }) ?? rects.first ?? .zero
        return CGPoint(x: min(max(p.x, home.minX), home.maxX - 1),
                       y: min(max(p.y, home.minY), home.maxY - 1))
    }

    private static func button(_ s: Substring) -> CGMouseButton {
        switch s { case "r": return .right; case "m": return .center; default: return .left }
    }

    private func types(_ b: CGMouseButton) -> (down: CGEventType, up: CGEventType) {
        switch b {
        case .right: return (.rightMouseDown, .rightMouseUp)
        case .center: return (.otherMouseDown, .otherMouseUp)
        default: return (.leftMouseDown, .leftMouseUp)
        }
    }

    func move(_ dx: Double, _ dy: Double) {
        let v = (dx * dx + dy * dy).squareRoot()
        let gain = speed * (1 + min(accel * v, 4))
        let mx = dx * gain, my = dy * gain
        let old = location
        let p = clamp(CGPoint(x: old.x + mx, y: old.y + my), from: old)

        var type = CGEventType.mouseMoved
        var btn = CGMouseButton.left
        if pressed.contains(CGMouseButton.left.rawValue) { type = .leftMouseDragged }
        else if pressed.contains(CGMouseButton.right.rawValue) { type = .rightMouseDragged; btn = .right }
        else if pressed.contains(CGMouseButton.center.rawValue) { type = .otherMouseDragged; btn = .center }

        guard let e = CGEvent(mouseEventSource: src, mouseType: type, mouseCursorPosition: p, mouseButton: btn) else { return }
        e.setIntegerValueField(.mouseEventDeltaX, value: Int64(mx.rounded()))
        e.setIntegerValueField(.mouseEventDeltaY, value: Int64(my.rounded()))
        e.post(tap: .cghidEventTap)
    }

    func down(_ b: CGMouseButton, counted: Bool = true) {
        let p = location
        if counted {
            let now = ProcessInfo.processInfo.systemUptime
            let near = hypot(p.x - lastClickPos.x, p.y - lastClickPos.y) < 6
            if now - lastClickTime < NSEvent.doubleClickInterval && b == lastClickButton && near {
                clickCount += 1
            } else {
                clickCount = 1
            }
            lastClickTime = now; lastClickButton = b; lastClickPos = p
        } else {
            clickCount = 1
        }
        pressed.insert(b.rawValue)
        post(types(b).down, b, p)
    }

    func up(_ b: CGMouseButton) {
        guard pressed.remove(b.rawValue) != nil else { return }
        post(types(b).up, b, location)
    }

    func click(_ b: CGMouseButton) { down(b); up(b) }

    private func post(_ t: CGEventType, _ b: CGMouseButton, _ p: CGPoint) {
        guard let e = CGEvent(mouseEventSource: src, mouseType: t, mouseCursorPosition: p, mouseButton: b) else { return }
        e.setIntegerValueField(.mouseEventClickState, value: clickCount)
        e.post(tap: .cghidEventTap)
    }

    /// Scorrimento a 2 dita, verticale e orizzontale, con le fasi dei trackpad Apple:
    /// b = inizio, c = in corso, e = fine, mb/mc/me = inerzia inizio/in corso/fine.
    func scroll(_ dx: Double, _ dy: Double, phase: Substring) {
        if phase == "b" { natural = naturalScroll(); scrollRemX = 0; scrollRemY = 0 }
        let sign = natural ? 1.0 : -1.0
        scrollRemX += dx * scrollSpeed * sign
        scrollRemY += dy * scrollSpeed * sign
        let ix = Int32(scrollRemX.rounded(.towardZero)), iy = Int32(scrollRemY.rounded(.towardZero))
        let edge = phase == "b" || phase == "e" || phase == "mb" || phase == "me"
        guard ix != 0 || iy != 0 || edge else { return }
        scrollRemX -= Double(ix); scrollRemY -= Double(iy)
        guard let e = CGEvent(scrollWheelEvent2Source: src, units: .pixel, wheelCount: 2,
                              wheel1: iy, wheel2: ix, wheel3: 0) else { return }
        e.setIntegerValueField(.scrollWheelEventIsContinuous, value: 1)
        var scrollPhase: Int64 = 0, momentumPhase: Int64 = 0
        switch phase {
        case "b": scrollPhase = 1        // kCGScrollPhaseBegan
        case "c": scrollPhase = 2        // kCGScrollPhaseChanged
        case "e": scrollPhase = 4        // kCGScrollPhaseEnded
        case "mb": momentumPhase = 1     // kCGMomentumScrollPhaseBegin
        case "mc": momentumPhase = 2     // kCGMomentumScrollPhaseContinue
        case "me": momentumPhase = 3     // kCGMomentumScrollPhaseEnd
        default: break
        }
        e.setIntegerValueField(.scrollWheelEventScrollPhase, value: scrollPhase)
        e.setIntegerValueField(.scrollWheelEventMomentumPhase, value: momentumPhase)
        e.post(tap: .cghidEventTap)
    }

    /// Rilascia tutto (es. se il telefono si scollega a metà trascinamento).
    func releaseAll() {
        for raw in pressed { if let b = CGMouseButton(rawValue: raw) { up(b) } }
    }

    // Protocollo (una riga per comando):
    //   m dx dy | s dx dy fase | c b | d b [n] | u b      (b = l/r/m)
    func handle(_ msg: Substring) {
        let t = msg.split(separator: " ")
        guard let cmd = t.first else { return }
        // valori anomali (NaN, infinito, enormi) scartati o limitati
        func num(_ i: Int) -> Double {
            guard i < t.count, let v = Double(t[i]), v.isFinite else { return 0 }
            return min(max(v, -2000), 2000)
        }
        func btn() -> CGMouseButton { t.count > 1 ? Mouse.button(t[1]) : .left }
        switch cmd {
        case "m": move(num(1), num(2))
        case "s": scroll(num(1), num(2), phase: t.count > 3 ? t[3] : "c")
        case "c": click(btn())
        case "d": down(btn(), counted: !(t.count > 2 && t[2] == "n"))
        case "u": up(btn())
        case "i": log("   \(msg.dropFirst(2))")      // info dal telefono (modello, OLED)
        default: break
        }
    }
}

let mouse = Mouse()

// MARK: - Stato del Mac (tema + Accessibilità)

func macIsDark() -> Bool { (globalPref("AppleInterfaceStyle") as? String)?.lowercased() == "dark" }

/// Nome del Mac (Impostazioni → Generali → Condivisione), mostrato in alto sul telefono.
let computerName: String = {
    let n = (SCDynamicStoreCopyComputerName(nil, nil) as String?) ?? "Mac"
    return n.replacingOccurrences(of: "\n", with: " ")
}()

func statusLines() -> String {
    "host \(computerName)\ntheme \(macIsDark() ? "dark" : "light")\nax \(AXIsProcessTrusted() ? 1 : 0)\noled \(oledMode)\n\(chargeLine)\n"
}

// MARK: - Server TCP

var clients: [ObjectIdentifier: NWConnection] = [:]

func broadcast(_ s: String) {
    let data = Data(s.utf8)
    for c in clients.values { c.send(content: data, completion: .contentProcessed { _ in }) }
}

func receive(on c: NWConnection, buffer: Data = Data(), authed: Bool = false) {
    c.receive(minimumIncompleteLength: 1, maximumLength: 16384) { data, _, done, error in
        var buf = buffer
        var ok = authed
        if let data { buf.append(data) }
        while let nl = buf.firstIndex(of: 0x0A) {
            let line = String(data: buf[buf.startIndex..<nl], encoding: .utf8) ?? ""
            buf.removeSubrange(buf.startIndex...nl)
            if ok {
                mouse.handle(Substring(line))
            } else if line.hasPrefix("auth "), sameSecret(String(line.dropFirst(5)), token) {
                ok = true
                let id = ObjectIdentifier(c)
                clients[id] = c
                c.send(content: Data(statusLines().utf8), completion: .contentProcessed { _ in })
                log("📱 Trackpad connesso")
            } else {
                log("⛔️ Connessione rifiutata: chiave non valida")
                c.cancel(); return
            }
        }
        if buf.count > 65536 { c.cancel(); return }      // riga troppo lunga: connessione chiusa
        if error == nil && !done { receive(on: c, buffer: buf, authed: ok) } else { c.cancel() }
    }
}

func startServer() throws -> NWListener {
    let p = NWParameters.tcp
    p.requiredInterfaceType = .loopback        // niente rete: solo via cavo (adb reverse)
    p.allowLocalEndpointReuse = true
    if let tcp = p.defaultProtocolStack.transportProtocol as? NWProtocolTCP.Options { tcp.noDelay = true }
    let l = try NWListener(using: p, on: NWEndpoint.Port(rawValue: port)!)
    l.newConnectionHandler = { c in
        let id = ObjectIdentifier(c)
        c.stateUpdateHandler = { st in
            switch st {
            case .failed, .cancelled:
                if clients.removeValue(forKey: id) != nil {
                    mouse.releaseAll(); log("🔌 Trackpad disconnesso")
                }
            default: break
            }
        }
        c.start(queue: .main)
        receive(on: c)
    }
    l.start(queue: .main)
    return l
}

// MARK: - USB / adb (plug and play)

let adbPath: String? = {
    var candidates: [String] = []
    if let a = env["ADB"] { candidates.append(a) }
    candidates.append(exeDir.appendingPathComponent("platform-tools/adb").path)   // scaricato da install.sh
    if let h = env["ANDROID_HOME"] { candidates.append("\(h)/platform-tools/adb") }
    candidates.append(NSHomeDirectory() + "/Library/Android/sdk/platform-tools/adb")
    candidates.append(NSHomeDirectory() + "/Documents/PROJECTS/Memedroid/platform-tools/adb")
    return candidates.first { FileManager.default.isExecutableFile(atPath: $0) }
}()

@discardableResult
func adb(_ args: [String], serial: String? = nil) -> (ok: Bool, out: String) {
    guard let adbPath else { return (false, "") }
    let p = Process()
    p.executableURL = URL(fileURLWithPath: adbPath)
    p.arguments = (serial.map { ["-s", $0] } ?? []) + args
    let pipe = Pipe()
    p.standardOutput = pipe; p.standardError = pipe
    do { try p.run() } catch { return (false, "") }
    let data = pipe.fileHandleForReading.readDataToEndOfFile()
    p.waitUntilExit()
    return (p.terminationStatus == 0, String(data: data, encoding: .utf8) ?? "")
}

/// Installa l'app sul telefono se manca o se l'APK è stato ricompilato.
func ensureApp(_ serial: String) {
    guard let attrs = try? FileManager.default.attributesOfItem(atPath: apkPath),
          let size = attrs[.size] as? Int, let date = attrs[.modificationDate] as? Date else {
        log("⚠️  APK non trovato in \(apkPath)")
        return
    }
    let stamp = "\(size)-\(Int(date.timeIntervalSince1970))"
    let marker = exeDir.appendingPathComponent(".installed-\(serial)")
    let installed = adb(["shell", "pm", "path", appPackage], serial: serial).out.contains("package:")
    if installed, (try? String(contentsOf: marker, encoding: .utf8)) == stamp { return }
    log("📲 Installo l'app sul telefono…")
    let r = adb(["install", "-r", apkPath], serial: serial)
    if r.ok && r.out.contains("Success") {
        try? stamp.write(to: marker, atomically: true, encoding: .utf8)
        log("   installata")
    } else {
        log("   installazione non riuscita: \(r.out.trimmingCharacters(in: .whitespacesAndNewlines))")
    }
}

var usbState = ""
let usbQueue = DispatchQueue(label: "usb")

func usbTick() {
    let lines = adb(["devices"]).out.split(separator: "\n").dropFirst()
    let ready = lines.first { $0.hasSuffix("\tdevice") }.map { String($0.split(separator: "\t")[0]) }
    let unauthorized = lines.contains { $0.hasSuffix("\tunauthorized") }

    guard let serial = ready else {
        let s = unauthorized ? "unauthorized" : "none"
        if s != usbState {
            if s == "unauthorized" { log("🔐 Sul telefono accetta \"Consentire il debug USB?\"") }
            else if usbState == "device" { log("⏳ Telefono scollegato, in attesa…") }
            usbState = s
        }
        return
    }
    let linked = adb(["reverse", "--list"], serial: serial).out.contains("tcp:\(port)")
    if !linked || usbState != "device" {
        usbState = "device"
        log("🔗 Telefono collegato via USB")
        ensureApp(serial)
        adb(["reverse", "tcp:\(port)", "tcp:\(port)"], serial: serial)
        adb(["shell", "am", "start", "-n", "\(appPackage)/.MainActivity", "--es", "token", token], serial: serial)
    }
}

// MARK: - Avvio

let trusted = AXIsProcessTrustedWithOptions([kAXTrustedCheckOptionPrompt.takeUnretainedValue() as String: true] as CFDictionary)
if !trusted {
    log("⚠️  Manca il permesso Accessibilità: senza di esso il cursore NON si muove.")
    log("   Impostazioni di Sistema → Privacy e sicurezza → Accessibilità → abilita \"usbtrackpad\" (o l'app da cui lo lanci).")
}

let listener: NWListener
do {
    listener = try startServer()
    log("✅ USBTrackpad attivo su localhost:\(port) · tema Mac: \(macIsDark() ? "scuro" : "chiaro") · scorrimento naturale: \(naturalScroll() ? "sì" : "no")")
} catch {
    log("❌ Impossibile avviare il server: \(error)")
    exit(1)
}
if adbPath == nil { log("⚠️  adb non trovato: imposta ADB=/percorso/adb") }

// Cambio tema immediato (anche quello automatico alba/tramonto del Mac)…
DistributedNotificationCenter.default().addObserver(
    forName: Notification.Name("AppleInterfaceThemeChangedNotification"), object: nil, queue: .main
) { _ in
    log("🎨 Tema del Mac: \(macIsDark() ? "scuro" : "chiaro")")
    broadcast(statusLines())
}
// …e ogni 3 s lo stato completo, che fa anche da segnale di vita per l'app.
Timer.scheduledTimer(withTimeInterval: 3, repeats: true) { _ in broadcast(statusLines()) }

// Sorveglianza del cavo USB ogni 2 s.
let usbTimer = DispatchSource.makeTimerSource(queue: usbQueue)
usbTimer.schedule(deadline: .now(), repeating: 2)
usbTimer.setEventHandler { usbTick() }
usbTimer.resume()

var signalSources: [DispatchSourceSignal] = []
for sig in [SIGINT, SIGTERM] {
    signal(sig, SIG_IGN)
    let s = DispatchSource.makeSignalSource(signal: sig, queue: .main)
    s.setEventHandler { mouse.releaseAll(); log("👋 Chiuso"); exit(0) }
    s.resume()
    signalSources.append(s)
}
RunLoop.main.run()

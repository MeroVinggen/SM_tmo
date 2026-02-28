package main

import (
	"encoding/json"
	"fmt"
	"net"
	"net/http"
	"os/exec"
	"strconv"
	"sync"
	"time"

	"golang.org/x/net/websocket"
)

const (
	httpPort    = 8080
	bufSize     = 65536
	controlPort = 15558
)

type Server struct {
	mu          sync.Mutex
	status      string
	conn        net.Conn
	listener    net.Listener
	wsClients   map[*websocket.Conn]bool
	stopStream  chan struct{}
	androidPort int
	width       int
	height      int
	controlConn net.Conn
	devices     *DeviceStore
	pairCode    string
	activeID    string
	controlListening bool
}

func NewServer() *Server {
	return &Server{
		status:    "idle",
		wsClients: make(map[*websocket.Conn]bool),
		width:     1280,
		height:    720,
		devices:   NewDeviceStore(),
	}
}

func (s *Server) setStatus(st string) {
	s.mu.Lock()
	s.status = st
	s.mu.Unlock()
}

func (s *Server) getStatus() string {
	s.mu.Lock()
	defer s.mu.Unlock()
	return s.status
}

// ── Device endpoints ──────────────────────────────────────────────────────────

func (s *Server) handleDevices(w http.ResponseWriter, r *http.Request) {
	devices := s.devices.All()
	w.Header().Set("Content-Type", "application/json")
	data, _ := json.Marshal(devices)
	w.Write(data)
}

func (s *Server) handleDeviceRename(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost {
		http.Error(w, "method not allowed", 405)
		return
	}
	id := r.URL.Query().Get("id")
	name := r.URL.Query().Get("name")
	s.devices.Rename(id, name)
	w.Header().Set("Content-Type", "application/json")
	w.Write([]byte(`{"ok":true}`))
}

func (s *Server) handleDeviceRemove(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost {
		http.Error(w, "method not allowed", 405)
		return
	}
	id := r.URL.Query().Get("id")
	s.devices.Remove(id)
	w.Header().Set("Content-Type", "application/json")
	w.Write([]byte(`{"ok":true}`))
}

// ── Pairing endpoints ─────────────────────────────────────────────────────────

func (s *Server) handlePairCode(w http.ResponseWriter, r *http.Request) {
	s.mu.Lock()
	s.pairCode = generatePairCode()
	code := s.pairCode
	s.mu.Unlock()

	pcName := getPCName()
	w.Header().Set("Content-Type", "application/json")
	fmt.Fprintf(w, `{"code":"%s","pcName":"%s"}`, code, pcName)
}

func (s *Server) handlePairConfirm(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost {
		http.Error(w, "method not allowed", 405)
		return
	}
	code := r.URL.Query().Get("code")
	name := r.URL.Query().Get("name")

	s.mu.Lock()
	valid := s.pairCode != "" && s.pairCode == code
	if valid {
		s.pairCode = ""
	}
	s.mu.Unlock()

	if !valid {
		w.Header().Set("Content-Type", "application/json")
		w.Write([]byte(`{"ok":false,"msg":"invalid code"}`))
		return
	}

	serials := getConnectedSerials()
	serial := ""
	if len(serials) > 0 {
		serial = serials[0]
	}
	if name == "" {
		name = "Android Device"
	}

	device := s.devices.Add(name, serial)
	w.Header().Set("Content-Type", "application/json")
	fmt.Fprintf(w, `{"ok":true,"id":"%s","name":"%s"}`, device.ID, device.Name)
}

// ── Stream endpoints ──────────────────────────────────────────────────────────

func (s *Server) handleStartStream(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost {
		http.Error(w, "method not allowed", 405)
		return
	}
	id := r.URL.Query().Get("id")
	dev, ok := s.devices.Get(id)
	if !ok {
		w.Header().Set("Content-Type", "application/json")
		w.Write([]byte(`{"ok":false,"msg":"device not found"}`))
		return
	}
	if !dev.Online {
		w.Header().Set("Content-Type", "application/json")
		w.Write([]byte(`{"ok":false,"msg":"device offline"}`))
		return
	}
	if s.getStatus() == "streaming" {
    // already streaming, just let WS clients reconnect
    w.Header().Set("Content-Type", "application/json")
    w.Write([]byte(`{"ok":true}`))
    return
	}
	if s.getStatus() == "connected" {
			w.Header().Set("Content-Type", "application/json")
			w.Write([]byte(`{"ok":false,"msg":"already connecting"}`))
			return
	}

	res := r.URL.Query().Get("res")
	switch res {
	case "1080":
		s.width, s.height = 1920, 1080
	case "480":
		s.width, s.height = 854, 480
	default:
		s.width, s.height = 1280, 720
	}

	s.activeID = id
	go s.startCapture(dev.Serial)
	w.Header().Set("Content-Type", "application/json")
	w.Write([]byte(`{"ok":true}`))
}

func (s *Server) handleStopStream(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost {
		http.Error(w, "method not allowed", 405)
		return
	}
	s.stopCapture()
	w.Header().Set("Content-Type", "application/json")
	w.Write([]byte(`{"ok":true}`))
}

func (s *Server) handleStatus(w http.ResponseWriter, r *http.Request) {
	w.Header().Set("Content-Type", "application/json")
	fmt.Fprintf(w, `{"status":"%s","width":%d,"height":%d,"activeId":"%s"}`,
		s.getStatus(), s.width, s.height, s.activeID)
}

func (s *Server) handleSetRes(w http.ResponseWriter, r *http.Request) {
	res := r.URL.Query().Get("res")
	switch res {
	case "1080":
		s.width, s.height = 1920, 1080
	case "480":
		s.width, s.height = 854, 480
	default:
		s.width, s.height = 1280, 720
	}
	s.sendResolution()
	w.Header().Set("Content-Type", "application/json")
	w.Write([]byte(`{"ok":true}`))
}

// ── WS ────────────────────────────────────────────────────────────────────────

func (s *Server) handleWS(ws *websocket.Conn) {
	s.mu.Lock()
	s.wsClients[ws] = true
	s.mu.Unlock()

	buf := make([]byte, 1)
	for {
		if _, err := ws.Read(buf); err != nil {
			break
		}
	}

	s.mu.Lock()
	delete(s.wsClients, ws)
	s.mu.Unlock()
	ws.Close()
}

func (s *Server) broadcastToWS(data []byte) {
	s.mu.Lock()
	defer s.mu.Unlock()
	for ws := range s.wsClients {
		if err := websocket.Message.Send(ws, data); err != nil {
			delete(s.wsClients, ws)
			ws.Close()
		}
	}
}

// ── Capture ───────────────────────────────────────────────────────────────────

func (s *Server) startCapture(serial string) {
	fmt.Println("startCapture called, listener nil:", s.listener == nil, "status:", s.getStatus())
	if s.listener != nil {
    if s.getStatus() != "idle" {
        return
    }
    s.setStatus("connected")
    s.stopStream = make(chan struct{})
		go s.runControlListener()
    go s.reAccept(serial)
    return
	}
	videoL, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		fmt.Println("Video listen failed:", err)
		return
	}
	s.androidPort = videoL.Addr().(*net.TCPAddr).Port
	s.listener = videoL

	if err := adbReverse(serial, 15557, s.androidPort); err != nil {
		fmt.Println("ADB reverse video failed:", err)
		return
	}
	if err := adbReverse(serial, controlPort, controlPort); err != nil {
		fmt.Println("ADB reverse control failed:", err)
		return
	}
	fmt.Println("ADB tunnels active")

	s.setStatus("connected")
	s.stopStream = make(chan struct{})

	go s.runControlListener()

	fmt.Println("Waiting for Android video connection...")
	conn, err := videoL.Accept()
	if err != nil {
		fmt.Println("Video accept failed:", err)
		s.setStatus("idle")
		return
	}
	s.conn = conn
	s.setStatus("streaming")
	fmt.Println("Streaming started")

	buf := make([]byte, bufSize)
	for {
		n, err := conn.Read(buf)
		if err != nil {
			fmt.Println("TCP read error:", err)
			s.setStatus("idle")
			select {
			case <-s.stopStream:
					fmt.Println("stopStream caught, not restarting")
			default:
					fmt.Println("stopStream NOT caught, restarting")
					go s.startCapture(serial)
			}
			return
		}
		chunk := make([]byte, n)
		copy(chunk, buf[:n])
		s.broadcastToWS(chunk)
	}
}

func (s *Server) runControlListener() {
		s.mu.Lock()
    if s.controlListening {
        s.mu.Unlock()
        fmt.Println("runControlListener already running, skipping")
        return
    }
    s.controlListening = true
    s.mu.Unlock()
    defer func() {
        s.mu.Lock()
        s.controlListening = false
        s.mu.Unlock()
    }()
    for {
				fmt.Println("runControlListener iteration start")
        l, err := net.Listen("tcp", fmt.Sprintf("127.0.0.1:%d", controlPort))
        if err != nil {
            fmt.Println("Control listen failed:", err)
            time.Sleep(500 * time.Millisecond)
            continue
        }
        conn, err := l.Accept()
        l.Close()
        if err != nil {
            continue
        }
        s.controlConn = conn
        fmt.Println("Control channel established")
        s.sendResolution()

        // wait until disconnected then re-listen
        buf := make([]byte, 1)
				conn.Read(buf)
				s.controlConn = nil
				fmt.Println("Control channel closed")
				// just return, no re-loop
				return
    }
}

func (s *Server) stopCapture() {
    fmt.Println("stopCapture called, status:", s.getStatus())
    if s.controlConn != nil {
        fmt.Println("closing controlConn")
        s.controlConn.Close()
        s.controlConn = nil
    }
    if s.stopStream != nil {
        fmt.Println("closing stopStream channel")
        close(s.stopStream)
    }
    if s.conn != nil {
        fmt.Println("closing video conn")
        s.conn.Close()
        s.conn = nil
    }
    s.activeID = ""
    s.setStatus("idle")
    fmt.Println("stopCapture done")
}

func (s *Server) sendResolution() {
	if s.controlConn == nil {
		return
	}
	config := fmt.Sprintf("{\"width\":%d,\"height\":%d}\n", s.width, s.height)
	fmt.Printf("Sending resolution: %dx%d\n", s.width, s.height)
	s.controlConn.Write([]byte(config))
}

// ── Polling ───────────────────────────────────────────────────────────────────

func (s *Server) pollDevices() {
	for {
		s.devices.PollOnline()
		time.Sleep(3 * time.Second)
	}
}

// ── Helpers ───────────────────────────────────────────────────────────────────

func adbReverse(serial string, remotePort, localPort int) error {
	args := []string{}
	if serial != "" {
		args = append(args, "-s", serial)
	}
	args = append(args, "reverse",
		"tcp:"+strconv.Itoa(remotePort),
		"tcp:"+strconv.Itoa(localPort),
	)
	return exec.Command("adb", args...).Run()
}

// ── Start ─────────────────────────────────────────────────────────────────────

func (s *Server) Start() {
	go s.pollDevices()

	http.HandleFunc("/api/pair/ready", s.handlePairReady)
	http.HandleFunc("/api/devices", s.handleDevices)
	http.HandleFunc("/api/devices/rename", s.handleDeviceRename)
	http.HandleFunc("/api/devices/remove", s.handleDeviceRemove)
	http.HandleFunc("/api/pair/code", s.handlePairCode)
	http.HandleFunc("/api/pair/confirm", s.handlePairConfirm)
	http.HandleFunc("/api/stream/start", s.handleStartStream)
	http.HandleFunc("/api/stream/stop", s.handleStopStream)
	http.HandleFunc("/api/setres", s.handleSetRes)
	http.HandleFunc("/api/status", s.handleStatus)
	http.Handle("/ws", websocket.Handler(s.handleWS))
	http.Handle("/", http.FileServer(http.Dir("web")))

	fmt.Printf("HTTP server on http://127.0.0.1:%d\n", httpPort)
	http.ListenAndServe(fmt.Sprintf("127.0.0.1:%d", httpPort), nil)
}

func (s *Server) handlePairReady(w http.ResponseWriter, r *http.Request) {
    // Reverse HTTP port for all connected devices
    for _, serial := range getConnectedSerials() {
        adbReverse(serial, httpPort, httpPort)
    }
    w.Header().Set("Content-Type", "application/json")
    w.Write([]byte(`{"ok":true}`))
}

func (s *Server) reAccept(serial string) {
		fmt.Println("reAccept called, status:", s.getStatus())
    // re-setup adb reverse in case it dropped
    adbReverse(serial, 15557, s.androidPort)
    adbReverse(serial, controlPort, controlPort)
    adbReverse(serial, httpPort, httpPort)

    fmt.Println("Re-waiting for Android video connection...")
    conn, err := s.listener.Accept()
		fmt.Println("reAccept Accept returned, err:", err)
    if err != nil {
        fmt.Println("Re-accept failed:", err)
        s.setStatus("idle")
        return
    }
    s.conn = conn
    s.setStatus("streaming")
    fmt.Println("Streaming re-established")

    buf := make([]byte, bufSize)
    for {
        n, err := conn.Read(buf)
        if err != nil {
            fmt.Println("TCP read error:", err)
            s.setStatus("idle")
            select {
						case <-s.stopStream:
								fmt.Println("stopStream caught, not restarting")
						default:
								fmt.Println("stopStream NOT caught, restarting")
								go s.reAccept(serial)
						}
            return
        }
        chunk := make([]byte, n)
        copy(chunk, buf[:n])
        s.broadcastToWS(chunk)
    }
}

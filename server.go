package main

import (
	"fmt"
	"net"
	"net/http"
	"os/exec"
	"sync"

	"golang.org/x/net/websocket"
)

const (
	httpPort    = 8080
	bufSize     = 65536
)

type Server struct {
	mu         sync.Mutex
	status     string
	conn       net.Conn
	listener   net.Listener
	wsClients  map[*websocket.Conn]bool
	stopStream chan struct{}
	androidPort int
	width      int
	height     int
}

func NewServer() *Server {
	return &Server{
		status:    "idle",
		wsClients: make(map[*websocket.Conn]bool),
		width:     1280,
		height:    720,
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

func (s *Server) handleConnect(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost {
		http.Error(w, "method not allowed", http.StatusMethodNotAllowed)
		return
	}
	if s.getStatus() != "idle" {
		w.Header().Set("Content-Type", "application/json")
		w.Write([]byte(`{"ok":false,"msg":"already connected"}`))
		return
	}
	res := r.URL.Query().Get("res")
	switch res {
	case "1080":
		s.width, s.height = 1920, 1080
	default:
		s.width, s.height = 1280, 720
	}
	go s.startCapture()
	w.Header().Set("Content-Type", "application/json")
	w.Write([]byte(`{"ok":true}`))
}

func (s *Server) handleDisconnect(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost {
		http.Error(w, "method not allowed", http.StatusMethodNotAllowed)
		return
	}
	s.stopCapture()
	w.Header().Set("Content-Type", "application/json")
	w.Write([]byte(`{"ok":true}`))
}

func (s *Server) handleStatus(w http.ResponseWriter, r *http.Request) {
	w.Header().Set("Content-Type", "application/json")
	fmt.Fprintf(w, `{"status":"%s","width":%d,"height":%d}`, s.getStatus(), s.width, s.height)
}

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

func (s *Server) startCapture() {
	listener, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
			fmt.Println("Listen failed:", err)
			return
	}
	s.androidPort = listener.Addr().(*net.TCPAddr).Port
	cmd := exec.Command("adb", "reverse",
			"tcp:15557",
			fmt.Sprintf("tcp:%d", s.androidPort),
	)
	if err := cmd.Run(); err != nil {
			fmt.Println("ADB reverse failed:", err)
			return
	}
	fmt.Println("ADB reverse tunnel active")
	if err != nil {
		fmt.Println("Listen failed:", err)
		return
	}
	s.listener = listener
	s.setStatus("connected")
	s.stopStream = make(chan struct{})

	fmt.Println("Waiting for Android...")
	conn, err := listener.Accept()
	if err != nil {
		fmt.Println("Accept failed:", err)
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
				// manually stopped, don't restart
			default:
				go s.startCapture()
			}
			return
		}
		chunk := make([]byte, n)
		copy(chunk, buf[:n])
		s.broadcastToWS(chunk)
	}
}

func (s *Server) stopCapture() {
	if s.stopStream != nil {
		close(s.stopStream)
		s.stopStream = nil
	}
	if s.conn != nil {
		s.conn.Close()
		s.conn = nil
	}
	if s.listener != nil {
		s.listener.Close()
		s.listener = nil
	}
	s.setStatus("idle")
	fmt.Println("Capture stopped")
}

func (s *Server) Start() {
	http.HandleFunc("/api/connect", s.handleConnect)
	http.HandleFunc("/api/disconnect", s.handleDisconnect)
	http.HandleFunc("/api/status", s.handleStatus)
	http.Handle("/ws", websocket.Handler(s.handleWS))
	http.Handle("/", http.FileServer(http.Dir("web")))

	fmt.Printf("HTTP server on http://127.0.0.1:%d\n", httpPort)
	http.ListenAndServe(fmt.Sprintf("127.0.0.1:%d", httpPort), nil)
}

package main

import (
	"fmt"
	"time"

	webview "github.com/jchv/go-webview2"
)

func main() {
	srv := NewServer()
	go srv.Start()

	time.Sleep(300 * time.Millisecond)

	fmt.Println("Launching window...")
	w := webview.New(true)
	defer w.Destroy()

	w.SetTitle("Screen Mirror")
	w.SetSize(1300, 800, webview.HintNone)
	w.Navigate("http://127.0.0.1:8080")
	w.Run()
}

package main

import (
	"fmt"
	"io"
	"net"
	"os"
	"os/exec"
)

const (
	port       = 15557
	bufferSize = 65536
)

func forwardADB() error {
	cmd := exec.Command("adb", "reverse", fmt.Sprintf("tcp:%d", port), fmt.Sprintf("tcp:%d", port))
	cmd.Stdout = os.Stdout
	cmd.Stderr = os.Stderr
	return cmd.Run()
}

func main() {
	fmt.Println("Setting up ADB reverse tunnel...")
	if err := forwardADB(); err != nil {
		fmt.Println("ADB reverse failed:", err)
		os.Exit(1)
	}
	fmt.Printf("ADB reverse set up on port %d\n", port)

	listener, err := net.Listen("tcp", fmt.Sprintf("127.0.0.1:%d", port))
	if err != nil {
		fmt.Println("Failed to listen:", err)
		os.Exit(1)
	}
	defer listener.Close()

	fmt.Println("Waiting for Android to connect...")
	conn, err := listener.Accept()
	if err != nil {
		fmt.Println("Accept failed:", err)
		os.Exit(1)
	}
	defer conn.Close()
	fmt.Println("Connected:", conn.RemoteAddr())

	ffplay := exec.Command(
		"ffplay",
		"-fflags", "nobuffer+discardcorrupt",
		"-flags", "low_delay",
		"-framedrop",
		"-vf", "setpts=0",
		"-i", "pipe:0",
		"-window_title", "Screen Mirror",
	)
	ffplay.Stdout = os.Stdout
	ffplay.Stderr = os.Stderr

	stdin, err := ffplay.StdinPipe()
	if err != nil {
		fmt.Println("Failed to get ffplay stdin:", err)
		os.Exit(1)
	}

	if err := ffplay.Start(); err != nil {
		fmt.Println("Failed to start ffplay:", err)
		os.Exit(1)
	}

	fmt.Println("Streaming to ffplay...")
	buf := make([]byte, bufferSize)
	for {
		n, err := conn.Read(buf)
		if err != nil {
			if err != io.EOF {
				fmt.Println("Read error:", err)
			}
			break
		}
		if _, err := stdin.Write(buf[:n]); err != nil {
			fmt.Println("Write to ffplay failed:", err)
			break
		}
	}

	stdin.Close()
	ffplay.Wait()
	fmt.Println("Stream ended.")
}

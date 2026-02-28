package main

import (
	"crypto/rand"
	"fmt"
	"math/big"
	"os/exec"
	"strings"
)

// PairCode holds the pairing info shown to user
type PairCode struct {
	Code   string `json:"code"`   // 6-digit numeric code
	PCID   string `json:"pcId"`   // unique PC identifier
	PCName string `json:"pcName"` // PC hostname
}

func generatePairCode() string {
	n, _ := rand.Int(rand.Reader, big.NewInt(900000))
	return fmt.Sprintf("%06d", n.Int64()+100000)
}

func getPCName() string {
	out, err := exec.Command("hostname").Output()
	if err != nil {
		return "PC"
	}
	return strings.TrimSpace(string(out))
}

func getConnectedSerials() []string {
	out, err := exec.Command("adb", "devices").Output()
	if err != nil {
		return nil
	}
	var serials []string
	lines := strings.Split(string(out), "\n")
	for _, line := range lines[1:] {
		parts := strings.Fields(line)
		if len(parts) >= 2 && parts[1] == "device" {
			serials = append(serials, parts[0])
		}
	}
	return serials
}

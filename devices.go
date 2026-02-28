package main

import (
	"crypto/rand"
	"encoding/json"
	"fmt"
	"os"
	"os/exec"
	"strings"
	"sync"
)

type Device struct {
	ID     string `json:"id"`
	Name   string `json:"name"`
	Serial string `json:"serial"` // ADB serial
	Online bool   `json:"online"`
}

type DeviceStore struct {
	mu      sync.Mutex
	devices []Device
	path    string
}

func NewDeviceStore() *DeviceStore {
	ds := &DeviceStore{path: "devices.json"}
	ds.load()
	return ds
}

func (ds *DeviceStore) load() {
	data, err := os.ReadFile(ds.path)
	if err != nil {
		return
	}
	json.Unmarshal(data, &ds.devices)
}

func (ds *DeviceStore) save() {
	data, _ := json.MarshalIndent(ds.devices, "", "  ")
	os.WriteFile(ds.path, data, 0644)
}

func (ds *DeviceStore) Add(name, serial string) Device {
	ds.mu.Lock()
	defer ds.mu.Unlock()
	d := Device{
		ID:     generateID(),
		Name:   name,
		Serial: serial,
	}
	ds.devices = append(ds.devices, d)
	ds.save()
	return d
}

func (ds *DeviceStore) Remove(id string) {
	ds.mu.Lock()
	defer ds.mu.Unlock()
	for i, d := range ds.devices {
		if d.ID == id {
			ds.devices = append(ds.devices[:i], ds.devices[i+1:]...)
			break
		}
	}
	ds.save()
}

func (ds *DeviceStore) Rename(id, name string) {
	ds.mu.Lock()
	defer ds.mu.Unlock()
	for i, d := range ds.devices {
		if d.ID == id {
			ds.devices[i].Name = name
			break
		}
	}
	ds.save()
}

func (ds *DeviceStore) Get(id string) (Device, bool) {
	ds.mu.Lock()
	defer ds.mu.Unlock()
	for _, d := range ds.devices {
		if d.ID == id {
			return d, true
		}
	}
	return Device{}, false
}

func (ds *DeviceStore) All() []Device {
	ds.mu.Lock()
	defer ds.mu.Unlock()
	result := make([]Device, len(ds.devices))
	copy(result, ds.devices)
	return result
}

func (ds *DeviceStore) SetOnline(id string, online bool) {
	ds.mu.Lock()
	defer ds.mu.Unlock()
	for i, d := range ds.devices {
		if d.ID == id {
			ds.devices[i].Online = online
			return
		}
	}
}

// PollOnline checks ADB device list and updates online status for all paired devices
func (ds *DeviceStore) PollOnline() {
	out, err := exec.Command("adb", "devices").Output()
	if err != nil {
		return
	}
	lines := strings.Split(string(out), "\n")
	online := map[string]bool{}
	for _, line := range lines[1:] {
		parts := strings.Fields(line)
		if len(parts) >= 2 && parts[1] == "device" {
			online[parts[0]] = true
		}
	}
	ds.mu.Lock()
	defer ds.mu.Unlock()
	for i, d := range ds.devices {
		ds.devices[i].Online = online[d.Serial]
	}
}

func generateID() string {
	b := make([]byte, 4)
	rand.Read(b)
	return fmt.Sprintf("%x", b)
}

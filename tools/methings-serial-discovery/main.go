package main

import (
	"bytes"
	"encoding/json"
	"fmt"
	"net/http"
	"os"
	"sort"
	"strconv"
	"strings"
	"sync"
	"time"

	properties "github.com/arduino/go-properties-orderedmap"
	discovery "github.com/arduino/pluggable-discovery-protocol-handler/v2"
)

const (
	localAPI        = "http://127.0.0.1:33389"
	identityHeader  = "x-methings-identity"
	identityValue   = "arduino-cli"
	pollInterval    = 2 * time.Second
	permissionDelay = 1200 * time.Millisecond
)

type usbListResponse struct {
	Devices []struct {
		Name        string `json:"name"`
		VendorID    int    `json:"vendor_id"`
		ProductID   int    `json:"product_id"`
		Serial      string `json:"serial_number"`
		ProductName string `json:"product_name"`
	} `json:"devices"`
}

type permissionResponse struct {
	ID     string `json:"id"`
	Status string `json:"status"`
}

type usbOpenResponse struct {
	Handle string `json:"handle"`
}

type serialListPortsResponse struct {
	Ports []struct {
		PortIndex int `json:"port_index"`
	} `json:"ports"`
}

type appDiscovery struct {
	mu        sync.Mutex
	stop      chan struct{}
	running   bool
	prevPorts map[string]*discovery.Port
}

func main() {
	impl := &appDiscovery{}
	srv := discovery.NewServer(impl)
	if err := srv.Run(os.Stdin, os.Stdout); err != nil {
		fmt.Fprintf(os.Stderr, "Error: %v\n", err)
		os.Exit(1)
	}
}

func (d *appDiscovery) Hello(_ string, _ int) error { return nil }

func (d *appDiscovery) Quit() {
	_ = d.Stop()
}

func (d *appDiscovery) Stop() error {
	d.mu.Lock()
	defer d.mu.Unlock()
	if !d.running {
		return nil
	}
	close(d.stop)
	d.running = false
	return nil
}

func (d *appDiscovery) StartSync(eventCB discovery.EventCallback, errorCB discovery.ErrorCallback) error {
	d.mu.Lock()
	if d.running {
		d.mu.Unlock()
		return fmt.Errorf("already running")
	}
	d.stop = make(chan struct{})
	d.running = true
	d.prevPorts = map[string]*discovery.Port{}
	stop := d.stop
	d.mu.Unlock()

	initial, err := fetchPorts()
	if err != nil {
		return err
	}
	for _, p := range initial {
		eventCB("add", p)
		d.prevPorts[p.Address+"|"+p.Protocol] = p
	}

	go func() {
		t := time.NewTicker(pollInterval)
		defer t.Stop()
		for {
			select {
			case <-stop:
				return
			case <-t.C:
				cur, err := fetchPorts()
				if err != nil {
					errorCB(err.Error())
					continue
				}
				d.syncDiff(cur, eventCB)
			}
		}
	}()
	return nil
}

func (d *appDiscovery) syncDiff(cur []*discovery.Port, eventCB discovery.EventCallback) {
	d.mu.Lock()
	defer d.mu.Unlock()
	if !d.running {
		return
	}
	curr := map[string]*discovery.Port{}
	for _, p := range cur {
		curr[p.Address+"|"+p.Protocol] = p
	}
	for id, p := range curr {
		if _, ok := d.prevPorts[id]; !ok {
			eventCB("add", p)
		}
	}
	for id, p := range d.prevPorts {
		if _, ok := curr[id]; !ok {
			eventCB("remove", &discovery.Port{Address: p.Address, Protocol: p.Protocol})
		}
	}
	d.prevPorts = curr
}

func fetchPorts() ([]*discovery.Port, error) {
	client := &http.Client{Timeout: 5 * time.Second}
	list, code, err := usbList(client)
	if err != nil {
		return nil, err
	}
	if code == http.StatusForbidden {
		if err := requestUSBPermission(client); err != nil {
			return nil, err
		}
		list, code, err = usbList(client)
		if err != nil {
			return nil, err
		}
	}
	if code != http.StatusOK || list == nil {
		return []*discovery.Port{}, nil
	}

	ports := []*discovery.Port{}
	for _, dev := range list.Devices {
		name := strings.TrimSpace(dev.Name)
		if name == "" {
			continue
		}
		indexes, err := serialPortIndexes(client, name, dev.VendorID, dev.ProductID)
		if err != nil {
			return nil, err
		}
		if len(indexes) == 0 {
			indexes = []int{0}
		}
		for _, idx := range indexes {
			props := properties.NewMap()
			props.Set("vid", fmt.Sprintf("0x%04x", dev.VendorID&0xffff))
			props.Set("pid", fmt.Sprintf("0x%04x", dev.ProductID&0xffff))
			if strings.TrimSpace(dev.Serial) != "" {
				props.Set("serialNumber", strings.TrimSpace(dev.Serial))
			}
			label := name
			if strings.TrimSpace(dev.ProductName) != "" {
				label = strings.TrimSpace(dev.ProductName)
			}
			ports = append(ports, &discovery.Port{
				Address:       fmt.Sprintf("android-usb:%s:port%d", name, idx),
				AddressLabel:  label,
				Protocol:      "serial",
				ProtocolLabel: "Serial (Android USB)",
				Properties:    props,
				HardwareID:    strings.TrimSpace(dev.Serial),
			})
		}
	}
	sort.Slice(ports, func(i, j int) bool { return ports[i].Address < ports[j].Address })
	return ports, nil
}

func applyIdentity(req *http.Request) {
	req.Header.Set(identityHeader, identityValue)
}

func usbList(client *http.Client) (*usbListResponse, int, error) {
	req, err := http.NewRequest(http.MethodGet, localAPI+"/usb/list", nil)
	if err != nil {
		return nil, 0, err
	}
	applyIdentity(req)
	resp, err := client.Do(req)
	if err != nil {
		return nil, 0, err
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		return nil, resp.StatusCode, nil
	}
	var out usbListResponse
	if err := json.NewDecoder(resp.Body).Decode(&out); err != nil {
		return nil, 0, err
	}
	return &out, http.StatusOK, nil
}

func requestUSBPermission(client *http.Client) error {
	body := []byte(`{"tool":"device.usb","capability":"usb","detail":"Arduino serial discovery","scope":"session","identity":"arduino-cli"}`)
	req, err := http.NewRequest(http.MethodPost, localAPI+"/permissions/request", bytes.NewReader(body))
	if err != nil {
		return err
	}
	req.Header.Set("Content-Type", "application/json")
	applyIdentity(req)
	resp, err := client.Do(req)
	if err != nil {
		return err
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		return fmt.Errorf("permissions request failed: %s", resp.Status)
	}
	var out permissionResponse
	if err := json.NewDecoder(resp.Body).Decode(&out); err != nil {
		return err
	}
	if strings.EqualFold(out.Status, "approved") {
		return nil
	}
	if strings.TrimSpace(out.ID) == "" {
		return fmt.Errorf("permission id missing")
	}
	deadline := time.Time{}
	if wait := readWaitSeconds(); wait > 0 {
		deadline = time.Now().Add(time.Duration(wait) * time.Second)
	}
	for {
		if !deadline.IsZero() && time.Now().After(deadline) {
			return fmt.Errorf("usb permission wait timed out")
		}
		time.Sleep(permissionDelay)
		statusReq, err := http.NewRequest(http.MethodGet, localAPI+"/permissions/"+out.ID, nil)
		if err != nil {
			return err
		}
		applyIdentity(statusReq)
		statusResp, err := client.Do(statusReq)
		if err != nil {
			return err
		}
		if statusResp.StatusCode != http.StatusOK {
			statusResp.Body.Close()
			return fmt.Errorf("permissions status failed: %s", statusResp.Status)
		}
		var cur permissionResponse
		err = json.NewDecoder(statusResp.Body).Decode(&cur)
		statusResp.Body.Close()
		if err != nil {
			return err
		}
		s := strings.ToLower(strings.TrimSpace(cur.Status))
		if s == "approved" {
			return nil
		}
		if s == "denied" {
			return fmt.Errorf("usb permission denied")
		}
	}
}

func readWaitSeconds() int {
	raw := strings.TrimSpace(os.Getenv("METHINGS_ARDUINO_USB_PERMISSION_WAIT_SECONDS"))
	if raw == "" {
		return 0
	}
	n, err := strconv.Atoi(raw)
	if err != nil || n < 0 {
		return 0
	}
	return n
}

func serialPortIndexes(client *http.Client, name string, vendorID, productID int) ([]int, error) {
	openBody := []byte(fmt.Sprintf(
		`{"name":%q,"vendor_id":%d,"product_id":%d,"permission_timeout_ms":0,"identity":"arduino-cli"}`,
		name, vendorID, productID,
	))
	openReq, err := http.NewRequest(http.MethodPost, localAPI+"/usb/open", bytes.NewReader(openBody))
	if err != nil {
		return nil, err
	}
	openReq.Header.Set("Content-Type", "application/json")
	applyIdentity(openReq)
	openResp, err := client.Do(openReq)
	if err != nil {
		return nil, err
	}
	defer openResp.Body.Close()
	if openResp.StatusCode != http.StatusOK {
		return nil, nil
	}
	var opened usbOpenResponse
	if err := json.NewDecoder(openResp.Body).Decode(&opened); err != nil {
		return nil, err
	}
	h := strings.TrimSpace(opened.Handle)
	if h == "" {
		return nil, nil
	}
	defer func() {
		closeBody := []byte(fmt.Sprintf(`{"handle":%q}`, h))
		closeReq, err := http.NewRequest(http.MethodPost, localAPI+"/usb/close", bytes.NewReader(closeBody))
		if err != nil {
			return
		}
		closeReq.Header.Set("Content-Type", "application/json")
		applyIdentity(closeReq)
		closeResp, err := client.Do(closeReq)
		if err == nil && closeResp != nil {
			closeResp.Body.Close()
		}
	}()

	listBody := []byte(fmt.Sprintf(`{"handle":%q,"identity":"arduino-cli"}`, h))
	listReq, err := http.NewRequest(http.MethodPost, localAPI+"/serial/list_ports", bytes.NewReader(listBody))
	if err != nil {
		return nil, err
	}
	listReq.Header.Set("Content-Type", "application/json")
	applyIdentity(listReq)
	listResp, err := client.Do(listReq)
	if err != nil {
		return nil, err
	}
	defer listResp.Body.Close()
	if listResp.StatusCode != http.StatusOK {
		return nil, nil
	}
	var list serialListPortsResponse
	if err := json.NewDecoder(listResp.Body).Decode(&list); err != nil {
		return nil, err
	}
	res := make([]int, 0, len(list.Ports))
	for _, p := range list.Ports {
		res = append(res, p.PortIndex)
	}
	return res, nil
}

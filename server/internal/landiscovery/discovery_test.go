package landiscovery

import (
	"log/slog"
	"net"
	"strings"
	"testing"
	"time"
)

func request(nonce string) []byte {
	return []byte(requestPrefix + nonce + strings.Repeat(" ", packetSize-len(requestPrefix)-len(nonce)))
}

func TestResponse(t *testing.T) {
	nonce := strings.Repeat("a", nonceLength)
	valid := request(nonce)
	for _, tc := range []struct {
		name   string
		packet []byte
		want   bool
	}{
		{"valid", valid, true},
		{"short", valid[:len(valid)-1], false},
		{"oversize", append(append([]byte{}, valid...), ' '), false},
		{"wrong service", []byte(strings.Repeat("x", packetSize)), false},
		{"invalid nonce", request(strings.Repeat("z", nonceLength)), false},
		{"trailing data", append(append([]byte{}, valid[:len(valid)-1]...), 'x'), false},
	} {
		t.Run(tc.name, func(t *testing.T) {
			reply := response(tc.packet, 18080)
			if (reply != nil) != tc.want {
				t.Fatalf("unexpected reply %q", reply)
			}
			if tc.want && string(reply) != responsePrefix+nonce+":18080" {
				t.Fatalf("nonce/HTTP port not preserved: %q", reply)
			}
			if len(reply) > len(tc.packet) {
				t.Fatal("discovery must not amplify UDP packets")
			}
		})
	}
}

func TestServeAndClose(t *testing.T) {
	conn, err := net.ListenUDP("udp4", &net.UDPAddr{IP: net.IPv4(127, 0, 0, 1)})
	if err != nil {
		t.Fatal(err)
	}
	done := make(chan error, 1)
	go func() { done <- serve(conn, 18080, slog.Default()) }()
	t.Cleanup(func() {
		if err := conn.Close(); err != nil {
			t.Error(err)
		}
		select {
		case err := <-done:
			if err != nil {
				t.Error(err)
			}
		case <-time.After(time.Second):
			t.Error("discovery did not release listener on close")
		}
	})
	client, err := net.DialUDP("udp4", nil, conn.LocalAddr().(*net.UDPAddr))
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() {
		if err := client.Close(); err != nil {
			t.Error(err)
		}
	})
	if err := client.SetDeadline(time.Now().Add(time.Second)); err != nil {
		t.Fatal(err)
	}
	nonce := strings.Repeat("b", nonceLength)
	if _, err := client.Write(request(nonce)); err != nil {
		t.Fatal(err)
	}
	buffer := make([]byte, packetSize)
	n, err := client.Read(buffer)
	if err != nil {
		t.Fatal(err)
	}
	if string(buffer[:n]) != responsePrefix+nonce+":18080" {
		t.Fatalf("unexpected response: %q", buffer[:n])
	}
}

func TestLoopbackAndSpecificHTTPListenersDoNotAdvertise(t *testing.T) {
	for _, address := range []string{"127.0.0.1:18430", "192.0.2.8:8420", "[::1]:8420", "[::]:8420"} {
		closeDiscovery, err := Start(address, nil)
		if err != nil {
			t.Fatal(err)
		}
		closeDiscovery()
	}
}

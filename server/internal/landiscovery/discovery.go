// Package landiscovery 提供无凭据的 IPv4 局域网地址发现，不参与认证或业务数据访问。
// 线格式与 Android LanDiscoveryProtocol 对应，契约见 server/README.md「局域网发现」。
package landiscovery

import (
	"encoding/hex"
	"errors"
	"fmt"
	"log/slog"
	"net"
	"strconv"
	"strings"
	"time"
)

const (
	Port                  = 18421
	packetSize            = 96
	requestPrefix         = "QIMENG_DISCOVER_V1:"
	responsePrefix        = "QIMENG_SERVER_V1:"
	nonceLength           = 32
	maxResponsesPerSecond = 20
	writeTimeout          = time.Second
)

// Start 仅为通配 IPv4 HTTP 监听开放发现；回环内嵌、指定网卡及 IPv6-only 不开放。
// UDP 回包源地址由系统按当前路由选择，不缓存启动时的网卡/IP。
// 发现失败仅影响地址填写，原 HTTP 登录继续可用。
func Start(httpAddress string, logger *slog.Logger) (func(), error) {
	host, portText, err := net.SplitHostPort(httpAddress)
	if err != nil {
		return nil, fmt.Errorf("解析 HTTP 监听地址: %w", err)
	}
	if host != "" && host != "0.0.0.0" {
		return func() {}, nil
	}
	port, err := strconv.Atoi(portText)
	if err != nil || port < 1 || port > 65535 {
		return nil, fmt.Errorf("发现不支持 HTTP 端口 %q", portText)
	}
	conn, err := net.ListenUDP("udp4", &net.UDPAddr{Port: Port})
	if err != nil {
		return nil, fmt.Errorf("监听局域网发现: %w", err)
	}
	done := make(chan struct{})
	go func() {
		defer close(done)
		if err := serve(conn, port, logger); err != nil {
			logger.Warn("局域网发现已停止，仍可手动填写地址", "error", err)
		}
	}()
	return func() {
		if err := conn.Close(); err != nil {
			logger.Warn("关闭局域网发现失败", "error", err)
		}
		<-done
	}, nil
}

func serve(conn *net.UDPConn, httpPort int, logger *slog.Logger) error {
	// 多一个字节检出超长/截断包；串行处理且不维护按来源增长的集合。
	buffer := make([]byte, packetSize+1)
	window := time.Now()
	count := 0
	var lastWriteWarning time.Time
	for {
		n, peer, err := conn.ReadFromUDP(buffer)
		if err != nil {
			if errors.Is(err, net.ErrClosed) {
				return nil
			}
			return err
		}
		if !peer.IP.IsPrivate() && !peer.IP.IsLinkLocalUnicast() && !peer.IP.IsLoopback() {
			continue
		}
		reply := response(buffer[:n], httpPort)
		if reply == nil {
			continue
		}
		now := time.Now()
		if now.Sub(window) >= time.Second {
			window, count = now, 0
		}
		if count >= maxResponsesPerSecond {
			continue
		}
		count++
		if err := conn.SetWriteDeadline(now.Add(writeTimeout)); err != nil {
			return err
		}
		if _, err := conn.WriteToUDP(reply, peer); err != nil {
			if errors.Is(err, net.ErrClosed) {
				return nil
			}
			// 换网瞬间旧路由可能已失效；只放弃本次回应，不能让发现永久停止。
			if now.Sub(lastWriteWarning) >= time.Second {
				logger.Warn("局域网发现回应失败，等待下次查询", "error", err)
				lastWriteWarning = now
			}
		}
	}
}

func response(packet []byte, httpPort int) []byte {
	if len(packet) != packetSize {
		return nil
	}
	request := string(packet)
	if !strings.HasPrefix(request, requestPrefix) {
		return nil
	}
	nonce := request[len(requestPrefix) : len(requestPrefix)+nonceLength]
	if _, err := hex.DecodeString(nonce); err != nil || nonce != strings.ToLower(nonce) {
		return nil
	}
	if strings.Trim(request[len(requestPrefix)+nonceLength:], " ") != "" {
		return nil
	}
	return []byte(responsePrefix + nonce + ":" + strconv.Itoa(httpPort))
}

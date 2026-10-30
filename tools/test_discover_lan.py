"""发现工具的隔离UDP回归，只用随机本机端口，无HTTP/业务数据。"""
import socket
import threading
import unittest
from unittest.mock import patch

import discover_lan


class DiscoveryTest(unittest.TestCase):
    def test_prefers_saved_address_preserves_http_port_and_rejects_stale_reply(self):
        errors = []
        with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as responder:
            responder.bind(("127.0.0.1", 0))
            responder.settimeout(2)
            discovery_port = responder.getsockname()[1]

            def respond():
                try:
                    data, peer = responder.recvfrom(discover_lan.PACKET_SIZE + 1)
                    self.assertEqual(discover_lan.PACKET_SIZE, len(data))
                    prefix, nonce = data.rstrip().decode("ascii").split(":")
                    self.assertEqual("QIMENG_DISCOVER_V1", prefix)
                    # 迟到的旧轮次/非法端口均不能成为候选。
                    responder.sendto(b"QIMENG_SERVER_V1:stale:18080", peer)
                    responder.sendto(f"QIMENG_SERVER_V1:{nonce}:65536".encode(), peer)
                    responder.sendto(f"QIMENG_SERVER_V1:{nonce}:18080".encode(), peer)
                except Exception as error:
                    errors.append(error)

            worker = threading.Thread(target=respond)
            worker.start()
            try:
                with patch.object(discover_lan, "PORT", discovery_port):
                    result = discover_lan.discover(["http://127.0.0.2:8420", "http://127.0.0.1:8420"], [])
                self.assertEqual(["http://127.0.0.1:18080"], result)
            finally:
                worker.join(timeout=3)
            self.assertFalse(worker.is_alive())
            if errors:
                raise errors[0]


if __name__ == "__main__":
    unittest.main()

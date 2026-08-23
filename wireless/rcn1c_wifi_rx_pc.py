"""
Ponte WiFi -> gamepad Xbox 360 virtuale (lato PC).

Riceve via UDP i valori degli stick inviati dal telefono (wireless/rcn1c_phone_tx.py)
e pilota il controller virtuale con la stessa logica di dji_rcn1c_bridge.py
(stick Mode 2, CSC arm/disarm sugli stick -> LB/RB, failsafe a 0 dopo 0.4s di silenzio).

Uso:
    python wireless\\rcn1c_wifi_rx_pc.py [--porta 26789] [--duration N]

Requisiti: ViGEmBus installato; PC e telefono sulla STESSA rete (router o hotspot del telefono).
Sul telefono va impostato l'IP LAN di questo PC (su Windows: ipconfig).
Se non arriva nulla: consenti Python nel firewall di Windows per le reti private.
"""
import argparse
import os
import socket
import struct
import sys
import threading
import time
from datetime import datetime

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
os.environ.setdefault('VGAMEPAD_DEBUG', '0')

from dji_rcn1c_bridge import RAW_CENTER, RAW_MAX, RAW_MIN, Bridge, raw_to_axis  # noqa: E402
from rcn1c_transport import FRAME_V1, FRAME_V2, FRAME_V3, unpack_frame  # noqa: E402

DEFAULT_PORT = 26789
DISCOVERY_PORT = 26790
STALE_SECONDS = 0.4
FRAME = FRAME_V1
FRAME2 = FRAME_V2
FRAME3 = FRAME_V3
PING_MAGIC = b'PNG1'


def discovery_responder(port=DISCOVERY_PORT):
    s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    try:
        s.bind(('0.0.0.0', port))
    except OSError:
        return
    while True:
        try:
            msg, addr = s.recvfrom(64)
        except OSError:
            return
        if msg.startswith(b'RCN1C_DISC'):
            s.sendto(b'RCN1C_HERE', addr)


class WifiBridge(Bridge):
    def __init__(self, duration=None, udp_port=DEFAULT_PORT, allowed_ip=None):
        super().__init__(duration)
        self.udp_port = udp_port
        self.allowed_ip = allowed_ip
        self.sock = None
        self.last_rx = 0.0
        self.sender = '-'

    def open_controller(self):
        self.sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        try:
            self.sock.bind(('0.0.0.0', self.udp_port))
        except OSError as e:
            print(f'[ERRORE] porta UDP {self.udp_port} occupata: {e}')
            print('  un altro receiver e\' gia\' attivo? (AVVIA_WIFI_BRIDGE o AVVIA_VIZ_WIFI)')
            sys.exit(2)
        self.sock.settimeout(0.25)
        print(f'[OK] UDP in ascolto su 0.0.0.0:{self.udp_port} (attendo i pacchetti dal telefono)')

    def apply_inputs(self):
        if self.last_rx and time.time() - self.last_rx > STALE_SECONDS:
            self.state.update(lx=0, ly=0, rx=0, ry=0)
            self.camera_raw = RAW_CENTER
            self.apply_button_mask(0x1000)
            self.lb_pressed = False
            self.rb_pressed = False
            self.csc_arm_hold = 0.0
            self.csc_disarm_hold = 0.0
            self.csc_armed = False
        super().apply_inputs()

    def read_packets(self, deadline):
        last_report = time.time()
        rate_count = 0
        ignored_notice_at = 0.0
        while time.time() < deadline and not self.stop_thread.is_set():
            try:
                data, addr = self.sock.recvfrom(64)
            except socket.timeout:
                continue
            if self.allowed_ip and addr[0] != self.allowed_ip:
                self.stats['ignored'] += 1
                now = time.time()
                if now - ignored_notice_at > 5.0:
                    ignored_notice_at = now
                    print(f'[WARN] pacchetti scartati da {addr[0]} (lock su {self.allowed_ip})',
                          flush=True)
                continue
            if len(data) == 16 and data[:4] == PING_MAGIC:
                self.sock.sendto(data, addr)
                continue
            decoded = unpack_frame(data)
            if decoded is None:
                self.stats['bad'] += 1
                continue
            rx, ry, ly, lx = decoded['rx'], decoded['ry'], decoded['ly'], decoded['lx']
            self.camera_raw = decoded['camera']
            self.apply_button_mask(decoded['button_mask'])
            if not all(RAW_MIN <= v <= RAW_MAX for v in (rx, ry, ly, lx)):
                self.stats['bad'] += 1
                continue
            if self.allowed_ip is None:
                self.allowed_ip = addr[0]
                print(f'[OK] mittente bloccato su {addr[0]}', flush=True)
            self.sender = addr[0]
            self.last_rx = time.time()
            rate_count += 1
            self.stats['packets'] += 1
            self.raw.update(rx=rx, ry=ry, ly=ly, lx=lx)
            self.update_csc()
            self.state['rx'] = raw_to_axis(rx)
            self.state['ry'] = raw_to_axis(ry)
            self.state['ly'] = raw_to_axis(ly)
            self.state['lx'] = raw_to_axis(lx)
            now = time.time()
            if now - last_report >= 1.0:
                fps = rate_count / (now - last_report)
                rate_count = 0
                last_report = now
                s = self.state
                print(f'  [{datetime.now().strftime("%H:%M:%S")}] da {self.sender} '
                      f'LX={s["lx"]:7d} LY={s["ly"]:7d} RX={s["rx"]:7d} RY={s["ry"]:7d} '
                      f'({fps:.0f} pkt/s)', flush=True)

    def run(self):
        try:
            super().run()
        finally:
            if self.sock:
                self.sock.close()


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description='DJI RC-N1C WiFi receiver (UDP -> Xbox360)')
    parser.add_argument('--porta', type=int, default=DEFAULT_PORT,
                        help=f'porta UDP di ascolto (default: {DEFAULT_PORT})')
    parser.add_argument('--duration', type=float, default=None,
                        help='secondi di funzionamento (default: infinito)')
    parser.add_argument('--allow', default=None,
                        help='accetta pacchetti solo da questo IP (default: blocca sul primo mittente)')
    args = parser.parse_args()
    print('=== DJI RC-N1C WiFi Bridge (ricevente PC) ===')
    threading.Thread(target=discovery_responder, daemon=True).start()
    WifiBridge(duration=args.duration, udp_port=args.porta, allowed_ip=args.allow).run()

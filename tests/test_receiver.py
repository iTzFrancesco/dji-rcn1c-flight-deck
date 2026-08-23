import os
import socket
import struct
import subprocess
import sys
import threading
import time

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
PORT = int(os.environ.get('RX_TEST_PORT', '26785'))
FRAME = struct.Struct('<IHHHH')
FRAME3 = struct.Struct('<IHHHHHHBB')
C = 1024
LO = 364 + 90
HI = 1684 - 90

proc = subprocess.Popen(
    [sys.executable, os.path.join(ROOT, 'wireless', 'rcn1c_wifi_rx_pc.py'),
     '--porta', str(PORT), '--duration', '9'],
    stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True,
    encoding='utf-8', errors='replace')

time.sleep(1.5)
s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
stop = threading.Event()


def sender():
    seq = [((1600, C, C, 900), 0.0067)] * 150 \
        + [((LO - 10, LO - 10, LO - 10, HI + 10), 0.0067)] * 210 \
        + [((1600, C, C, 900), 0.0067)] * 150
    for i, (vals, dt) in enumerate(seq):
        if i >= 150 and i < 360:
            rx, ry, ly, lx = vals
            s.sendto(FRAME3.pack(i, rx, ry, ly, lx, 1024, 0x1060, 1, 0),
                     ('127.0.0.1', PORT))
        else:
            s.sendto(FRAME.pack(i, *vals), ('127.0.0.1', PORT))
        if stop.wait(dt):
            return


threading.Thread(target=sender, daemon=True).start()
time.sleep(0.6)

ping = b'PNG1' + struct.pack('<iQ', 7, 1234)
s.sendto(ping, ('127.0.0.1', PORT))
s.settimeout(0.5)
pong_ok = False
try:
    echo, _ = s.recvfrom(64)
    pong_ok = echo == ping
except OSError:
    pass

h = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
h.bind(('127.0.0.2', 0))
for j in range(40):
    h.sendto(FRAME.pack(9000 + j, C, C, C, C), ('127.0.0.1', PORT))
    time.sleep(0.01)

out, _ = proc.communicate(timeout=30)
stop.set()
checks = [
    ('lock mittente', 'mittente bloccato su 127.0.0.1' in out),
    ('scarto IP ostile', 'scartati da 127.0.0.2' in out),
    ('CSC arm ~1.1s', '[CSC] ARM' in out),
    ('pulsante scatto v3', '[INPUT] mask=0x1060' in out),
    ('ping/pong', pong_ok),
]
ok = True
for name, res in checks:
    print(f'[{"PASS" if res else "FAIL"}] ricevente: {name}')
    ok &= res
if not ok:
    print(out)
sys.exit(0 if ok else 1)

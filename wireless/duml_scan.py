"""
Scanner attivo protocollo DJI DMUL su RC-N1C (USB).

Estrae le tabelle CRC dal progetto di riferimento online, costruisce richieste
DMUL valide e confronta le risposte: riposo VS mentre tieni premuto un tasto.
Report: wireless/duml_scan_report.txt
"""
import os
import re
import sys
import time
import urllib.request
from datetime import datetime

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

import serial
import serial.tools.list_ports

from dji_rcn1c_bridge import BAUD, DJI_PORT_DESCRIPTIONS

REPORT = os.path.join(os.path.dirname(os.path.abspath(__file__)), 'duml_scan_report.txt')
SRC_URL = 'https://raw.githubusercontent.com/thanhmobiledev/DJI-RC-N1-SIMULATOR-FLY-CONTROLLER-PYTHON/main/main.py'


def load_crc_tables():
    tmp = os.path.join(os.environ.get('TEMP', '.'), 'rcn1_ref_main.py')
    if not os.path.exists(tmp):
        urllib.request.urlretrieve(SRC_URL, tmp)
    src = open(tmp, encoding='utf-8', errors='ignore').read()
    m1 = re.search(r'crc\s*=\s*\[(.*?)\]', src, re.S)
    m2 = re.search(r'arr_2A103\s*=\s*\[(.*?)\]', src, re.S)
    if not m1 or not m2:
        raise RuntimeError('tabelle CRC non trovate nel riferimento')
    crc = eval('[' + m1.group(1) + ']')
    hdr = eval('[' + m2.group(1) + ']')
    return crc, hdr


CRC = None
HDR = None

KNOWN_REQUESTS = {
    (0x01, b''): bytes.fromhex('550d04330a06eb344006017424'),
    (0x27, b''): bytes.fromhex('550d04330a06eb344006274060'),
    (0x24, b'\x01'): bytes.fromhex('550e04660a06eb3440062401d9ec'),
}


def ensure_crc_tables():
    global CRC, HDR
    if CRC is None or HDR is None:
        CRC, HDR = load_crc_tables()


def payload_crc(data):
    ensure_crc_tables()
    v = 0x3692
    for b in data:
        v = (v >> 8) ^ CRC[(b ^ v) & 0xFF]
    return v


def hdr_crc(h3):
    ensure_crc_tables()
    c = 0x77
    for b in h3:
        c = HDR[(b ^ c) & 0xFF]
    return c


def build(src, dst, cmd_type, cmd_set, cmd_id, payload=b'', seq=0x34EB):
    if src == 0x0A and dst == 0x06 and cmd_type == 0x40 and cmd_set == 0x06:
        known = KNOWN_REQUESTS.get((cmd_id, bytes(payload)))
        if known is not None and seq == 0x34EB:
            return known
    body_len = 13 + len(payload)
    p = bytearray(b'\x55')
    p.append(body_len & 0xFF)
    p.append((body_len >> 8) | 0x04)
    p.append(hdr_crc(bytes(p[-3:])))
    p += bytes([src, dst])
    p += seq.to_bytes(2, 'little')
    p += bytes([cmd_type, cmd_set, cmd_id]) + payload
    p += payload_crc(p).to_bytes(2, 'little')
    return bytes(p)


def pop_frame(buf):
    while len(buf) >= 4:
        if buf[0] != 0x55:
            del buf[0]
            continue
        ln = int.from_bytes(buf[1:3], 'little') & 0x3FF
        if ln < 13 or ln > 512:
            del buf[0]
            continue
        if len(buf) < ln:
            return None
        pkt = bytes(buf[:ln])
        del buf[:ln]
        payload = pkt[10:-2]
        return (pkt[4], pkt[5], pkt[9], payload)
    return None


def find_port():
    for p in serial.tools.list_ports.comports(True):
        if any(d in (p.description or '') for d in DJI_PORT_DESCRIPTIONS):
            return p.device, p.description
    return None, None


def sweep(ser, buf, ids, seconds):
    """Interroga ogni id ripetutamente per `seconds`; ritorna {id: {(len,sig)}}"""
    prof = {}
    end = time.time() + seconds
    i = 0
    ids = list(ids)
    while time.time() < end:
        cid = ids[i % len(ids)]
        i += 1
        ser.write(build(0x0A, 0x06, 0x40, 0x06, cid))
        t0 = time.time()
        while time.time() - t0 < 0.03:
            chunk = ser.read(64)
            if not chunk:
                continue
            buf.extend(chunk)
            while True:
                r = pop_frame(buf)
                if r is None:
                    break
                s_, d_, pid_, payload = r
                if d_ != 0x0A:
                    continue
                sig = (len(payload), payload[:8].hex())
                prof.setdefault(cid, set()).add(sig)
    return prof


def main():
    name, desc = find_port()
    if not name:
        print('[ERRORE] porta VCOM non trovata')
        sys.exit(1)
    ser = serial.Serial(port=name, baudrate=BAUD, timeout=0.005)
    print(f'[OK] {desc}')
    buf = bytearray()

    lines = []

    def w(s=''):
        print(s, flush=True)
        lines.append(s)

    IDS = [i for i in range(0x00, 0x30) if i not in (0x01,)]
    w('Fase 1: sweep a RIPOSO (~6s), non toccare nulla...')
    idle = sweep(ser, buf, IDS, 6.0)
    w(f'   id che hanno risposto: {sorted(idle.keys())}')

    w('\n>>> PREPARATI! Tra 5 secondi: TIENI PREMUTO SCATTO <<<')
    for k in range(5, 0, -1):
        w(f'    {k}...')
        time.sleep(1)

    phases = [('SCATTO / registra video (TIENI PREMUTO)', 5.0),
              ('RTH / STOP (TIENI PREMUTO)', 5.0),
              ('FN (TIENI PREMUTO)', 4.0)]
    results = []
    first = True
    for label, dur in phases:
        if not first:
            w('\n>>> PROSSIMO TRA 3 SECONDI <<<')
            for k in range(3, 0, -1):
                w(f'    {k}...')
                time.sleep(1)
        first = False
        w(f"Fase: {label}  ({dur}s)")
        prof = sweep(ser, buf, sorted(idle.keys()), dur)
        diffs = {}
        for cid in sorted(prof):
            new = prof[cid] - idle.get(cid, set())
            gone = idle.get(cid, set()) - prof[cid]
            if new or gone:
                diffs[cid] = (new, gone)
        results.append((label, diffs))
        if not diffs:
            w('   nessuna differenza rispetto al riposo')

    w('\n===== REPORT =====')
    for label, diffs in results:
        w(f'\n[{label}]')
        if not diffs:
            w('  NESSUNA variazione')
        for cid, (new, gone) in sorted(diffs.items()):
            w(f'  cmd_id 0x{cid:02X}:')
            for n in sorted(new):
                w(f'    + len={n[0]} sig={n[1]}')
            for g in sorted(gone):
                w(f'    - len={g[0]} sig={g[1]}')
    w('\nFINE')

    with open(REPORT, 'w', encoding='utf-8') as f:
        f.write(f'# scan {datetime.now().isoformat()}\n')
        f.write('\n'.join(lines))
        f.write('\nDONE\n')
    print('\nReport salvato.')


if __name__ == '__main__':
    try:
        main()
    except KeyboardInterrupt:
        pass

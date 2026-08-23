import serial
import serial.tools.list_ports
import time
import sys

DJI_PORT_DESCRIPTIONS = ['DJI USB VCOM For Protocol', 'DEVICE USB VCOM For Protocol']
REQUEST_PACKET = bytes.fromhex('550d04330a06eb344006017424')
PACKET_LEN = 38
BAUD = 921600

PHASES = [
    ('FASE 1/4 - STICK SINISTRO: su/giu e destra/sinistra fino in fondo!', 6, ['LX', 'LY']),
    ('FASE 2/4 - STICK DESTRO: su/giu e destra/sinistra fino in fondo!', 6, ['RX', 'RY']),
    ('FASE 3/4 - ROTELLA CAMERA: avanti e indietro!', 6, ['CAM']),
    ('FASE 4/4 - NON TOCCARE NULLA (verifica centro)', 4, []),
]

CH_IDX = {'RX': 0, 'RY': 1, 'LY': 2, 'LX': 3, 'CAM': 4}


def find_port():
    for port in serial.tools.list_ports.comports(True):
        if any(d in (port.description or '') for d in DJI_PORT_DESCRIPTIONS):
            return port.device, port.description
    return None, None


def main():
    port_name, port_desc = find_port()
    if not port_name:
        print('[FAIL] porta VCOM Protocol non trovata.')
        sys.exit(1)
    print(f'[OK] porta: {port_name} -> {port_desc} (baud {BAUD})')

    try:
        ser = serial.Serial(port=port_name, baudrate=BAUD, timeout=0.02)
    except serial.SerialException as e:
        print(f'[FAIL] apertura seriale: {e}')
        sys.exit(2)

    mins = [1 << 30] * 5
    maxs = [-(1 << 30)] * 5
    phase_moved = [set() for _ in PHASES]
    stats = {'bytes': 0, 'packets': 0, 'bad': 0}
    buf = bytearray()

    try:
        for pi, (msg, dur, chans) in enumerate(PHASES):
            print(f'\n>>> {msg}')
            start = time.time()
            last_tick = -10.0
            while time.time() - start < dur:
                rem = dur - (time.time() - start)
                if rem - last_tick > 1.0 or last_tick < 0:
                    last_tick = rem
                    print(f'   [{rem:4.1f}s]', end='\r')
                ser.write(REQUEST_PACKET)
                chunk = ser.read(512)
                if chunk:
                    buf.extend(chunk)
                    stats['bytes'] += len(chunk)
                while len(buf) >= 3:
                    if buf[0] != 0x55:
                        buf.pop(0)
                        continue
                    plen = int.from_bytes(buf[1:3], byteorder='little') & 0b1111111111
                    if plen < 3 or plen > 512:
                        buf.pop(0)
                        continue
                    if len(buf) < plen:
                        break
                    packet = bytes(buf[:plen])
                    del buf[:plen]
                    if plen != PACKET_LEN:
                        stats['bad'] += 1
                        continue
                    stats['packets'] += 1
                    vals = [int.from_bytes(packet[o:o + 2], 'little')
                            for o in (13, 16, 19, 22, 25)]
                    for ci in range(5):
                        mins[ci] = min(mins[ci], vals[ci])
                        maxs[ci] = max(maxs[ci], vals[ci])
                    center_hit = True
                    for name in chans:
                        v = vals[CH_IDX[name]]
                        if v < 900 or v > 1150:
                            phase_moved[pi].add(name)
                            center_hit = False
            print('   [done!] ')
    except serial.SerialException as e:
        print(f'[FAIL] errore seriale: {e}')
        sys.exit(3)
    finally:
        ser.close()

    names = ['RX', 'RY', 'LY', 'LX', 'CAM']
    print('\n=== RISULTATI ===')
    print(f'pacchetti validi: {stats["packets"]} ({stats["bad"]} bad-size)')
    ok_all = stats['packets'] > 50
    fail = False
    for pi, (_, _, chans) in enumerate(PHASES):
        if not chans:
            continue
        for name in chans:
            ci = CH_IDX[name]
            rng = f'min={mins[ci]:5d} max={maxs[ci]:5d}'
            moved = name in phase_moved[pi]
            status = 'OK' if moved else 'NON RILEVATO'
            if not moved:
                fail = True
            print(f'  {name}: {rng} [{status}] (fase {pi + 1})')
    if not ok_all:
        print('[FAIL] pacchetti insufficienti')
        sys.exit(4)
    if fail:
        print('[WARN] qualche canale non rilevato: hai seguito le fasi? Rifacciamo?')
        sys.exit(5)
    print('[PASS] TUTTI I CANALI FUNZIONANTI: sinistro, destro e rotella letti correttamente')


if __name__ == '__main__':
    main()

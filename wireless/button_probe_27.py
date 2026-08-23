"""Sonda DUML 0x27 per i pulsanti fisici del DJI RC-N1C.

Il comando 0x01 restituisce gli stick in 38 byte. Il comando 0x27, documentato
da progetti per RC-N3/N2, può restituire 58 byte con uno stato pulsanti a
offset 28:30. Questa sonda interroga entrambi e registra anche i frame
inaspettati, senza inviare comandi di volo.
"""
import argparse
import os
import sys
import time
from collections import Counter
from datetime import datetime

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

import serial
import serial.tools.list_ports

from dji_rcn1c_bridge import BAUD, DJI_PORT_DESCRIPTIONS
from duml_scan import build, hdr_crc
from rcn1c_protocol import decode_button_packet

REPORT = os.path.join(os.path.dirname(os.path.abspath(__file__)), 'button_27_report.txt')
CMD_ENABLE_SIM = 0x24
CMD_READ_STICKS = 0x01
CMD_READ_BUTTONS = 0x27
def find_port(explicit=None):
    if explicit:
        return explicit, explicit
    for port in serial.tools.list_ports.comports(True):
        if any(d in (port.description or '') for d in DJI_PORT_DESCRIPTIONS):
            return port.device, port.description
    return None, None


def pop_frame(buf):
    frames = []
    while True:
        start = buf.find(b'\x55')
        if start < 0:
            buf.clear()
            break
        if start:
            del buf[:start]
        if len(buf) < 4:
            break
        length = int.from_bytes(buf[1:3], 'little') & 0x3FF
        if length < 13 or length > 512:
            del buf[0]
            continue
        if len(buf) < length:
            break
        packet = bytes(buf[:length])
        del buf[:length]
        if hdr_crc(packet[:3]) != packet[3]:
            continue
        frames.append(packet)
    return frames


def decode_buttons(packet):
    state = decode_button_packet(packet)
    if state is None:
        return None
    return {
        'raw': state['mask'],
        'mode': state['mode'],
        'pressed': {
            'SCATTO/REGISTRA': state['shutter'],
            'FOTO/VIDEO': state['photo_video'],
            'RTH/STOP': state['rth'],
            'FN': state['fn'],
        },
    }


def poll(ser, buf, duration, counters, samples, changes):
    end = time.monotonic() + duration
    next_poll = 0.0
    last_state = None
    while time.monotonic() < end:
        now = time.monotonic()
        if now >= next_poll:
            ser.write(build(0x0A, 0x06, 0x40, 0x06, CMD_READ_STICKS))
            ser.write(build(0x0A, 0x06, 0x40, 0x06, CMD_READ_BUTTONS))
            next_poll = now + 0.02
        chunk = ser.read(256)
        if not chunk:
            continue
        buf.extend(chunk)
        for packet in pop_frame(buf):
            key = (len(packet), packet[9] if len(packet) > 9 else -1)
            counters[key] += 1
            samples.setdefault(key, packet.hex(' '))
            state = decode_buttons(packet)
            if state is None or state == last_state:
                continue
            last_state = state
            changes.append(state)
    return last_state


def countdown(write, seconds=3):
    for value in range(seconds, 0, -1):
        write(f'    {value}...')
        time.sleep(1)


def main():
    parser = argparse.ArgumentParser(description='Sonda pulsanti DUML 0x27 del DJI RC-N1C')
    parser.add_argument('--port', help='porta seriale, ad esempio COM4')
    parser.add_argument('--report', default=REPORT, help='percorso report')
    parser.add_argument('--hold-seconds', type=int, default=10,
                        help='secondi disponibili per ciascun pulsante')
    parser.add_argument('--mode-seconds', type=int, default=12,
                        help='secondi disponibili per lo switch modalità')
    args = parser.parse_args()

    name, description = find_port(args.port)
    if not name:
        print('[ERRORE] porta VCOM Protocol non trovata')
        return 1

    lines = []

    def write(line=''):
        print(line, flush=True)
        lines.append(line)

    counters = Counter()
    samples = {}
    changes = []
    buf = bytearray()

    try:
        ser = serial.Serial(port=name, baudrate=BAUD, timeout=0.005)
    except serial.SerialException as exc:
        print(f'[ERRORE] apertura {name}: {exc}')
        return 1

    try:
        write(f'[OK] {name} | {description}')
        write('[INFO] invio solo enable-simulator e richieste di lettura DUML')
        ser.reset_input_buffer()
        ser.write(build(0x0A, 0x06, 0x40, 0x06, CMD_ENABLE_SIM, b'\x01'))
        poll(ser, buf, 2.5, counters, samples, changes)
        write('[OK] riposo acquisito; il valore 0x1000 normalmente indica NORMAL')

        phases = [
            ('SCATTO / REGISTRA VIDEO', args.hold_seconds),
            ('TASTO FOTO/VIDEO', args.hold_seconds),
            ('RTH / STOP', args.hold_seconds),
            ('FN', args.hold_seconds),
        ]
        for label, seconds in phases:
            write(f'\n>>> PREPARATI: {label} <<<')
            countdown(write)
            write(f'PREMI e tieni premuto {label} per {seconds} secondi')
            before = len(changes)
            poll(ser, buf, seconds, counters, samples, changes)
            observed = changes[before:]
            if observed:
                values = sorted({f"0x{s['raw']:04X}" for s in observed})
                write(f'    valori pulsanti osservati: {", ".join(values)}')
            else:
                write('    nessuna variazione del frame 0x27')

        write('\n>>> CAMBIO MODALITA: SPORT, NORMAL, CINE <<<')
        write(f'Aziona lo switch lentamente tra le tre posizioni durante {args.mode_seconds} secondi.')
        countdown(write)
        before = len(changes)
        poll(ser, buf, args.mode_seconds, counters, samples, changes)
        observed = changes[before:]
        modes = sorted({s['mode'] for s in observed})
        write(f'    modalita osservate: {", ".join(modes) if modes else "nessuna variazione"}')
    except KeyboardInterrupt:
        write('\n[INTERRUZIONE]')
    finally:
        ser.close()

    write('\n===== RISULTATO =====')
    for key, count in sorted(counters.items()):
        write(f'  count={count} total_len={key[0]} byte9/cmd=0x{key[1]:02X}')
    for state in changes:
        pressed = ', '.join(name for name, active in state['pressed'].items() if active) or '-'
        write(f"  mask=0x{state['raw']:04X} mode={state['mode']} premuti={pressed}")
    write('\nCampioni frame:')
    for key in sorted(samples):
        write(f'  len={key[0]} cmd=0x{key[1]:02X}: {samples[key]}')

    report_path = os.path.abspath(args.report)
    with open(report_path, 'w', encoding='utf-8') as report:
        report.write(f'# report {datetime.now().isoformat()}\n')
        report.write('\n'.join(lines))
        report.write('\nDONE\n')
    print(f'\nReport salvato in {report_path}')
    return 0


if __name__ == '__main__':
    raise SystemExit(main())

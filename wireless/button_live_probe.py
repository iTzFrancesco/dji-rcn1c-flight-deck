"""Monitor minimale in tempo reale dei pulsanti RC-N1C via DUML 0x27."""
import argparse
import os
import sys
import time

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

import serial

from button_probe_27 import decode_buttons, find_port, pop_frame
from dji_rcn1c_bridge import BAUD
from duml_scan import build

CMD_ENABLE_SIM = 0x24
CMD_READ_STICKS = 0x01
CMD_READ_BUTTONS = 0x27


def main():
    parser = argparse.ArgumentParser(description='Monitor live pulsanti RC-N1C')
    parser.add_argument('--port', default=None, help='porta seriale, ad esempio COM4')
    parser.add_argument('--duration', type=float, default=120,
                        help='secondi di monitoraggio (default: 120)')
    args = parser.parse_args()

    name, description = find_port(args.port)
    if not name:
        print('[ERRORE] porta VCOM Protocol non trovata')
        return 1

    try:
        ser = serial.Serial(port=name, baudrate=BAUD, timeout=0.005)
    except serial.SerialException as exc:
        print(f'[ERRORE] apertura {name}: {exc}')
        return 1

    buf = bytearray()
    last_state = None
    last_poll = 0.0
    deadline = time.monotonic() + args.duration

    print(f'[OK] {name} | {description}', flush=True)
    print('[INFO] monitor attivo: premi e rilascia i pulsanti; Ctrl+C per uscire', flush=True)
    print('[INFO] non viene creato alcun gamepad virtuale', flush=True)

    try:
        ser.reset_input_buffer()
        ser.write(build(0x0A, 0x06, 0x40, 0x06, CMD_ENABLE_SIM, b'\x01'))
        while time.monotonic() < deadline:
            now = time.monotonic()
            if now >= last_poll:
                ser.write(build(0x0A, 0x06, 0x40, 0x06, CMD_READ_STICKS))
                ser.write(build(0x0A, 0x06, 0x40, 0x06, CMD_READ_BUTTONS))
                last_poll = now + 0.02
            chunk = ser.read(256)
            if not chunk:
                continue
            buf.extend(chunk)
            for packet in pop_frame(buf):
                state = decode_buttons(packet)
                if state is None or state == last_state:
                    continue
                last_state = state
                pressed = ', '.join(
                    name for name, active in state['pressed'].items() if active
                ) or '-'
                stamp = time.strftime('%H:%M:%S')
                print(
                    f'[{stamp}] mask=0x{state["raw"]:04X} '
                    f'modalità={state["mode"]} premuti={pressed}',
                    flush=True,
                )
    except KeyboardInterrupt:
        print('\n[STOP] monitor interrotto', flush=True)
    finally:
        ser.close()
    return 0


if __name__ == '__main__':
    raise SystemExit(main())

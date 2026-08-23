"""
DJI RC-N1C -> Xbox 360 virtuale (bridge open source migliorato).

Uso:
    python dji_rcn1c_bridge.py                 # gira finche' non premi Ctrl+C
    python dji_rcn1c_bridge.py --duration 12   # si ferma da solo dopo 12 secondi

Requisiti: driver ViGEmBus installato, radiocomando acceso collegato alla porta USB-C inferiore.
Protocollo: pacchetti 0x55 su porta "DEVICE USB VCOM For Protocol". Gli stick
arrivano nel frame da 38 byte; i pulsanti nel frame da 58 byte richiesto con
DUML 0x27.
"""
import argparse
import os
import sys
import threading
import time
from datetime import datetime

import serial
import serial.tools.list_ports

os.environ.setdefault('VGAMEPAD_DEBUG', '0')
import vgamepad as vg

from rcn1c_protocol import (
    BAUD,
    BUTTON_PACKET_LEN,
    ENABLE_SIMULATOR,
    PACKET_LEN,
    PUSH_PACKET_LEN,
    REQUEST_BUTTONS,
    REQUEST_STICKS,
    RAW_CENTER,
    RAW_MAX,
    RAW_MIN,
    decode_button_packet,
    decode_button_mask,
    raw_to_axis,
)

DJI_PORT_DESCRIPTIONS = ['DJI USB VCOM For Protocol', 'DEVICE USB VCOM For Protocol']
REQUEST_PACKET = REQUEST_STICKS
BUTTON_REQUEST_PACKET = REQUEST_BUTTONS
AXIS_MAX = 32767
CSC_THRESHOLD = 90
CSC_HOLD_SECONDS = 1.1
CSC_PULSE_SECONDS = 0.25


def find_port():
    for port in serial.tools.list_ports.comports(True):
        if any(d in (port.description or '') for d in DJI_PORT_DESCRIPTIONS):
            return port.device, port.description
    return None, None


class Bridge:
    def __init__(self, duration=None):
        self.duration = duration
        self.ser = None
        self.gamepad = None
        self.stop_thread = threading.Event()
        self.state = {'lx': 0, 'ly': 0, 'rx': 0, 'ry': 0}
        self.camera_raw = RAW_CENTER
        self.raw = {'lx': RAW_CENTER, 'ly': RAW_CENTER, 'rx': RAW_CENTER, 'ry': RAW_CENTER}
        self.csc_arm_hold = 0.0
        self.csc_disarm_hold = 0.0
        self.csc_pulse_until = 0.0
        self.csc_active = None
        self.lb_pressed = False
        self.rb_pressed = False
        self.csc_armed = False
        self.last_csc_t = None
        self.fn_pressed = False
        self.shutter_pressed = False
        self.photo_video_pressed = False
        self.rth_pressed = False
        self.button_mask = 0x1000
        self.mode = 'NORMAL'
        self.buttons27_seen = False
        self.last_button_mask = None
        self.camera_right_button = vg.XUSB_BUTTON.XUSB_GAMEPAD_B
        self.camera_left_button = vg.XUSB_BUTTON.XUSB_GAMEPAD_A
        self.button_map = {
            'shutter': vg.XUSB_BUTTON.XUSB_GAMEPAD_Y,
            'photo_video': vg.XUSB_BUTTON.XUSB_GAMEPAD_BACK,
            'rth': vg.XUSB_BUTTON.XUSB_GAMEPAD_START,
            'fn': vg.XUSB_BUTTON.XUSB_GAMEPAD_X,
        }
        self.camera_threshold = int(0.15 * (RAW_MAX - RAW_CENTER))
        self.stats = {
            'packets': 0, 'button_packets': 0, 'bad': 0, 'bytes': 0,
            'updates': 0, 'ignored': 0,
        }

    def apply_button_mask(self, mask):
        decoded = decode_button_mask(mask)
        self.button_mask = mask
        self.mode = decoded['mode']
        self.shutter_pressed = decoded['shutter']
        self.photo_video_pressed = decoded['photo_video']
        self.rth_pressed = decoded['rth']
        self.fn_pressed = decoded['fn']
        if self.last_button_mask != mask:
            self.last_button_mask = mask
            pressed = [
                name for name, active in (
                    ('SCATTO', self.shutter_pressed),
                    ('FOTO/VIDEO', self.photo_video_pressed),
                    ('RTH', self.rth_pressed),
                    ('FN', self.fn_pressed),
                ) if active
            ]
            suffix = ','.join(pressed) if pressed else '-'
            print(f'[INPUT] mask=0x{mask:04X} mode={self.mode} buttons={suffix}', flush=True)

    def open_controller(self):
        port_name, port_desc = find_port()
        if not port_name:
            print('[ERRORE] Porta "VCOM For Protocol" non trovata.')
            print('  - il radiocomando e\' acceso?')
            print('  - cavo USB-C dati sulla porta INFERIORE?')
            print('  - DJI Assistant 2 chiuso (occupa la porta)?')
            sys.exit(1)
        try:
            self.ser = serial.Serial(port=port_name, baudrate=BAUD, timeout=0.01)
        except serial.SerialException as e:
            print(f'[ERRORE] apertura {port_name}: {e}')
            sys.exit(2)
        print(f'[OK] {port_desc} aperta')

    def open_gamepad(self):
        try:
            self.gamepad = vg.VX360Gamepad()
        except Exception as e:
            print(f'[ERRORE] ViGEmBus non disponibile: {e}')
            print('  installa il driver: winget install ViGEm.ViGEmBus')
            sys.exit(3)
        print('[OK] Controller Xbox 360 virtuale creato')

    def apply_inputs(self):
        g = self.gamepad
        s = self.state
        g.left_joystick(s['lx'], s['ly'])
        g.right_joystick(s['rx'], s['ry'])
        if self.camera_raw >= RAW_CENTER + self.camera_threshold:
            g.press_button(vg.XUSB_BUTTON.XUSB_GAMEPAD_A)
            g.release_button(vg.XUSB_BUTTON.XUSB_GAMEPAD_B)
        elif self.camera_raw <= RAW_CENTER - self.camera_threshold:
            g.press_button(vg.XUSB_BUTTON.XUSB_GAMEPAD_B)
            g.release_button(vg.XUSB_BUTTON.XUSB_GAMEPAD_A)
        else:
            g.release_button(self.camera_right_button)
            g.release_button(self.camera_left_button)
        if self.lb_pressed:
            g.press_button(vg.XUSB_BUTTON.XUSB_GAMEPAD_LEFT_SHOULDER)
        else:
            g.release_button(vg.XUSB_BUTTON.XUSB_GAMEPAD_LEFT_SHOULDER)
        if self.rb_pressed:
            g.press_button(vg.XUSB_BUTTON.XUSB_GAMEPAD_RIGHT_SHOULDER)
        else:
            g.release_button(vg.XUSB_BUTTON.XUSB_GAMEPAD_RIGHT_SHOULDER)
        if self.fn_pressed:
            g.press_button(self.button_map['fn'])
        else:
            g.release_button(self.button_map['fn'])
        for attr, name in (
            ('shutter_pressed', 'shutter'),
            ('photo_video_pressed', 'photo_video'),
            ('rth_pressed', 'rth'),
        ):
            if getattr(self, attr):
                g.press_button(self.button_map[name])
            else:
                g.release_button(self.button_map[name])
        g.update()
        self.stats['updates'] += 1

    def update_csc(self):
        lo = RAW_MIN + CSC_THRESHOLD
        hi = RAW_MAX - CSC_THRESHOLD
        r = self.raw
        combo = None
        if r['ly'] < lo:
            if r['lx'] > hi and r['ry'] < lo and r['rx'] < lo:
                combo = 'arm'
            elif r['lx'] < lo and r['ry'] < lo and r['rx'] > hi:
                combo = 'disarm'
        now = time.time()
        dt = 1 / 30 if self.last_csc_t is None else min(max(now - self.last_csc_t, 0.0), 0.1)
        self.last_csc_t = now
        if combo == 'arm':
            self.csc_arm_hold += dt
            self.csc_disarm_hold = 0.0
        elif combo == 'disarm':
            self.csc_disarm_hold += dt
            self.csc_arm_hold = 0.0
        else:
            self.csc_arm_hold = 0.0
            self.csc_disarm_hold = 0.0
        if now >= self.csc_pulse_until:
            self.lb_pressed = False
            self.rb_pressed = False
        if combo == 'arm' and self.csc_arm_hold >= CSC_HOLD_SECONDS and now >= self.csc_pulse_until:
            self.lb_pressed = True
            self.rb_pressed = False
            self.csc_pulse_until = now + CSC_PULSE_SECONDS
            if not self.csc_armed:
                print('[CSC] ARM -> gas SBLOCCATO', flush=True)
            self.csc_armed = True
        elif combo == 'disarm' and self.csc_disarm_hold >= CSC_HOLD_SECONDS and now >= self.csc_pulse_until:
            self.rb_pressed = True
            self.lb_pressed = False
            self.csc_pulse_until = now + CSC_PULSE_SECONDS
            if self.csc_armed:
                print('[CSC] DISARM -> gas BLOCCATO', flush=True)
            self.csc_armed = False

    def gamepad_loop(self):
        while not self.stop_thread.is_set():
            try:
                self.apply_inputs()
            except Exception as e:
                print(f'[ERRORE] gamepad thread: {e}', flush=True)
                return
            time.sleep(0.008)

    def read_packets(self, deadline):
        buf = bytearray()
        last_report = time.time()
        self.ser.reset_input_buffer()
        self.ser.write(ENABLE_SIMULATOR)
        while time.time() < deadline and not self.stop_thread.is_set():
            self.ser.write(REQUEST_PACKET)
            self.ser.write(BUTTON_REQUEST_PACKET)
            chunk = self.ser.read(128)
            if chunk:
                buf.extend(chunk)
                self.stats['bytes'] += len(chunk)
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
                if plen == BUTTON_PACKET_LEN:
                    button_state = decode_button_packet(packet)
                    self.buttons27_seen = True
                    self.stats['button_packets'] += 1
                    self.apply_button_mask(button_state['mask'])
                    continue
                if plen == PUSH_PACKET_LEN:
                    continue
                if plen != PACKET_LEN:
                    self.stats['bad'] += 1
                    continue
                self.stats['packets'] += 1
                if not self.buttons27_seen:
                    self.fn_pressed = bool(packet[28] & 0x01)
                raw = {
                    'rx': int.from_bytes(packet[13:15], 'little'),
                    'ry': int.from_bytes(packet[16:18], 'little'),
                    'ly': int.from_bytes(packet[19:21], 'little'),
                    'lx': int.from_bytes(packet[22:24], 'little'),
                }
                self.raw.update(raw)
                self.update_csc()
                self.state['rx'] = raw_to_axis(raw['rx'])
                self.state['ry'] = raw_to_axis(raw['ry'])
                self.state['ly'] = raw_to_axis(raw['ly'])
                self.state['lx'] = raw_to_axis(raw['lx'])
                self.camera_raw = int.from_bytes(packet[25:27], 'little')
                now = time.time()
                if now - last_report >= 1.0:
                    last_report = now
                    s, c = self.state, self.camera_raw
                    print(f'  [{datetime.now().strftime("%H:%M:%S")}] '
                          f'LX={s["lx"]:7d} LY={s["ly"]:7d} '
                          f'RX={s["rx"]:7d} RY={s["ry"]:7d} CAM={c:5d} '
                          f'(pkt totali: {self.stats["packets"]})', flush=True)

    def run(self):
        print(f'=== DJI RC-N1C Bridge === ({datetime.now().strftime("%H:%M:%S")})')
        self.open_controller()
        self.open_gamepad()
        print('Mappatura: stick sinistro->LS (throttle/yaw), destro->RS (pitch/roll), rotella->A/B')
        print('Pulsanti: SCATTO/REC->Y, FOTO/VIDEO->BACK, RTH/STOP->START, FN->X')
        print('Muovi gli stick: i valori qui sotto devono cambiare.')
        deadline = (time.time() + self.duration) if self.duration else float('inf')
        t = threading.Thread(target=self.gamepad_loop, daemon=True)
        t.start()
        try:
            self.read_packets(deadline)
        except KeyboardInterrupt:
            pass
        except serial.SerialException as e:
            print(f'\n[ERRORE] porta seriale: {e}')
        finally:
            self.stop_thread.set()
            t.join(timeout=1)
            try:
                if self.gamepad:
                    self.gamepad.reset()
                    self.gamepad.update()
            except Exception:
                pass
            if self.ser:
                self.ser.close()
            el = self.stats['packets']
            print('\n=== STATISTICHE ===')
            print(f"pacchetti stick: {el}, pulsanti: {self.stats['button_packets']}, "
                  f"scarti: {self.stats['bad']}, bytes: {self.stats['bytes']}, "
                  f"update gamepad: {self.stats['updates']}")
            print('Bridge terminato pulito.')


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description='DJI RC-N1C to Xbox360 bridge')
    parser.add_argument('--duration', type=float, default=None,
                        help='secondi di funzionamento (default: infinito)')
    args = parser.parse_args()
    Bridge(duration=args.duration).run()

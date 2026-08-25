"""
DJI RC-N1C Control Center - visualizzatore real-time degli input.

Avvio:  python controller_viz.py                 (modalita' seriale USB, solo dashboard)
        python controller_viz.py --gamepad       (USB + dashboard + gamepad Xbox virtuale)
        python controller_viz.py --source udp    (modalita' WiFi: riceve i frame dal telefono,
                                                  crea anche il gamepad Xbox virtuale)

Architettura:
  - thread sorgente: seriale VCOM (come il bridge) OPPURE socket UDP del ponte telefono
  - thread HTTP: serve la dashboard (cartella static/)
  - WebSocket: strema snapshot JSON dei canali ai client connessi
  - opzionale: thread gamepad -> ViGEm
"""
import argparse
import asyncio
import json
import socket
import struct
import sys
import threading
import time
import webbrowser
from collections import deque
from http.server import ThreadingHTTPServer, SimpleHTTPRequestHandler
from pathlib import Path

import serial
import serial.tools.list_ports
import websockets

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

from rcn1c_protocol import (
    BAUD, BUTTON_PACKET_LEN, ENABLE_SIMULATOR, PACKET_LEN, REQUEST_BUTTONS,
    REQUEST_STICKS, RAW_CENTER, RAW_MAX, RAW_MIN, decode_button_packet,
    decode_button_mask,
)
from rcn1c_transport import FRAME_V1, FRAME_V2, FRAME_V3, unpack_frame

from registra_volo import Recorder

HTTP_PORT = 8123
WS_PORT = 8124
DEFAULT_UDP_PORT = 26789
DISCOVERY_PORT = 26790
STALE_SECONDS = 0.4
FRAME = FRAME_V1
FRAME2 = FRAME_V2
FRAME3 = FRAME_V3
PING_MAGIC = b'PNG1'
AXIS_MAX = 32767
DJI_PORT_DESCRIPTIONS = ['DJI USB VCOM For Protocol', 'DEVICE USB VCOM For Protocol']
REQUEST_PACKET = REQUEST_STICKS
BUTTON_REQUEST_PACKET = REQUEST_BUTTONS
STATIC_DIR = Path(__file__).parent / 'static'

STATE = {
    'connected': False, 'port': None, 'desc': None,
    'lx': 0.0, 'ly': 0.0, 'rx': 0.0, 'ry': 0.0,
    'cam_raw': RAW_CENTER, 'pps': 0,
    'button_mask': 0x1000, 'mode': 'NORMAL',
    'shutter': False, 'photo_video': False, 'rth': False, 'fn': False,
    'btn_a': False, 'btn_b': False, 'lb': False, 'rb': False,
    'pkt': 0, 'bytes': 0,
}
DISCONNECTED_INPUTS = {
    'lx': 0.0, 'ly': 0.0, 'rx': 0.0, 'ry': 0.0,
    'cam_raw': RAW_CENTER, 'button_mask': 0x1000, 'mode': 'NORMAL',
    'shutter': False, 'photo_video': False, 'rth': False, 'fn': False,
    'btn_a': False, 'btn_b': False, 'lb': False, 'rb': False, 'pps': 0,
}
LOCK = threading.Lock()
STOP = threading.Event()
SERIAL = None
LOGS = deque(maxlen=800)
LOG_SEQ = 0

RECORDER = None
REC_LOCK = threading.Lock()
REC_OPTS_KEYS = ('fps', 'crf', 'window', 'log_only', 'poll')


def rec_start(opts):
    global RECORDER
    with REC_LOCK:
        if RECORDER and RECORDER.recording:
            raise RuntimeError('Registrazione già attiva')
    clean = {k: opts[k] for k in REC_OPTS_KEYS if k in opts}
    rec = Recorder(**clean)
    status = rec.start()
    with REC_LOCK:
        RECORDER = rec
    mode = 'solo log' if status.get('log_only') else f"{status.get('fps')} fps"
    print(f"[VIZ] registrazione avviata: {status.get('base')} ({mode})")
    return status


def rec_stop():
    with REC_LOCK:
        rec = RECORDER
    if rec is None or rec.t0 is None:
        raise RuntimeError('Nessuna registrazione attiva')
    summary = rec.stop()
    print(f"[VIZ] registrazione fermata: {summary['base']} "
          f"({summary['elapsed']}s, {summary['rows']} righe)")
    return summary


def rec_status():
    with REC_LOCK:
        rec = RECORDER
    return rec.status() if rec else {
        'recording': False, 'elapsed': 0, 'rows': 0, 'video_mb': 0, 'base': None,
    }


def log_packet(direction, payload, tag=None):
    global LOG_SEQ
    LOG_SEQ += 1
    LOGS.append({
        'i': LOG_SEQ, 'd': direction,
        'p': len(payload), 'h': payload.hex(' ').upper(),
        't': int(time.time() * 1000),
        'm': tag or ('rx' if direction == '<' else ''),
    })


def raw_to_norm(raw):
    span = (RAW_MAX - RAW_MIN) / 2
    val = (raw - RAW_CENTER) / span
    return max(-1.0, min(1.0, val))


def set_state(**kw):
    with LOCK:
        STATE.update(kw)


def get_state():
    with LOCK:
        snap = dict(STATE)
        snap['ts'] = int(time.time() * 1000)
        return snap


def find_port():
    for port in serial.tools.list_ports.comports(True):
        if any(d in (port.description or '') for d in DJI_PORT_DESCRIPTIONS):
            return port.device, port.description
    return None, None


def serial_loop():
    global SERIAL
    buf = bytearray()
    pkt_count = 0
    pps_window_start = time.time()
    pps_window_pkts = 0
    last_pps = 0
    while not STOP.is_set():
        if not STATE.get('connected'):
            name, desc = find_port()
            if not name:
                set_state(connected=False, port=None, desc=None, **DISCONNECTED_INPUTS)
                STOP.wait(2.0)
                continue
            try:
                ser = serial.Serial(port=name, baudrate=BAUD, timeout=0.01)
            except serial.SerialException:
                set_state(connected=False, port=None, desc=None, **DISCONNECTED_INPUTS)
                STOP.wait(2.0)
                continue
            set_state(connected=True, port=name, desc=desc)
            SERIAL = ser
            print(f'[VIZ] {desc} connessa')
            buf.clear()
            ser.reset_input_buffer()
            ser.write(ENABLE_SIMULATOR)
            log_packet('>', ENABLE_SIMULATOR, tag='simulator')

        try:
            ser.write(REQUEST_PACKET)
            ser.write(BUTTON_REQUEST_PACKET)
            log_packet('>', REQUEST_PACKET)
            log_packet('>', BUTTON_REQUEST_PACKET)
            chunk = ser.read(128)
            if chunk:
                buf.extend(chunk)
                with LOCK:
                    STATE['bytes'] += len(chunk)
            while len(buf) >= 3:
                if buf[0] != 0x55:
                    buf.pop(0)
                    continue
                plen = int.from_bytes(buf[1:3], 'little') & 0b1111111111
                if plen < 3 or plen > 512:
                    buf.pop(0)
                    continue
                if len(buf) < plen:
                    break
                packet = bytes(buf[:plen])
                del buf[:plen]
                log_packet('<', packet)
                if plen == BUTTON_PACKET_LEN:
                    button_state = decode_button_packet(packet)
                    set_state(
                        button_mask=button_state['mask'], mode=button_state['mode'],
                        shutter=button_state['shutter'],
                        photo_video=button_state['photo_video'],
                        rth=button_state['rth'], fn=button_state['fn'],
                    )
                    continue
                if plen != PACKET_LEN:
                    continue
                pkt_count += 1
                pps_window_pkts += 1
                now = time.time()
                if now - pps_window_start >= 1.0:
                    last_pps = round(pps_window_pkts / (now - pps_window_start))
                    pps_window_start = now
                    pps_window_pkts = 0
                set_state(
                    lx=raw_to_norm(int.from_bytes(packet[22:24], 'little')),
                    ly=raw_to_norm(int.from_bytes(packet[19:21], 'little')),
                    rx=raw_to_norm(int.from_bytes(packet[13:15], 'little')),
                    ry=raw_to_norm(int.from_bytes(packet[16:18], 'little')),
                    cam_raw=int.from_bytes(packet[25:27], 'little'),
                    pkt=pkt_count, pps=last_pps,
                )
        except serial.SerialException:
            try:
                ser.close()
            except Exception:
                pass
            SERIAL = None
            set_state(
                connected=False, port=None, desc=None, **DISCONNECTED_INPUTS,
            )
            print('[VIZ] porta persa, attesco riconnessione...')
            STOP.wait(2.0)


def discovery_responder(port=DISCOVERY_PORT):
    s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    try:
        s.bind(('0.0.0.0', port))
    except OSError:
        return
    while not STOP.is_set():
        try:
            msg, addr = s.recvfrom(64)
        except OSError:
            return
        if msg.startswith(b'RCN1C_DISC'):
            s.sendto(b'RCN1C_HERE', addr)


def udp_loop(port):
    sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    sock.bind(('0.0.0.0', port))
    sock.settimeout(0.5)
    print(f'[VIZ] in ascolto UDP su 0.0.0.0:{port} (ponte telefono)')
    pkt_count = 0
    pps_window_start = time.time()
    pps_window_pkts = 0
    last_pps = 0
    last_rx = 0.0
    sender = '-'
    locked_ip = None
    while not STOP.is_set():
        try:
            data, addr = sock.recvfrom(64)
        except socket.timeout:
            data = None
        if isinstance(data, bytes) and locked_ip is not None and addr[0] != locked_ip:
            continue
        if isinstance(data, bytes) and len(data) == 16 and data[:4] == PING_MAGIC:
            sock.sendto(data, addr)
            continue
        now = time.time()
        if data is None or len(data) not in (FRAME.size, FRAME2.size, FRAME3.size):
            if last_rx and now - last_rx > STALE_SECONDS:
                last_rx = 0.0
                set_state(connected=False, **DISCONNECTED_INPUTS)
                last_pps = 0
            continue
        if locked_ip is None:
            locked_ip = addr[0]
            print(f'[VIZ] sorgente bloccata su {locked_ip}')
        decoded = unpack_frame(data) if data is not None else None
        if decoded is None:
            continue
        rx, ry, ly, lx = decoded['rx'], decoded['ry'], decoded['ly'], decoded['lx']
        camv = decoded['camera']
        button_state = decode_button_mask(decoded['button_mask'])
        if not all(RAW_MIN <= v <= RAW_MAX for v in (rx, ry, ly, lx)):
            continue
        sender = addr[0]
        last_rx = now
        pkt_count += 1
        pps_window_pkts += 1
        if now - pps_window_start >= 1.0:
            last_pps = round(pps_window_pkts / (now - pps_window_start))
            pps_window_start = now
            pps_window_pkts = 0
        log_packet('<', data, tag='wifi')
        set_state(
            connected=True, port=f'UDP:{port}', desc=f'telefono {sender}',
            lx=raw_to_norm(lx), ly=raw_to_norm(ly), rx=raw_to_norm(rx), ry=raw_to_norm(ry),
            cam_raw=camv, button_mask=button_state['mask'], mode=button_state['mode'],
            shutter=button_state['shutter'], photo_video=button_state['photo_video'],
            rth=button_state['rth'], fn=button_state['fn'],
            pkt=pkt_count, pps=last_pps, bytes=STATE.get('bytes', 0) + len(data),
        )


def gamepad_loop(source='WiFi'):
    import vgamepad as vg
    try:
        gp = vg.VX360Gamepad()
    except Exception as e:
        print(f'[VIZ] gamepad non disponibile: {e}')
        return
    print(f'[VIZ] Controller Xbox 360 virtuale creato (sorgente {source})')
    sys.path.insert(0, str(Path(__file__).resolve().parent.parent))
    from dji_rcn1c_bridge import Bridge as DjiBridge
    logic = DjiBridge()
    logic.gamepad = gp
    thr = int(0.15 * (RAW_MAX - RAW_CENTER))
    while not STOP.is_set():
        snap = get_state()
        stale = (not snap['connected']) or \
            (time.time() * 1000 - snap['ts'] > STALE_SECONDS * 1000)
        if stale:
            logic.state.update(lx=0, ly=0, rx=0, ry=0)
            logic.camera_raw = RAW_CENTER
            logic.apply_button_mask(0x1000)
        else:
            logic.state.update(
                lx=int(snap['lx'] * AXIS_MAX), ly=int(snap['ly'] * AXIS_MAX),
                rx=int(snap['rx'] * AXIS_MAX), ry=int(snap['ry'] * AXIS_MAX))
            logic.camera_raw = int(snap.get('cam_raw', RAW_CENTER))
            logic.apply_button_mask(int(snap.get('button_mask', 0x1000)))
        logic.update_csc()
        logic.apply_inputs()
        set_state(
            btn_a=logic.camera_raw <= RAW_CENTER - thr,
            btn_b=logic.camera_raw >= RAW_CENTER + thr,
            mode=logic.mode,
            shutter=logic.shutter_pressed,
            photo_video=logic.photo_video_pressed,
            rth=logic.rth_pressed,
            fn=logic.fn_pressed,
            lb=logic.lb_pressed, rb=logic.rb_pressed,
        )
        time.sleep(0.008)
    try:
        gp.reset()
        gp.update()
    except Exception:
        pass
    try:
        gp.reset()
        gp.update()
    except Exception:
        pass


async def ws_handler(ws):
    await ws.send(json.dumps({'t': 'hello', 'http_port': HTTP_PORT}))
    sent_i = 0

    async def reader():
        async for raw in ws:
            try:
                msg = json.loads(raw)
            except (ValueError, TypeError):
                continue
            if msg.get('t') == 'tx':
                hexstr = str(msg.get('h', '')).strip()
                clean = hexstr.replace(' ', '')
                if len(clean) % 2 or len(clean) > 512 or \
                        any(c not in '0123456789abcdefABCDEF' for c in clean):
                    await ws.send(json.dumps(
                        {'t': 'txerr', 'msg': 'hex non valido: usa coppie di cifre esadecimali'}))
                    continue
                payload = bytes.fromhex(clean)
                with LOCK:
                    ser = SERIAL
                if not ser:
                    await ws.send(json.dumps(
                        {'t': 'txerr', 'msg': 'radiocomando non connesso'}))
                    continue
                try:
                    ser.write(payload)
                    log_packet('>', payload, tag='manuale')
                except Exception as e:
                    await ws.send(json.dumps({'t': 'txerr', 'msg': f'invio fallito: {e}'}))

    rtask = asyncio.create_task(reader())
    try:
        while True:
            snap = get_state()
            with LOCK:
                new = [e for e in LOGS if e['i'] > sent_i]
                if new:
                    sent_i = new[-1]['i']
            if len(new) > 60:
                new = new[-60:]
            msg = {'t': 's', **snap, 'rec': rec_status()}
            if new:
                msg['lg'] = new
            await ws.send(json.dumps(msg))
            await asyncio.sleep(0.02)
    except websockets.ConnectionClosed:
        pass
    finally:
        rtask.cancel()


class DashboardHandler(SimpleHTTPRequestHandler):
    def __init__(self, *a, **kw):
        super().__init__(*a, directory=str(STATIC_DIR), **kw)

    def log_message(self, fmt, *args):
        pass

    def _send_json(self, code, obj):
        body = json.dumps(obj, ensure_ascii=False).encode('utf-8')
        self.send_response(code)
        self.send_header('Content-Type', 'application/json; charset=utf-8')
        self.send_header('Content-Length', str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def do_GET(self):
        if self.path == '/api/rec/status':
            self._send_json(200, rec_status())
        elif self.path.startswith('/api/'):
            self._send_json(404, {'ok': False, 'error': 'endpoint sconosciuto'})
        else:
            super().do_GET()

    def do_POST(self):
        if self.path in ('/api/rec/start', '/api/rec/stop'):
            length = int(self.headers.get('Content-Length') or 0)
            opts = {}
            if length:
                try:
                    opts = json.loads(self.rfile.read(length).decode('utf-8') or '{}')
                except (ValueError, UnicodeDecodeError):
                    opts = {}
            if not isinstance(opts, dict):
                opts = {}
            try:
                if self.path == '/api/rec/start':
                    out = rec_start(opts)
                else:
                    out = rec_stop()
                self._send_json(200, {'ok': True, **out})
            except RuntimeError as e:
                self._send_json(409, {'ok': False, 'error': str(e)})
            except (TypeError, ValueError) as e:
                self._send_json(400, {'ok': False, 'error': f'opzioni non valide: {e}'})
            except Exception as e:
                self._send_json(500, {'ok': False, 'error': f'errore inatteso: {e}'})
        else:
            self._send_json(404, {'ok': False, 'error': 'endpoint sconosciuto'})


async def main_async(args):
    loop = asyncio.get_running_loop()

    try:
        httpd = ThreadingHTTPServer(('127.0.0.1', HTTP_PORT), DashboardHandler)
    except OSError as e:
        print(f'[ERRORE] porta HTTP {HTTP_PORT} occupata: {e}')
        print('Chiudi la vecchia scheda/istanza e riprova.')
        return

    threading.Thread(target=httpd.serve_forever, daemon=True).start()
    print(f'[VIZ] Dashboard:  http://127.0.0.1:{HTTP_PORT}')
    print(f'[VIZ] WebSocket:  ws://127.0.0.1:{WS_PORT}')

    try:
        async with websockets.serve(ws_handler, '127.0.0.1', WS_PORT):
            if not args.no_browser:
                threading.Timer(0.8, lambda: webbrowser.open(f'http://127.0.0.1:{HTTP_PORT}')).start()
            print('[VIZ] Ctrl+C per fermare')
            await asyncio.Future()
    except OSError as e:
        print(f'[ERRORE] porta WebSocket {WS_PORT} occupata: {e}')
        print('Chiudi la vecchia istanza e riprova.')


def should_start_gamepad(source, no_gamepad=False, gamepad=False):
    return not no_gamepad and (source == 'udp' or gamepad)


def main():
    parser = argparse.ArgumentParser(description='DJI RC-N1C Control Center')
    parser.add_argument('--source', choices=['serial', 'udp'], default='serial',
                        help='sorgente dati: seriale USB (default) o UDP dal telefono')
    parser.add_argument('--porta', type=int, default=DEFAULT_UDP_PORT,
                        help=f'porta UDP in modalita\' udp (default: {DEFAULT_UDP_PORT})')
    parser.add_argument('--gamepad', action='store_true',
                        help='in modalita\' seriale crea il gamepad virtuale')
    parser.add_argument('--no-gamepad', action='store_true',
                        help='non creare il gamepad virtuale')
    parser.add_argument('--no-browser', action='store_true',
                        help='non aprire il browser automaticamente')
    args = parser.parse_args()

    if args.source == 'udp':
        threading.Thread(target=discovery_responder, daemon=True).start()
        threading.Thread(target=udp_loop, args=(args.porta,), daemon=True).start()
        print('[VIZ] se il telefono non si connette: consenti Python nel firewall Windows '
              '(reti private) oppure crea la regola:')
        print(f'      netsh advfirewall firewall add rule name="RC-N1C WiFi UDP" dir=in action=allow '
              f'protocol=UDP localport={args.porta} profile=private')
    else:
        threading.Thread(target=serial_loop, daemon=True).start()

    if should_start_gamepad(args.source, args.no_gamepad, args.gamepad):
        source_label = 'USB' if args.source == 'serial' else 'WiFi'
        threading.Thread(target=gamepad_loop, args=(source_label,), daemon=True).start()

    try:
        asyncio.run(main_async(args))
    except KeyboardInterrupt:
        pass
    finally:
        STOP.set()
        try:
            rec_stop()
        except RuntimeError:
            pass
        print('\n[VIZ] chiuso.')


if __name__ == '__main__':
    main()

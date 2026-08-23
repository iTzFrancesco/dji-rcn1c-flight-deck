"""
RC-N1C -> WiFi: lato TELEFONO (Android + Termux).

Legge i pacchetti stick dalla porta USB del radiocomando e li inoltra via UDP al PC,
dove wireless/rcn1c_wifi_rx_pc.py crea il gamepad Xbox virtuale.

Setup Termux (una volta sola: installa Termux E Termux:API dallo stesso store, meglio F-Droid):
    pkg update && pkg install python termux-api

Uso (radiocomando ACCESO collegato al telefono con cavo dati USB-C):
    termus-usb -l                                    # annota il percorso, es. /dev/bus/usb/002/003
    termus-usb -r /dev/bus/usb/002/003               # consenti l'accesso alla richiesta Android
    termux-usb -E -e "python rcn1c_phone_tx.py IP_DEL_PC" /dev/bus/usb/002/003

IP_DEL_PC = IP locale del PC (su Windows: ipconfig). Porta opzionale come secondo argomento.
Se il radiocomando non risponde, riprova con l'altra porta USB-C del radiocomando.
Solo libreria standard: niente pip install.
"""
import ctypes
import os
import socket
import struct
import sys
import time
from datetime import datetime

REQUEST_PACKET = bytes.fromhex('550d04330a06eb344006017424')
BUTTON_REQUEST_PACKET = bytes.fromhex('550d04330a06eb344006274060')
ENABLE_SIMULATOR = bytes.fromhex('550e04660a06eb3440062401d9ec')
PACKET_LEN = 38
BUTTON_PACKET_LEN = 58
DEFAULT_PORT = 26789
RAW_CENTER = 1024

PTR = ctypes.sizeof(ctypes.c_void_p)
# Il modulo è importabile anche su Windows per i test del parser; la parte USB
# usa USBDEVFS e viene eseguita soltanto su Android/Linux.
LIBC = ctypes.CDLL(None, use_errno=True) if os.name != 'nt' else None


def _ioc(direction, nr, size):
    return direction | (size << 16) | (0x55 << 8) | nr


def _align(n):
    return (n + PTR - 1) // PTR * PTR


_HDR = _align(12)
USBDEVFS_CONTROL = _ioc(0xC0000000, 0, _HDR + PTR)
USBDEVFS_BULK = _ioc(0xC0000000, 2, _HDR + PTR)
USBDEVFS_CLAIMINTERFACE = _ioc(0x80000000, 15, 4)
USBDEVFS_RELEASEINTERFACE = _ioc(0x80000000, 16, 4)
USBDEVFS_CLEAR_HALT = _ioc(0x80000000, 21, 4)
USBDEVFS_DISCONNECT = 0x00005516

_PTRFMT = 'P' if PTR == 8 else 'I'
_PAD = str(_HDR - 12) + 'x'
BULK_FMT = '=III' + _PAD + _PTRFMT
CTRL_FMT = '=BBHHHI' + _PAD + _PTRFMT
FRAME_FMT = '<IHHHHHHBB'

IFACE_CANDIDATES = (1, 0)
OUT_EP_CANDIDATES = (0x02, 0x01)
IN_EP_CANDIDATES = (0x82, 0x81)


def _ioctl(fd, num, payload=b'', out_size=0):
    buf = ctypes.create_string_buffer(payload, max(len(payload), out_size, 1))
    if LIBC.ioctl(ctypes.c_int(fd), ctypes.c_uint(num), buf) == -1:
        raise OSError(ctypes.get_errno(), os.strerror(ctypes.get_errno()))
    return buf.raw[:out_size] if out_size else b''


def bulk_transfer(fd, ep, timeout_ms, out_data=None, in_len=512):
    if out_data is not None:
        buf = ctypes.create_string_buffer(bytes(out_data), len(out_data))
        length = len(out_data)
    else:
        buf = ctypes.create_string_buffer(in_len)
        length = in_len
    pkt = ctypes.create_string_buffer(
        struct.pack(BULK_FMT, ep, length, timeout_ms, ctypes.addressof(buf)), _HDR + PTR)
    if LIBC.ioctl(ctypes.c_int(fd), ctypes.c_uint(USBDEVFS_BULK), pkt) == -1:
        raise OSError(ctypes.get_errno(), os.strerror(ctypes.get_errno()))
    return buf.raw[:in_len] if out_data is None else b''


def set_dtr(fd, iface):
    pkt = struct.pack(CTRL_FMT, 0x21, 0x22, 0x0003, iface, 0, 1000, 0)
    _ioctl(fd, USBDEVFS_CONTROL, pkt)


def find_endpoints(fd):
    try:
        _ioctl(fd, USBDEVFS_DISCONNECT, b'\x00')
    except OSError:
        pass
    for iface in IFACE_CANDIDATES:
        try:
            _ioctl(fd, USBDEVFS_CLAIMINTERFACE, struct.pack('=I', iface))
        except OSError:
            continue
        for out_ep in OUT_EP_CANDIDATES:
            for in_ep in IN_EP_CANDIDATES:
                try:
                    for ep in (out_ep, in_ep):
                        try:
                            _ioctl(fd, USBDEVFS_CLEAR_HALT, struct.pack('=I', ep))
                        except OSError:
                            pass
                    set_dtr(fd, iface)
                    bulk_transfer(fd, out_ep, 200, out_data=REQUEST_PACKET)
                    resp = read_response(fd, in_ep, 0.3)
                except OSError:
                    continue
                if len(resp) >= PACKET_LEN and resp[0] == 0x55:
                    print(f'[OK] endpoint trovati: iface {iface} OUT 0x{out_ep:02X} IN 0x{in_ep:02X}')
                    return iface, out_ep, in_ep
        try:
            _ioctl(fd, USBDEVFS_RELEASEINTERFACE, struct.pack('=I', iface))
        except OSError:
            pass
    return None


def read_response(fd, in_ep, total_timeout):
    chunks = bytearray()
    deadline = time.time() + total_timeout
    while time.time() < deadline:
        try:
            chunk = bulk_transfer(fd, in_ep, 25)
        except OSError:
            break
        if chunk:
            chunks.extend(chunk)
            if len(chunks) >= PACKET_LEN:
                break
        elif chunks:
            break
    return bytes(chunks)


def parse_frames(buf):
    frames = []
    while len(buf) >= 3:
        if buf[0] != 0x55:
            del buf[0]
            continue
        plen = int.from_bytes(buf[1:3], 'little') & 0b1111111111
        if plen < 3 or plen > 512:
            del buf[0]
            continue
        if len(buf) < plen:
            break
        packet = bytes(buf[:plen])
        del buf[:plen]
        if plen == PACKET_LEN:
            frames.append(('sticks', (
                int.from_bytes(packet[13:15], 'little'),
                int.from_bytes(packet[16:18], 'little'),
                int.from_bytes(packet[19:21], 'little'),
                int.from_bytes(packet[22:24], 'little'),
                int.from_bytes(packet[25:27], 'little'),
            )))
        elif plen == BUTTON_PACKET_LEN:
            mask = int.from_bytes(packet[28:30], 'big')
            mode = (mask & 0x3000) >> 12
            frames.append(('buttons', (mask, mode)))
    return frames


def get_usb_fd(argv):
    env_fd = os.environ.get('TERMUX_USB_FD')
    if env_fd and env_fd.isdigit():
        return int(env_fd)
    for t in reversed(argv):
        if t.isdigit():
            return int(t)
    return None


def main():
    if os.name == 'nt':
        raise SystemExit('[ERRORE] questo trasmettitore USB va eseguito in Termux/Android, non su Windows')
    fd = get_usb_fd(sys.argv[1:])
    positional = [t for t in sys.argv[1:] if not t.isdigit()]
    if not positional:
        raise SystemExit('[ERRORE] manca l\'IP del PC: python rcn1c_phone_tx.py IP_PC [porta]')
    ip = positional[0]
    port = int(positional[1]) if len(positional) > 1 else DEFAULT_PORT
    if fd is None:
        raise SystemExit('[ERRORE] nessun fd USB: usa termux-usb -E -e "python ..." <device>')

    print(f'=== RC-N1C -> WiFi -> {ip}:{port} ===')
    print('Cerco gli endpoint USB del radiocomando...')
    cfg = find_endpoints(fd)
    if not cfg:
        raise SystemExit('[ERRORE] il radiocomando non risponde: '
                         'acceso? cavo dati? prova l\'altra porta USB-C o riavvia il comando')
    iface, out_ep, in_ep = cfg
    bulk_transfer(fd, out_ep, 100, out_data=ENABLE_SIMULATOR)

    sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    dest = (ip, port)
    buf = bytearray()
    seq = 0
    sent = 0
    bad = 0
    last_report = time.time()
    report_count = 0
    values = (RAW_CENTER, RAW_CENTER, RAW_CENTER, RAW_CENTER, RAW_CENTER)
    button_mask = 0x1000
    mode_code = 1
    print('Inoltro attivo. CTRL+C per fermare.')
    try:
        while True:
            try:
                bulk_transfer(fd, out_ep, 100, out_data=REQUEST_PACKET)
                bulk_transfer(fd, out_ep, 100, out_data=BUTTON_REQUEST_PACKET)
                chunk = bulk_transfer(fd, in_ep, 128)
            except OSError as e:
                if e.errno in (9, 19):
                    raise SystemExit('[ERRORE] radiocomando scollegato: '
                                     'rilancia termux-usb dopo averlo ricollegato')
                time.sleep(0.005)
                continue
            if chunk:
                buf.extend(chunk)
                for kind, event in parse_frames(buf):
                    if kind == 'sticks':
                        values = event
                        rx, ry, ly, lx, cam = values
                        frame = struct.pack(
                            FRAME_FMT, seq & 0xFFFFFFFF,
                            rx, ry, ly, lx, cam, button_mask, mode_code, 0,
                        )
                        sock.sendto(frame, dest)
                        seq += 1
                        sent += 1
                        report_count += 1
                    else:
                        button_mask, mode_code = event
            elif len(buf) > 1024:
                bad += 1
                buf.clear()
            now = time.time()
            if now - last_report >= 1.0:
                rate = report_count / (now - last_report)
                report_count = 0
                last_report = now
                print(f'  [{datetime.now().strftime("%H:%M:%S")}] {rate:5.0f} pkt/s '
                      f'(inviati: {sent}, scarti: {bad})', flush=True)
    except KeyboardInterrupt:
        pass
    finally:
        try:
            _ioctl(fd, USBDEVFS_RELEASEINTERFACE, struct.pack('=I', iface))
        except OSError:
            pass
        sock.close()
        print(f'\nPacchetti inviati: {sent}. Uscito pulito.')


if __name__ == '__main__':
    main()

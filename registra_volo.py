"""Registratore sessioni FPV: video dello schermo + log XInput del gamepad virtuale.

Usabile da CLI (registra_volo.py --duration 60) oppure come libreria dalla
dashboard (classe Recorder): video ffmpeg/gdigrab + CSV stick ~100 Hz + JSON
di sincronizzazione, tutto in registrazioni/ con lo stesso prefisso temporale.
"""
import argparse
import ctypes
import ctypes.wintypes as wintypes
import csv
import json
import os
import shutil
import subprocess
import sys
import threading
import time
from datetime import datetime

OUT_DIR = os.path.join(os.path.dirname(os.path.abspath(__file__)), 'registrazioni')

CSV_HEADER = ['t_s', 'lx', 'ly', 'rx', 'ry', 'lt', 'rt', 'buttons', 'names', 'fg_exe', 'fg_title']
MIN_DISK_BYTES = 400 * 1024 * 1024


def _osd_font():
    for name in ('consola.ttf', 'arial.ttf', 'segoeui.ttf'):
        path = os.path.join(os.environ.get('WINDIR', r'C:\Windows'), 'Fonts', name)
        if os.path.exists(path):
            return path.replace('\\', '/').replace(':', '\\:')
    return ''


def ffmpeg_cmd(path, fps, crf, window, osd=True):
    base = ['ffmpeg', '-y', '-hide_banner', '-v', 'error']
    if window:
        base += ['-f', 'gdigrab', '-framerate', str(fps), '-i', 'title=' + window]
    else:
        base += ['-f', 'gdigrab', '-framerate', str(fps), '-i', 'desktop']
    vf = ''
    if osd:
        font = _osd_font()
        if font:
            vf = ("drawtext=fontfile='%s':text='REC %%{pts\\:hms}':fontsize=22:"
                  "fontcolor=white:box=1:boxcolor=black@0.55:boxborderw=8:x=14:y=10" % font)
    filters = [f for f in (vf, 'format=yuv420p') if f]
    base += ['-vf', ','.join(filters),
             '-c:v', 'libx264', '-preset', 'veryfast', '-crf', str(crf), path]
    return base

BUTTON_NAMES = [
    (0x0001, 'up'), (0x0002, 'down'), (0x0004, 'left'), (0x0008, 'right'),
    (0x0010, 'start'), (0x0020, 'back'), (0x0040, 'ls'), (0x0080, 'rs'),
    (0x0100, 'lb'), (0x0200, 'rb'), (0x1000, 'a'), (0x2000, 'b'),
    (0x4000, 'x'), (0x8000, 'y'),
]


class XINPUT_GAMEPAD(ctypes.Structure):
    _fields_ = [
        ('wButtons', ctypes.c_ushort),
        ('bLeftTrigger', ctypes.c_ubyte),
        ('bRightTrigger', ctypes.c_ubyte),
        ('sThumbLX', ctypes.c_short),
        ('sThumbLY', ctypes.c_short),
        ('sThumbRX', ctypes.c_short),
        ('sThumbRY', ctypes.c_short),
    ]


class XINPUT_STATE(ctypes.Structure):
    _fields_ = [('dwPacketNumber', ctypes.c_uint32), ('Gamepad', XINPUT_GAMEPAD)]


_XINPUT = None
_XINPUT_LOCK = threading.Lock()


def load_xinput():
    global _XINPUT
    with _XINPUT_LOCK:
        if _XINPUT is not None:
            return _XINPUT
        for name in ('xinput1_4.dll', 'xinput1_3.dll', 'xinput9_1_0.dll'):
            try:
                dll = ctypes.WinDLL(name)
                get_state = dll.XInputGetState
                get_state.argtypes = [ctypes.c_uint32, ctypes.POINTER(XINPUT_STATE)]
                get_state.restype = ctypes.c_uint32
                _XINPUT = get_state
                return _XINPUT
            except OSError:
                continue
        _XINPUT = False
        return _XINPUT


def buttons_to_names(mask):
    return '|'.join(n for bit, n in BUTTON_NAMES if mask & bit) or '-'


_FG_CACHE = {'hwnd': None, 'exe': '', 'title': ''}
_WIN_APIS = None


def foreground_info():
    """Nome processo e titolo della finestra in primo piano (cache per HWND)."""
    global _WIN_APIS
    if _WIN_APIS is None:
        try:
            _WIN_APIS = (ctypes.windll.user32, ctypes.windll.kernel32)
        except AttributeError:
            _WIN_APIS = False
    if not _WIN_APIS:
        return '', ''
    user32, kernel32 = _WIN_APIS
    hwnd = user32.GetForegroundWindow()
    if hwnd != _FG_CACHE['hwnd']:
        _FG_CACHE['hwnd'] = hwnd
        pid = wintypes.DWORD(0)
        user32.GetWindowThreadProcessId(hwnd, ctypes.byref(pid))
        exe = ''
        proc = kernel32.OpenProcess(0x1000, False, pid.value)
        if proc:
            buf = ctypes.create_unicode_buffer(512)
            size = wintypes.DWORD(512)
            if kernel32.QueryFullProcessImageNameW(proc, 0, buf, ctypes.byref(size)):
                exe = buf.value.rsplit('\\', 1)[-1]
            kernel32.CloseHandle(proc)
        length = user32.GetWindowTextLengthW(hwnd)
        title_buf = ctypes.create_unicode_buffer(length + 1)
        user32.GetWindowTextW(hwnd, title_buf, length + 1)
        _FG_CACHE['exe'] = exe
        _FG_CACHE['title'] = title_buf.value
    return _FG_CACHE['exe'], _FG_CACHE['title']


class Recorder:
    """Registra video + dati stick; sicuro per uso concorrente (dashboard web)."""

    def __init__(self, fps=30, crf=18, window='', log_only=False, poll=0.01, osd=True):
        self.fps = int(fps)
        self.crf = int(crf)
        self.window = str(window or '')
        self.log_only = bool(log_only)
        self.poll = float(poll)
        self.osd = bool(osd)
        self._lock = threading.Lock()
        self._stop = threading.Event()
        self._thread = None
        self._proc = None
        self._csv = None
        self._csv_file = None
        self._ffmpeg_err = []
        self.rows = 0
        self.t0 = None
        self.base = None
        self.video_path = None
        self.csv_path = None
        self.json_path = None
        self.pad_ok = False
        self.video_stopped = False
        self.stop_reason = ''

    @property
    def recording(self):
        return self._thread is not None and self._thread.is_alive()

    def start(self):
        with self._lock:
            if self.recording:
                raise RuntimeError('Registrazione già attiva')
            self._stop.clear()
            self.rows = 0
            self._ffmpeg_err = []
            os.makedirs(OUT_DIR, exist_ok=True)
            stamp = datetime.now().strftime('%Y%m%d_%H%M%S')
            self.base = os.path.join(OUT_DIR, 'volo_' + stamp)
            self.video_path = '' if self.log_only else self.base + '.mp4'
            self.csv_path = self.base + '.csv'
            self.json_path = self.base + '.json'
            get_state = load_xinput()
            state = XINPUT_STATE()
            self.pad_ok = bool(get_state) and get_state(0, ctypes.byref(state)) == 0
            if not self.log_only:
                try:
                    subprocess.run(['ffmpeg', '-version'], capture_output=True, check=True)
                except (OSError, subprocess.CalledProcessError):
                    raise RuntimeError('ffmpeg non trovato nel PATH')
                free = shutil.disk_usage(OUT_DIR).free
                if free < 2 * 1024 ** 3:
                    print(f'[ATT] Poco spazio libero: {free / 1e9:.1f} GB '
                          f'(una registrazione lunga richiede ~0.2 GB/min)')
                self._proc = subprocess.Popen(
                    ffmpeg_cmd(self.video_path, self.fps, self.crf, self.window, self.osd),
                    stdin=subprocess.PIPE, stderr=subprocess.PIPE)
                threading.Thread(target=self._drain_ffmpeg, daemon=True).start()
            else:
                self._proc = None
            self.t0 = time.time()
            meta = {
                'start_epoch': self.t0,
                'start_iso': datetime.fromtimestamp(self.t0).isoformat(timespec='milliseconds'),
                'video': os.path.basename(self.video_path) if self.video_path else None,
                'csv': os.path.basename(self.csv_path),
                'fps': 0 if self.log_only else self.fps,
                'crf': self.crf,
                'window': self.window or 'desktop',
                'poll_hz': round(1 / self.poll),
                'pad_connected': self.pad_ok,
            }
            with open(self.json_path, 'w', encoding='utf-8') as f:
                json.dump(meta, f, indent=2, ensure_ascii=False)
            self._csv_file = open(self.csv_path, 'w', newline='', encoding='utf-8')
            self._csv = csv.writer(self._csv_file)
            self._csv.writerow(CSV_HEADER)
            self._thread = threading.Thread(
                target=self._loop, args=(get_state,), daemon=True)
            self._thread.start()
            return self.status()

    def _drain_ffmpeg(self):
        try:
            for line in self._proc.stderr:
                self._ffmpeg_err.append(line.decode(errors='replace').strip())
                if len(self._ffmpeg_err) > 12:
                    self._ffmpeg_err.pop(0)
        except Exception:
            pass

    def _loop(self, get_state):
        state = XINPUT_STATE()
        next_flush = time.time() + 1.0
        next_watch = time.time() + 2.0
        next_disk = time.time() + 30.0
        while not self._stop.is_set():
            now = time.time()
            try:
                fg_exe, fg_title = foreground_info()
                if get_state and get_state(0, ctypes.byref(state)) == 0:
                    g = state.Gamepad
                    self._csv.writerow([
                        round(now - self.t0, 3),
                        g.sThumbLX, g.sThumbLY, g.sThumbRX, g.sThumbRY,
                        g.bLeftTrigger, g.bRightTrigger,
                        '0x%04X' % g.wButtons, buttons_to_names(g.wButtons),
                        fg_exe, fg_title,
                    ])
                else:
                    self._csv.writerow([round(now - self.t0, 3), 0, 0, 0, 0, 0, 0, '0x0000', '-',
                                        fg_exe, fg_title])
                self.rows += 1
            except (ValueError, OSError):
                break
            if now >= next_flush:
                try:
                    self._csv_file.flush()
                except (ValueError, OSError):
                    break
                next_flush = now + 1.0
            if now >= next_watch:
                next_watch = now + 2.0
                if self._proc and self._proc.poll() is not None and not self.video_stopped:
                    self.video_stopped = True
                    self.stop_reason = 'ffmpeg terminato in anticipo: ' + \
                        ('; '.join(self._ffmpeg_err[-2:]) or 'causa sconosciuta')
                    print('[ATT] ' + self.stop_reason)
            if now >= next_disk:
                next_disk = now + 30.0
                if not self.log_only and not self.video_stopped and \
                        shutil.disk_usage(OUT_DIR).free < MIN_DISK_BYTES:
                    self.video_stopped = True
                    self.stop_reason = 'spazio su disco quasi esaurito, video fermato (log continua)'
                    print('[ATT] ' + self.stop_reason)
                    try:
                        self._proc.stdin.write(b'q')
                        self._proc.stdin.flush()
                    except Exception:
                        pass
            time.sleep(self.poll)
        try:
            self._csv_file.flush()
            self._csv_file.close()
        except Exception:
            pass

    def stop(self):
        with self._lock:
            if self.t0 is None:
                raise RuntimeError('Nessuna registrazione attiva')
            self._stop.set()
        if self._thread:
            self._thread.join(timeout=3)
        proc, self._proc = self._proc, None
        if proc:
            try:
                proc.stdin.write(b'q')
                proc.stdin.flush()
                proc.wait(timeout=5)
            except Exception:
                proc.terminate()
        elapsed = time.time() - self.t0
        paths = [p for p in (self.video_path, self.csv_path, self.json_path) if p]
        summary = {
            'recording': False,
            'elapsed': round(elapsed, 1),
            'rows': self.rows,
            'base': os.path.basename(self.base),
            'files': [os.path.basename(p) for p in paths],
            'pad': self.pad_ok,
            'video_stopped': self.video_stopped,
            'stop_reason': self.stop_reason,
        }
        if self._ffmpeg_err:
            summary['ffmpeg_tail'] = self._ffmpeg_err[-3:]
        self.t0 = None
        return summary

    def status(self):
        if not self.recording:
            return {'recording': False, 'elapsed': 0, 'rows': 0, 'video_mb': 0,
                    'base': os.path.basename(self.base) if self.base else None}
        now = time.time()
        size = 0
        if self.video_path:
            try:
                size = os.path.getsize(self.video_path)
            except OSError:
                size = 0
        elapsed = max(now - self.t0, 0.001)
        return {
            'recording': True, 'elapsed': round(elapsed, 1), 'rows': self.rows,
            'video_mb': round(size / 1e6, 1), 'hz': round(self.rows / elapsed),
            'base': os.path.basename(self.base), 'fps': self.fps, 'crf': self.crf,
            'log_only': self.log_only, 'pad': self.pad_ok,
            'video_stopped': self.video_stopped, 'stop_reason': self.stop_reason,
        }


def main():
    ap = argparse.ArgumentParser(description='Registra volo FPV: video + dati stick.')
    ap.add_argument('--duration', type=float, default=0, help='secondi (0 = finche non premi Q)')
    ap.add_argument('--fps', type=int, default=30)
    ap.add_argument('--crf', type=int, default=18, help='qualita video (17=alta, 23=bassa)')
    ap.add_argument('--window', default='', help='cattura solo la finestra col titolo dato')
    ap.add_argument('--log-only', action='store_true', help='solo dati stick, nessun video')
    ap.add_argument('--no-osd', action='store_true', help='nessun timestamp REC inciso nel video')
    ap.add_argument('--poll', type=float, default=0.01, help='intervallo campionamento stick (s)')
    args = ap.parse_args()

    rec = Recorder(fps=args.fps, crf=args.crf, window=args.window,
                   log_only=args.log_only, poll=args.poll, osd=not args.no_osd)
    try:
        st = rec.start()
    except RuntimeError as e:
        sys.exit('[ERRORE] ' + str(e))
    if not st.get('pad'):
        print('[ATT] Gamepad virtuale non rilevato (bridge spento?): log a zero.')
    print('[REC] ' + (rec.video_path if rec.video_path else 'solo log'))
    print('[REC] Dati: ' + rec.csv_path)
    print('[REC] Ferma con Q' + (f' o dopo {args.duration:g}s' if args.duration else ''))
    next_report = time.time() + 2.0
    try:
        while rec.recording:
            if args.duration and time.time() - rec.t0 >= args.duration:
                break
            try:
                import msvcrt
                if msvcrt.kbhit() and msvcrt.getwch() in ('q', 'Q'):
                    break
            except ImportError:
                pass
            if time.time() >= next_report:
                st = rec.status()
                line = f"[REC] {st['elapsed']:6.0f}s | log {st['hz']:4.0f} Hz"
                if not rec.log_only:
                    line += f" | video {st['video_mb']:6.1f} MB"
                    if st.get('video_stopped'):
                        line += ' | VIDEO FERMO: ' + st.get('stop_reason', '')
                print(line)
                next_report = time.time() + 2.0
            time.sleep(0.2)
    except KeyboardInterrupt:
        pass
    finally:
        summary = rec.stop()
        print(f"[OK] Fermato dopo {summary['elapsed']:.1f}s, {summary['rows']} righe di dati.")
        print('[OK] File: ' + rec.base + '.*')


if __name__ == '__main__':
    main()

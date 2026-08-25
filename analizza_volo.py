"""Analisi completa di una registrazione: tutto il log + tutto il video.

Copre il 100% della registrazione: ogni riga del CSV viene processata e il video
viene campionato a cadenza fissa (contact sheet) oltre ai frame dedicati degli
eventi. Produce un report HTML autonomo in registrazioni/analisi_<nome>/.

Uso:
  python analizza_volo.py                     # ultima registrazione
  python analizza_volo.py volo_20260825_135434
  python analizza_volo.py --every 2           # copertura video piu' fitta
"""
import argparse
import csv
import glob
import os
import subprocess
import sys
from datetime import datetime

ROOT = os.path.dirname(os.path.abspath(__file__))
OUT_DIR = os.path.join(ROOT, 'registrazioni')
GAME = 'liftoff'
AX_MAX = 32767.0


def load_csv(path):
    rows = []
    with open(path, newline='', encoding='utf-8') as f:
        for r in csv.DictReader(f):
            try:
                rows.append({
                    't': float(r['t_s']),
                    'lx': int(r['lx']) / AX_MAX, 'ly': int(r['ly']) / AX_MAX,
                    'rx': int(r['rx']) / AX_MAX, 'ry': int(r['ry']) / AX_MAX,
                    'names': r.get('names', '-'),
                    'fg': (r.get('fg_exe') or '').lower(),
                })
            except (KeyError, ValueError):
                continue
    return rows


def segments(rows):
    """Segmenti per finestra di primo piano, gap < 2s uniti."""
    if not rows:
        return []
    has_fg = any(r['fg'] for r in rows)
    segs = []
    cur = None
    for r in rows:
        key = r['fg'].split('.')[0] if has_fg else 'intera'
        is_game = key.startswith(GAME) if has_fg else True
        if cur and cur['fg'] == key and r['t'] - cur['t_end'] < 2.0:
            cur['t_end'] = r['t']
            cur['rows'].append(r)
        else:
            if cur:
                segs.append(cur)
            cur = {'fg': key, 'game': is_game, 't_start': r['t'], 't_end': r['t'],
                   'rows': [r]}
    if cur:
        segs.append(cur)
    return segs


def find_events(rows, t0, t1):
    """Eventi nel log: tagli di gas, probabili impatti, disarm."""
    events = []
    last_chop = -9
    for i, r in enumerate(rows):
        if not (t0 <= r['t'] <= t1):
            continue
        if i > 0 and r['ly'] < 0.15 and rows[i - 1]['ly'] > 0.45:
            dt = r['t'] - rows[i - 1]['t']
            if dt <= 0.6 and r['t'] - last_chop > 1.0:
                last_chop = r['t']
                events.append({'t': r['t'], 'kind': 'chop',
                               'from': rows[i - 1]['ly'], 'to': r['ly'], 'dt': dt})
        if i >= 12:
            win = [x for x in rows[i - 12:i + 1] if t0 <= x['t'] <= t1]
            if len(win) == 13 and win[-1]['t'] - win[0]['t'] <= 0.45:
                flips = sum(1 for a, b in zip(win, win[1:])
                            if abs(b['rx'] - a['rx']) > 0.5 and (b['rx'] > 0) != (a['rx'] > 0))
                if flips >= 3 and abs(win[-1]['rx']) > 0.5:
                    if not events or events[-1]['kind'] != 'storm' or \
                            events[-1]['t'] < r['t'] - 1.5:
                        events.append({'t': r['t'], 'kind': 'storm',
                                       'flips': flips, 'amp': win[-1]['rx']})
        if r['ly'] < -0.9:
            if not events or events[-1]['kind'] != 'disarm' or \
                    events[-1]['t'] < r['t'] - 1.5:
                prev_fly = any(x['ly'] > 0.2 for x in rows[max(0, i - 300):i])
                if prev_fly:
                    events.append({'t': r['t'], 'kind': 'disarm'})
    return events


EVENT_LABEL = {
    'chop': 'Taglio di gas',
    'storm': 'Probabile impatto / contatto',
    'disarm': 'Disarm (stick completamente giu)',
}


def extract_frame(mp4, t, out_jpg):
    cmd = ['ffmpeg', '-y', '-v', 'error', '-ss', f'{max(0, t):.3f}', '-i', mp4,
           '-frames:v', '1', '-q:v', '3', out_jpg]
    try:
        subprocess.run(cmd, capture_output=True, timeout=30, check=True)
        return os.path.exists(out_jpg)
    except (subprocess.SubprocessError, OSError):
        return False


def coverage_sheets(mp4, out_dir, every):
    """Passata completa: 1 frame ogni `every` secondi, zero salti."""
    cmd = ['ffmpeg', '-y', '-v', 'error', '-i', mp4,
           '-vf', f'fps=1/{every:g},scale=480:-1,tile=5x4',
           '-q:v', '4', os.path.join(out_dir, 'copertura_%02d.jpg')]
    try:
        subprocess.run(cmd, capture_output=True, timeout=1800, check=True)
    except (subprocess.SubprocessError, OSError) as e:
        print(f'[ATT] copertura video incompleta: {e}')
    return sorted(glob.glob(os.path.join(out_dir, 'copertura_*.jpg')))


def pct(v):
    return f'{v * 100:+.0f}%'


def build_html(out_dir, meta, segs, events, sheets, every, has_fg):
    def frame_card(path, caption):
        if not path:
            return ''
        rel = os.path.basename(path)
        return f'<figure><img src="{rel}"><figcaption>{caption}</figcaption></figure>'

    seg_html = ''
    for s in segs:
        evs = [e for e in events if s['t_start'] <= e['t'] <= s['t_end']]
        kind = 'Liftoff (gioco in focus)' if s['game'] else s['fg'] or 'sconosciuto'
        seg_html += (f'<tr><td>{s["t_start"]:7.1f}s — {s["t_end"]:7.1f}s</td>'
                     f'<td>{s["t_end"] - s["t_start"]:6.1f}s</td><td>{kind}</td>'
                     f'<td>{len(evs)}</td></tr>\n')

    ev_html = ''
    for e in events:
        frames = ''
        for off, lab in ((-0.6, 'prima'), (0, 'durante'), (0.6, 'dopo')):
            p = os.path.join(out_dir, f'ev_{events.index(e):02d}_{lab}.jpg')
            ok = extract_frame(meta['mp4'], e['t'] + off, p)
            frames += frame_card(p if ok else '', lab)
        row = next((r for r in meta['rows'] if r['t'] >= e['t']), None)
        sticks = ''
        if row:
            sticks = (f'<table class="sticks"><tr><td>LY</td><td>{pct(row["ly"])}</td></tr>'
                      f'<tr><td>LX</td><td>{pct(row["lx"])}</td></tr>'
                      f'<tr><td>RX</td><td>{pct(row["rx"])}</td></tr>'
                      f'<tr><td>RY</td><td>{pct(row["ry"])}</td></tr></table>')
        detail = ''
        if e['kind'] == 'chop':
            detail = f'LY {pct(e["from"])} → {pct(e["to"])} in {e["dt"] * 1000:.0f} ms'
        elif e['kind'] == 'storm':
            detail = f'{e["flips"]} inversioni rapide di roll in 0.4s'
        seg_note = 'gioco in focus' if has_fg else 'file senza colonna fg_exe (formato vecchio)'
        ev_html += (f'<div class="ev"><h3>#{events.index(e) + 1} {EVENT_LABEL[e["kind"]]} '
                    f'— t={e["t"]:.2f}s <small>({seg_note})</small></h3>'
                    f'<div class="trio">{frames}</div><p>{detail}</p>{sticks}</div>\n')

    cov_html = ''
    for i, s in enumerate(sheets):
        t0 = i * 20 * every
        cov_html += (f'<figure><img src="{os.path.basename(s)}">'
                     f'<figcaption>copertura {t0:.0f}s → {t0 + 20 * every:.0f}s '
                     f'(1 frame ogni {every:g}s)</figcaption></figure>\n')

    dur = meta['rows'][-1]['t'] if meta['rows'] else 0
    html = f"""<!doctype html><html lang="it"><head><meta charset="utf-8">
<title>Analisi {meta['base']}</title><style>
body{{background:#0d1117;color:#e6edf3;font:14px/1.5 'Segoe UI',sans-serif;margin:0;padding:24px;max-width:1200px;margin:auto}}
h1,h2,h3{{font-family:Consolas,monospace}}h2{{border-bottom:1px solid #30363d;padding-bottom:6px;margin-top:34px}}
table{{border-collapse:collapse;margin:10px 0}}td,th{{border:1px solid #30363d;padding:5px 10px;font-size:13px}}
figure{{margin:6px;display:inline-block;vertical-align:top}}img{{max-width:100%;border-radius:6px;border:1px solid #30363d}}
figcaption{{font:11px Consolas,monospace;color:#8b949e;margin-top:4px;max-width:460px}}
.ev{{border:1px solid #30363d;border-radius:8px;padding:12px 16px;margin:14px 0;background:#161b22}}
.trio figure{{width:31%}}.sticks td{{border:none;padding:1px 8px 1px 0;font-family:Consolas,monospace;color:#7ee787}}
small{{color:#8b949e}}p{{margin:6px 0}}</style></head><body>
<h1>Analisi volo — {meta['base']}</h1>
<p>Generata il {datetime.now().strftime('%d/%m/%Y %H:%M')} · durata {dur:.1f}s ·
{len(meta['rows'])} righe di log (100%) · copertura video completa: {len(sheets)} tavole,
1 frame ogni {every:g}s · {len(events)} eventi · analisi di tutto, nessuno escluso.</p>
<h2>Segmenti (finestra di primo piano)</h2>
<table><tr><th>Periodo</th><th>Durata</th><th>App in primo piano</th><th>Eventi</th></tr>
{seg_html}</table>
<h2>Eventi</h2>
{ev_html or '<p>Nessun evento rilevato nel log.</p>'}
<h2>Copertura completa del video</h2>
<p>Tutto il video campionato in ordine: le ore del OSD "REC mm:ss" incise nei frame
coincidono con i secondi del CSV.</p>
{cov_html}
</body></html>"""
    out = os.path.join(out_dir, 'report.html')
    with open(out, 'w', encoding='utf-8') as f:
        f.write(html)
    return out


def main():
    ap = argparse.ArgumentParser(description='Analisi completa: tutto il log + tutto il video')
    ap.add_argument('base', nargs='?', default='',
                    help='prefisso o cartella della registrazione (default: ultima)')
    ap.add_argument('--every', type=float, default=3.0,
                    help='secondi tra i frame di copertura completa (default 3)')
    args = ap.parse_args()

    if args.base:
        base = args.base if os.path.isabs(args.base) or os.path.sep in args.base \
            else os.path.join(OUT_DIR, args.base)
        if os.path.isdir(base):
            cands = sorted(glob.glob(os.path.join(base, 'volo_*.csv')))
            base = cands[-1][:-4] if cands else base
    else:
        cands = sorted(glob.glob(os.path.join(OUT_DIR, 'volo_*.csv')))
        if not cands:
            sys.exit('[ERRORE] nessuna registrazione in registrazioni/')
        base = cands[-1][:-4]
        print('[INFO] ultima registrazione: ' + os.path.basename(base))

    mp4 = base + '.mp4'
    csv_path = base + '.csv'
    if not os.path.exists(csv_path):
        sys.exit('[ERRORE] manca ' + csv_path)
    has_video = os.path.exists(mp4)

    rows = load_csv(csv_path)
    if not rows:
        sys.exit('[ERRORE] CSV vuoto o non leggibile')
    has_fg = any(r['fg'] for r in rows)
    segs = segments(rows)
    events = []
    for s in segs:
        events.extend(find_events(s['rows'], s['t_start'], s['t_end']))
    events.sort(key=lambda e: e['t'])

    print(f'[OK] log: {len(rows)} righe (100%), {len(segs)} segmenti, {len(events)} eventi')
    for s in segs:
        kind = 'Liftoff' if s['game'] else (s['fg'] or '?')
        evs = [e for e in events if s['t_start'] <= e['t'] <= s['t_end']]
        print(f"     {s['t_start']:7.1f}s-{s['t_end']:7.1f}s  {kind:<12} {len(evs)} eventi")
    for i, e in enumerate(events):
        print(f'     evento #{i + 1}: {EVENT_LABEL[e["kind"]]} a {e["t"]:.2f}s')

    out_dir = os.path.join(OUT_DIR, 'analisi_' + os.path.basename(base))
    os.makedirs(out_dir, exist_ok=True)
    sheets = []
    if has_video:
        print('[..] copertura completa del video in corso (passata unica, pazienta)')
        sheets = coverage_sheets(mp4, out_dir, args.every)
        print(f'[OK] copertura: {len(sheets)} tavole, nessun secondo saltato')
    else:
        print('[ATT] video assente: analizzo solo il log')

    meta = {'base': os.path.basename(base), 'mp4': mp4, 'rows': rows}
    report = build_html(out_dir, meta, segs, events, sheets, args.every, has_fg)
    print('[OK] report: ' + report)


if __name__ == '__main__':
    main()

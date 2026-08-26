"""Suite completa: compilazione, trasporti, ricevente e dashboard."""
import os
import py_compile
import subprocess
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
FILES = [
    'rcn1c_protocol.py',
    'rcn1c_transport.py',
    'dji_rcn1c_bridge.py',
    'registra_volo.py',
    'analizza_volo.py',
    'wireless/rcn1c_wifi_rx_pc.py',
    'wireless/rcn1c_phone_tx.py',
    'wireless/button_probe_27.py',
    'wireless/button_live_probe.py',
    'viz_app/controller_viz.py',
]
TESTS = [
    'test_duml_protocol.py',
    'test_transport.py',
    'test_phone_tx.py',
    'test_receiver.py',
    'test_dashboard_udp.py',
    'test_dashboard_modes.py',
    'test_android_app_surface.py',
    'test_android_simulator.py',
    'test_fpv_simulator_integration.py',
]

fails = 0
print('== COMPILAZIONE ==')
for f in FILES:
    try:
        py_compile.compile(os.path.join(ROOT, f), doraise=True)
        print(f'[PASS] {f}')
    except py_compile.PyCompileError as e:
        print(f'[FAIL] {f}: {e}')
        fails += 1

for t in TESTS:
    print(f'== {t} ==')
    command = [sys.executable, '-m', 'pytest', os.path.join(ROOT, 'tests', t)] \
        if t.startswith('test_') and t not in ('test_receiver.py', 'test_dashboard_udp.py') \
        else [sys.executable, os.path.join(ROOT, 'tests', t)]
    r = subprocess.run(command,
                       capture_output=True, text=True, encoding='utf-8', errors='replace',
                       timeout=180, cwd=ROOT)
    tail = (r.stdout or '').strip().splitlines()
    for line in tail:
        if line.startswith('[PASS]') or line.startswith('[FAIL]') or 'TEST_' in line:
            print('   ' + line)
    if r.returncode != 0:
        print('   [FAIL] exit ' + str(r.returncode))
        print('\n'.join('   ' + l for l in tail[-25:]))
        fails += 1

print('== RISULTATO ==')
print('TUTTI_PASS' if fails == 0 else f'FALLITI: {fails}')
sys.exit(1 if fails else 0)

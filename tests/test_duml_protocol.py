import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))
sys.path.insert(0, str(ROOT / 'wireless'))

from duml_scan import build
from button_probe_27 import decode_buttons


def test_build_matches_known_stick_request():
    assert build(0x0A, 0x06, 0x40, 0x06, 0x01) == bytes.fromhex(
        '55 0d 04 33 0a 06 eb 34 40 06 01 74 24'
    )


def test_build_button_request_has_valid_duml_header():
    packet = build(0x0A, 0x06, 0x40, 0x06, 0x27)
    assert packet[:3] == bytes.fromhex('55 0d 04')
    assert packet[8:11] == bytes.fromhex('40 06 27')
    assert len(packet) == 13


def test_decode_button_mask_and_mode():
    packet = bytearray(58)
    packet[28:30] = (0x1060 | 0x0004).to_bytes(2, 'big')
    decoded = decode_buttons(bytes(packet))
    assert decoded['mode'] == 'NORMAL'
    assert decoded['pressed']['SCATTO/REGISTRA']
    assert decoded['pressed']['FOTO/VIDEO']
    assert not decoded['pressed']['FN']

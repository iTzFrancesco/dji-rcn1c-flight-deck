import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / 'wireless'))

from rcn1c_phone_tx import BUTTON_PACKET_LEN, PACKET_LEN, parse_frames


def packet(length, command, mask=None):
    data = bytearray(length)
    data[0] = 0x55
    data[1:3] = length.to_bytes(2, 'little')
    data[9] = command
    if length == PACKET_LEN:
        for offset, value in ((13, 1500), (16, 900), (19, 1024), (22, 700), (25, 1600)):
            data[offset:offset + 2] = value.to_bytes(2, 'little')
    if length == BUTTON_PACKET_LEN:
        data[28:30] = mask.to_bytes(2, 'big')
    return bytes(data)


def test_termux_parser_keeps_sticks_and_buttons_in_one_buffer():
    events = parse_frames(bytearray(
        packet(PACKET_LEN, 0x01) + packet(BUTTON_PACKET_LEN, 0x27, 0x1060)
    ))
    assert events[0] == ('sticks', (1500, 900, 1024, 700, 1600))
    assert events[1] == ('buttons', (0x1060, 1))

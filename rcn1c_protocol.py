"""Protocollo verificato per DJI RC-N1C collegato alla porta VCOM Protocol."""

BAUD = 921600
RAW_MIN, RAW_MAX, RAW_CENTER = 364, 1684, 1024
PACKET_LEN = 38
BUTTON_PACKET_LEN = 58
PUSH_PACKET_LEN = 21

REQUEST_STICKS = bytes.fromhex('550d04330a06eb344006017424')
ENABLE_SIMULATOR = bytes.fromhex('550e04660a06eb3440062401d9ec')
REQUEST_BUTTONS = bytes.fromhex('550d04330a06eb344006274060')

BUTTON_MASKS = {
    'shutter': 0x0060,
    'photo_video': 0x0004,
    'rth': 0x0080,
    'fn': 0x0002,
}
MODE_MASK = 0x3000
MODE_NAMES = {
    0x0000: 'SPORT',
    0x1000: 'NORMAL',
    0x2000: 'CINE',
}


def raw_to_axis(raw, axis_max=32767):
    span = RAW_MAX - RAW_MIN
    value = int((raw - RAW_CENTER) * (2 * axis_max + 1) / span)
    return max(-axis_max, min(axis_max, value))


def decode_button_mask(mask):
    """Decodifica il campo big-endian byte 28:30 del frame da 58 byte."""
    mode_bits = mask & MODE_MASK
    return {
        'mask': mask,
        'mode': MODE_NAMES.get(mode_bits, f'0x{mode_bits:04X}'),
        'mode_code': {0x0000: 0, 0x1000: 1, 0x2000: 2}.get(mode_bits, 255),
        'shutter': (mask & BUTTON_MASKS['shutter']) == BUTTON_MASKS['shutter'],
        'photo_video': bool(mask & BUTTON_MASKS['photo_video']),
        'rth': bool(mask & BUTTON_MASKS['rth']),
        'fn': bool(mask & BUTTON_MASKS['fn']),
    }


def decode_button_packet(packet):
    if len(packet) != BUTTON_PACKET_LEN:
        return None
    return decode_button_mask(int.from_bytes(packet[28:30], 'big'))

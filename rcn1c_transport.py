"""Frame UDP versionato tra ponte Android/Termux e PC."""
import struct

FRAME_V1 = struct.Struct('<IHHHH')
FRAME_V2 = struct.Struct('<IHHHHHBB')
FRAME_V3 = struct.Struct('<IHHHHHHBB')


def pack_frame(seq, rx, ry, ly, lx, camera, button_mask=0x1000, mode_code=1):
    return FRAME_V3.pack(
        seq & 0xFFFFFFFF,
        rx, ry, ly, lx, camera,
        button_mask & 0xFFFF,
        mode_code & 0xFF,
        0,
    )


def unpack_frame(data):
    """Ritorna uno stato normalizzato per frame v1, v2 o v3."""
    if len(data) == FRAME_V3.size:
        seq, rx, ry, ly, lx, camera, mask, mode, _flags = FRAME_V3.unpack(data)
        return {
            'version': 3, 'seq': seq, 'rx': rx, 'ry': ry, 'ly': ly, 'lx': lx,
            'camera': camera, 'button_mask': mask, 'mode_code': mode,
        }
    if len(data) == FRAME_V2.size:
        seq, rx, ry, ly, lx, camera, b28, b29 = FRAME_V2.unpack(data)
        mask = 0x1000 | (0x0002 if b28 & 0x01 else 0)
        return {
            'version': 2, 'seq': seq, 'rx': rx, 'ry': ry, 'ly': ly, 'lx': lx,
            'camera': camera, 'button_mask': mask, 'mode_code': 1,
            'legacy_b29': b29,
        }
    if len(data) == FRAME_V1.size:
        seq, rx, ry, ly, lx = FRAME_V1.unpack(data)
        return {
            'version': 1, 'seq': seq, 'rx': rx, 'ry': ry, 'ly': ly, 'lx': lx,
            'camera': 1024, 'button_mask': 0x1000, 'mode_code': 1,
        }
    return None

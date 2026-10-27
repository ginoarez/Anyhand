"""Verificador por píxeles, sin dependencias (stdlib solamente).

Decodifica lo mínimo del PNG de `screencap` (8 bits, color RGB/RGBA) y
compara dos capturas por fracción de píxeles distintos. Es el mismo criterio
que `Verifier.changeFraction` en la app (ver agent/Verifier.kt).
"""

import struct
import zlib

PNG_SIGNATURE = b"\x89PNG\r\n\x1a\n"


def _read_chunks(data):
    if not data.startswith(PNG_SIGNATURE):
        raise ValueError("no es un PNG")
    pos = len(PNG_SIGNATURE)
    width = height = None
    bit_depth = color_type = None
    idat = bytearray()
    while pos < len(data):
        (length,) = struct.unpack(">I", data[pos:pos + 4])
        kind = data[pos + 4:pos + 8]
        body = data[pos + 8:pos + 8 + length]
        if kind == b"IHDR":
            width, height, bit_depth, color_type, _, _, _ = struct.unpack(
                ">IIBBBBB", body
            )
        elif kind == b"IDAT":
            idat += body
        elif kind == b"IEND":
            break
        pos += 12 + length
    if width is None:
        raise ValueError("PNG sin IHDR")
    if bit_depth != 8 or color_type not in (2, 6):
        raise ValueError(
            f"PNG no soportado: bit_depth={bit_depth} color_type={color_type}"
        )
    return width, height, color_type, bytes(idat)


def _unfilter(width, height, channels, raw):
    stride = width * channels
    out = bytearray(width * height * channels)
    prev = bytearray(stride)
    pos = 0
    for y in range(height):
        f = raw[pos]
        pos += 1
        line = bytearray(raw[pos:pos + stride])
        pos += stride
        if f == 1:  # Sub
            for i in range(channels, stride):
                line[i] = (line[i] + line[i - channels]) & 0xFF
        elif f == 2:  # Up
            for i in range(stride):
                line[i] = (line[i] + prev[i]) & 0xFF
        elif f == 3:  # Average
            for i in range(stride):
                a = line[i - channels] if i >= channels else 0
                line[i] = (line[i] + ((a + prev[i]) >> 1)) & 0xFF
        elif f == 4:  # Paeth
            for i in range(stride):
                a = line[i - channels] if i >= channels else 0
                b = prev[i]
                c = prev[i - channels] if i >= channels else 0
                p = a + b - c
                pa, pb, pc = abs(p - a), abs(p - b), abs(p - c)
                pr = a if (pa <= pb and pa <= pc) else (b if pb <= pc else c)
                line[i] = (line[i] + pr) & 0xFF
        elif f != 0:
            raise ValueError(f"filtro PNG desconocido: {f}")
        out[y * stride:(y + 1) * stride] = line
        prev = line
    return bytes(out)


def decode_png(data):
    """Devuelve (ancho, alto, píxeles RGB como bytes). Ignora el alfa."""
    width, height, color_type, compressed = _read_chunks(data)
    channels = 3 if color_type == 2 else 4
    raw = zlib.decompress(compressed)
    pixels = _unfilter(width, height, channels, raw)
    if channels == 4:
        # Quita el canal alfa: RGBA -> RGB.
        flat = bytearray(width * height * 3)
        src = 0
        for dst in range(0, len(flat), 3):
            flat[dst:dst + 3] = pixels[src:src + 3]
            src += 4
        pixels = bytes(flat)
    return width, height, pixels


def change_fraction(before_png, after_png, max_pixels=20000):
    """Fracción de píxeles distintos entre dos PNG (0.0 a 1.0)."""
    w1, h1, p1 = decode_png(before_png)
    w2, h2, p2 = decode_png(after_png)
    w, h = min(w1, w2), min(h1, h2)
    if w <= 0 or h <= 0:
        return 0.0
    step = max(1, int((w * h / max_pixels) ** 0.5))
    different = total = 0
    for y in range(0, h, step):
        for x in range(0, w, step):
            total += 1
            o1 = (y * w1 + x) * 3
            o2 = (y * w2 + x) * 3
            if p1[o1:o1 + 3] != p2[o2:o2 + 3]:
                different += 1
    return different / total if total else 0.0


def changed_enough(before_png, after_png, threshold=0.005):
    return change_fraction(before_png, after_png) >= threshold

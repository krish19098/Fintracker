"""Synthesises SpendSense's original notification sounds (no samples or recordings used).

Run: python3 tools/gen_sounds.py   (needs numpy and ffmpeg with libvorbis)
Writes app/src/main/res/raw/{coin_clink,coin_kaching,coin_spill,soft_chime}.ogg, each < 1.5 s.
"""
import os
import subprocess
import tempfile
import wave

import numpy as np

SR = 44100
rng = np.random.default_rng(7)  # fixed seed: reproducible output


def t(dur):
    return np.arange(int(SR * dur)) / SR


def clink(freq=2600.0, dur=0.45, amp=1.0, decay=11.0):
    """A small metallic strike: inharmonic partials with fast decay and a tiny noise transient."""
    x = t(dur)
    ratios = [1.0, 1.58, 2.31, 3.07, 4.12]
    weights = [1.0, 0.6, 0.45, 0.3, 0.18]
    s = sum(w * np.sin(2 * np.pi * freq * r * x + rng.uniform(0, 6.28)) * np.exp(-decay * (1 + 0.6 * i) * x)
            for i, (r, w) in enumerate(zip(ratios, weights)))
    click = rng.normal(0, 1, len(x)) * np.exp(-400 * x) * 0.3
    return amp * (s + click)


def bell(freq, dur, amp=1.0, decay=4.0):
    """Soft bell: near-harmonic partials, gentle 8 ms attack."""
    x = t(dur)
    s = sum(w * np.sin(2 * np.pi * freq * r * x) * np.exp(-decay * (1 + 0.5 * i) * x)
            for i, (r, w) in enumerate(zip([1.0, 2.0, 2.76, 4.07], [1.0, 0.35, 0.2, 0.08])))
    attack = np.minimum(1.0, x / 0.008)
    return amp * s * attack


def mix(total, *events):
    out = np.zeros(int(SR * total))
    for start, sig in events:
        i = int(SR * start)
        n = min(len(sig), len(out) - i)
        out[i:i + n] += sig[:n]
    return out


def finish(sig):
    fade = int(SR * 0.03)
    sig[-fade:] *= np.linspace(1, 0, fade)
    return sig / np.max(np.abs(sig)) * 0.7  # about -3 dBFS peak


def calm():  # light coin clink
    return finish(mix(0.6, (0.0, clink(2700, 0.5)), (0.085, clink(3300, 0.4, 0.35, 14))))


def warning():  # double coin drop + soft register "ka-ching"
    return finish(mix(1.25,
                      (0.0, clink(2300, 0.35, 0.9)), (0.16, clink(2050, 0.35, 0.8)),
                      (0.38, bell(1760, 0.85, 0.75, 3.2)), (0.40, bell(2637, 0.8, 0.45, 3.6))))


def over():  # coins spilling, falling in pitch
    events = []
    n = 16
    for k in range(n):
        start = 0.03 + k * 0.07 + rng.uniform(-0.015, 0.015)
        freq = 3600 * (0.55 ** (k / (n - 1))) * rng.uniform(0.95, 1.05)
        events.append((start, clink(freq, 0.3, 1.0 - 0.45 * k / n, 13)))
    x = t(1.3)
    glide = 0.18 * np.sin(2 * np.pi * np.cumsum(np.linspace(900, 420, len(x))) / SR) * np.exp(-2.2 * x)
    return finish(mix(1.4, (0.0, glide), *events))


def chime():  # soft two-note digest chime
    return finish(mix(1.4, (0.0, bell(1318.5, 1.2, 0.8, 3.0)), (0.22, bell(1975.5, 1.15, 0.55, 3.2))))


def write_ogg(sig, path):
    with tempfile.NamedTemporaryFile(suffix=".wav", delete=False) as tmp:
        with wave.open(tmp.name, "wb") as w:
            w.setnchannels(1)
            w.setsampwidth(2)
            w.setframerate(SR)
            w.writeframes((sig * 32767).astype("<i2").tobytes())
        subprocess.run(["ffmpeg", "-y", "-loglevel", "error", "-i", tmp.name, "-c:a", "libvorbis", "-q:a", "4",
                        "-map_metadata", "-1", path], check=True)
    os.unlink(tmp.name)


if __name__ == "__main__":
    out = os.path.join(os.path.dirname(__file__), "..", "app", "src", "main", "res", "raw")
    os.makedirs(out, exist_ok=True)
    for name, fn in [("coin_clink", calm), ("coin_kaching", warning), ("coin_spill", over), ("soft_chime", chime)]:
        write_ogg(fn(), os.path.join(out, f"{name}.ogg"))

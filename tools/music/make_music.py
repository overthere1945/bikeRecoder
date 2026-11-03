"""Makes the bundled sample tracks for videos (V1.11.0): bright, upbeat loops with a known tempo.

Everything is synthesised here (no samples, no downloads), so the tracks are ours to ship. Each track is 150 s
(longer than the longest video, 120 s), starts on a strong beat (no silent intro) and keeps a steady tempo, which
also makes it a fixture for the beat finder.

    python3 make_music.py OUT_DIR      # needs numpy; writes <name>.wav, then encode with ffmpeg (see README.md)
"""

import sys
import wave
from pathlib import Path

import numpy as np

SR = 44_100
SECONDS = 150.0

NOTE = {"C": 0, "C#": 1, "D": 2, "D#": 3, "E": 4, "F": 5, "F#": 6, "G": 7, "G#": 8, "A": 9, "A#": 10, "B": 11}


def freq(midi):
    return 440.0 * 2 ** ((midi - 69) / 12)


def midi(name, octave):
    return 12 * (octave + 1) + NOTE[name]


class Track:
    def __init__(self, bpm, seconds=SECONDS, seed=1):
        self.bpm = bpm
        self.beat = 60.0 / bpm
        self.n = int(seconds * SR)
        self.left = np.zeros(self.n)
        self.right = np.zeros(self.n)
        self.rng = np.random.default_rng(seed)

    @property
    def bars(self):
        return int(self.n / SR / (4 * self.beat))

    def t(self, bar, beat=0.0):
        return (bar * 4 + beat) * self.beat

    def add(self, start_s, sig, gain=1.0, pan=0.0):
        i = int(start_s * SR)
        if i >= self.n:
            return
        sig = sig[: self.n - i] * gain
        self.left[i : i + len(sig)] += sig * (1 - pan) * 0.7
        self.right[i : i + len(sig)] += sig * (1 + pan) * 0.7


def env(n, attack=0.005, decay=0.25, sustain=0.0, release=0.05):
    t = np.arange(n) / SR
    a = np.clip(t / max(attack, 1e-4), 0, 1)
    d = sustain + (1 - sustain) * np.exp(-np.maximum(t - attack, 0) / max(decay, 1e-4))
    out = a * d
    r = int(release * SR)
    if r and r < n:
        out[-r:] *= np.linspace(1, 0, r)
    return out


def lowpass(sig, cutoff):
    """Windowed-sinc FIR low-pass (vectorised; a one-pole loop in Python would take minutes)."""
    taps = 63
    k = np.arange(taps) - (taps - 1) / 2
    h = np.sinc(2 * cutoff / SR * k) * np.hamming(taps)
    return np.convolve(sig, h / h.sum(), mode="same")


def saw(f, dur, harmonics=12, detune=0.0):
    t = np.arange(int(dur * SR)) / SR
    out = np.zeros_like(t)
    for k in range(1, harmonics + 1):
        if f * k > SR / 2.2:
            break
        out += np.sin(2 * np.pi * f * (1 + detune) * k * t) / k
    return out * 0.6


def square(f, dur, harmonics=9):
    t = np.arange(int(dur * SR)) / SR
    out = np.zeros_like(t)
    for k in range(1, harmonics * 2, 2):
        if f * k > SR / 2.2:
            break
        out += np.sin(2 * np.pi * f * k * t) / k
    return out * 0.7


def sine(f, dur):
    return np.sin(2 * np.pi * f * np.arange(int(dur * SR)) / SR)


def pluck(f, dur, bright=0.5):
    """A guitar/ukulele-like pluck: a few decaying harmonics."""
    t = np.arange(int(dur * SR)) / SR
    out = np.zeros_like(t)
    for k in range(1, 9):
        out += np.sin(2 * np.pi * f * k * t) * np.exp(-t * (3 + k * (6 - 4 * bright))) * (0.8 ** k)
    return out * env(len(t), 0.002, dur, 0, 0.02)


def kick(dur=0.35):
    t = np.arange(int(dur * SR)) / SR
    f = 50 + 110 * np.exp(-t * 30)
    phase = 2 * np.pi * np.cumsum(f) / SR
    return np.sin(phase) * np.exp(-t * 9) * 1.0


def snare(rng, dur=0.22):
    n = int(dur * SR)
    t = np.arange(n) / SR
    noise = rng.standard_normal(n) * np.exp(-t * 22)
    tone = np.sin(2 * np.pi * 190 * t) * np.exp(-t * 30)
    return (noise * 0.5 + tone * 0.5) * 0.8


def clap(rng, dur=0.2):
    n = int(dur * SR)
    t = np.arange(n) / SR
    burst = np.exp(-((t * 1000) % 11) / 2.5) * (t < 0.03) + np.exp(-t * 25) * (t >= 0.01)
    noise = rng.standard_normal(n)
    hp = noise - np.concatenate(([0], noise[:-1]))
    return hp * burst * 0.35


def hat(rng, dur=0.05, open_=False):
    n = int((0.22 if open_ else dur) * SR)
    t = np.arange(n) / SR
    noise = rng.standard_normal(n)
    hp = noise - np.concatenate(([0], noise[:-1]))
    return hp * np.exp(-t * (14 if open_ else 70)) * 0.22


def shaker(rng, dur=0.08):
    n = int(dur * SR)
    t = np.arange(n) / SR
    noise = rng.standard_normal(n)
    hp = noise - np.concatenate(([0], noise[:-1]))
    return hp * np.sin(np.pi * t / dur) * 0.12


def chord_notes(root, quality):
    steps = [0, 4, 7] if quality == "" else [0, 3, 7]
    return [root + s for s in steps]


def progression(key, octave, names):
    """names like ["I", "V", "vi", "IV"] in a major key."""
    degree = {"I": (0, ""), "ii": (2, "m"), "iii": (4, "m"), "IV": (5, ""), "V": (7, ""), "vi": (9, "m")}
    base = midi(key, octave)
    return [chord_notes(base + degree[n][0], degree[n][1]) for n in names]


def delay_reverb(left, right, beat, mix=0.18):
    d = int(beat * 0.75 * SR)
    out_l, out_r = left.copy(), right.copy()
    for k, g in enumerate([mix, mix * 0.55, mix * 0.3], start=1):
        sh = d * k
        out_l[sh:] += right[:-sh] * g
        out_r[sh:] += left[:-sh] * g
    return out_l, out_r


def finish(track, path):
    l, r = delay_reverb(track.left, track.right, track.beat)
    peak = max(np.abs(l).max(), np.abs(r).max())
    l, r = np.tanh(l / peak * 1.6) * 0.89, np.tanh(r / peak * 1.6) * 0.89
    fade = int(3 * SR)
    l[-fade:] *= np.linspace(1, 0, fade)
    r[-fade:] *= np.linspace(1, 0, fade)
    data = np.empty(track.n * 2, dtype=np.int16)
    data[0::2] = (l * 32767).astype(np.int16)
    data[1::2] = (r * 32767).astype(np.int16)
    with wave.open(str(path), "wb") as w:
        w.setnchannels(2)
        w.setsampwidth(2)
        w.setframerate(SR)
        w.writeframes(data.tobytes())


def drums(tr, bar, style, full):
    rng = tr.rng
    for b in range(4):
        if style in ("four", "drive") or b in (0, 2):
            tr.add(tr.t(bar, b), kick(), 0.65)
        if style == "bounce" and b == 2:
            tr.add(tr.t(bar, b + 0.5), kick(), 0.45)
        if b in (1, 3):
            tr.add(tr.t(bar, b), clap(rng) if style == "four" else snare(rng), 0.7 if full else 0.45)
        tr.add(tr.t(bar, b + 0.5), hat(rng, open_=style == "four"), 0.8, pan=0.3)
        if full:
            tr.add(tr.t(bar, b), hat(rng), 0.45, pan=-0.3)
            if style != "four":
                tr.add(tr.t(bar, b + 0.25), shaker(rng), 0.8, pan=0.5)
                tr.add(tr.t(bar, b + 0.75), shaker(rng), 0.8, pan=0.5)


def sunny_pedal(out):
    """햇살 페달: 124 BPM, C major, four-on-the-floor, plucked arpeggio and a bright lead."""
    tr = Track(124, seed=1)
    chords = progression("C", 4, ["I", "V", "vi", "IV"])
    lead = [72, 74, 76, 79, 76, 74, 72, 74, 71, 72, 74, 76, 77, 76, 74, 72]
    for bar in range(tr.bars):
        full = bar >= 4
        ch = chords[bar % 4]
        drums(tr, bar, "four", full)
        root = ch[0] - 24
        for e in range(8):  # driving eighth-note bass
            n = root + (12 if e % 2 else 0)
            tr.add(tr.t(bar, e * 0.5), lowpass(saw(freq(n), tr.beat * 0.45, 8), 900) * env(int(tr.beat * 0.45 * SR), 0.003, 0.2, 0.3), 0.35)
        arp = ch + [ch[0] + 12]
        for s in range(16):
            n = arp[[0, 1, 2, 3, 2, 1, 2, 3][s % 8]] + 12
            tr.add(tr.t(bar, s * 0.25), pluck(freq(n), tr.beat * 0.5, 0.7), 0.34, pan=-0.4 if s % 2 else 0.4)
        for n in ch:  # soft pad
            tr.add(tr.t(bar), lowpass(saw(freq(n), 4 * tr.beat, 6, 0.003), 1800) * env(int(4 * tr.beat * SR), 0.15, 8, 0.7, 0.3), 0.06)
        if full and (bar // 8) % 2 == 1:
            for k in range(4):
                n = lead[(bar % 4) * 4 + k]
                tr.add(tr.t(bar, k), lowpass(square(freq(n), tr.beat * 0.9), 3500) * env(int(tr.beat * 0.9 * SR), 0.01, 0.4, 0.5), 0.24)
    finish(tr, out / "sunny_pedal.wav")


def wind_road(out):
    """바람길: 112 BPM, G major, ukulele strums, shaker and a bouncy bass."""
    tr = Track(112, seed=2)
    chords = progression("G", 3, ["I", "vi", "IV", "V"])
    whistle = [79, 81, 83, 86, 83, 81, 79, 76, 78, 79, 81, 83, 81, 79, 78, 74]
    for bar in range(tr.bars):
        full = bar >= 2
        ch = chords[bar % 4]
        drums(tr, bar, "bounce", full)
        root = ch[0] - 12
        for b, n in [(0, root), (1.5, root + 7), (2, root + 12), (3, root + 7)]:
            tr.add(tr.t(bar, b), lowpass(sine(freq(n), tr.beat * 0.6) + 0.3 * saw(freq(n), tr.beat * 0.6, 4), 700) * env(int(tr.beat * 0.6 * SR), 0.004, 0.25, 0.2), 0.38)
        for b, down in [(0, 1), (1, 1), (1.5, 0), (2.5, 0), (3, 1), (3.5, 0)]:  # strum pattern
            notes = ch + [ch[0] + 12] if down else list(reversed(ch + [ch[0] + 12]))
            for k, n in enumerate(notes):
                tr.add(tr.t(bar, b) + k * 0.012, pluck(freq(n + 12), tr.beat * 0.9, 0.45), 0.2, pan=0.2)
        if full and bar % 8 >= 4:
            for k in range(4):
                n = whistle[(bar % 4) * 4 + k]
                d = tr.beat * 0.8
                vib = np.sin(2 * np.pi * freq(n) * np.arange(int(d * SR)) / SR + 0.15 * np.sin(2 * np.pi * 5.5 * np.arange(int(d * SR)) / SR))
                tr.add(tr.t(bar, k), vib * env(int(d * SR), 0.03, 0.5, 0.6), 0.17, pan=-0.3)
    finish(tr, out / "wind_road.wav")


def over_the_hill(out):
    """언덕 너머: 132 BPM, D major, driving drums, synth stabs and a soaring lead."""
    tr = Track(132, seed=3)
    chords = progression("D", 4, ["vi", "IV", "I", "V"])
    lead = [74, 76, 78, 81, 83, 81, 78, 76, 78, 81, 85, 83, 81, 78, 76, 78]
    for bar in range(tr.bars):
        full = bar >= 4
        ch = chords[bar % 4]
        drums(tr, bar, "drive", full)
        root = ch[0] - 24
        for e in range(16):  # rolling sixteenth bass
            n = root + [0, 0, 12, 0][e % 4]
            tr.add(tr.t(bar, e * 0.25), lowpass(saw(freq(n), tr.beat * 0.22, 6), 1100) * env(int(tr.beat * 0.22 * SR), 0.002, 0.08, 0.2), 0.15)
        for b in (0, 0.75, 1.5, 2.5, 3.25):  # off-beat stabs
            for n in ch:
                tr.add(tr.t(bar, b), lowpass(saw(freq(n + 12), tr.beat * 0.3, 10, 0.004), 2600) * env(int(tr.beat * 0.3 * SR), 0.003, 0.1, 0.1), 0.24, pan=0.35)
        if full and (bar // 4) % 3 != 0:
            for k in range(4):
                n = lead[(bar % 4) * 4 + k]
                d = tr.beat * 0.95
                tone = saw(freq(n), d, 10, 0.002) * 0.5 + saw(freq(n), d, 10, -0.002) * 0.5
                tr.add(tr.t(bar, k), lowpass(tone, 4200) * env(int(d * SR), 0.01, 0.5, 0.6), 0.22, pan=-0.25)
    finish(tr, out / "over_the_hill.wav")


if __name__ == "__main__":
    out = Path(sys.argv[1])
    out.mkdir(parents=True, exist_ok=True)
    for make in (sunny_pedal, wind_road, over_the_hill):
        make(out)
        print("made", make.__name__)

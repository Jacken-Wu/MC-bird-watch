#!/usr/bin/env python3
"""白鹭音效程序化合成(M2b)。

用 numpy/scipy 合成白鹭类粗哑嘎叫与扇翅风声,ffmpeg 编码 OGG。
确定性种子 → 重复运行结果稳定,可迭代调参。

依赖:Python 3.11 + numpy + scipy + ffmpeg(路径见 FFMPEG)。
用法:python tools/gen_sounds.py
输出:src/main/resources/assets/birdwatch/sounds/heron/*.ogg(共 13 个)
M6 真实录音采集后,直接替换同名文件即可,代码无需改动。
"""

import subprocess
import tempfile
from pathlib import Path

import numpy as np
import scipy.io.wavfile
import scipy.signal

SR = 44100
FFMPEG = r"D:\files\chocolatey\bin\ffmpeg.exe"
OUT = Path(__file__).resolve().parents[1] / "src" / "main" / "resources" / "assets" / "birdwatch" / "sounds" / "heron"


def env(n, attack_s, decay_s):
    """线性起音 + 指数衰减包络"""
    x = np.ones(n)
    a = min(int(attack_s * SR), n)
    if a > 0:
        x[:a] = np.linspace(0.0, 1.0, a)
    rest = n - a
    if rest > 0:
        k = min(int(decay_s * SR), rest)
        x[a:a + k] *= np.exp(-np.linspace(0.0, 4.0, k))
    return x


def saw_glide(f0, f1, dur, jitter, seed):
    """相位积分锯齿波:f0→f1 滑音 + 高斯音高抖动"""
    rng = np.random.default_rng(seed)
    n = int(dur * SR)
    t = np.arange(n) / SR
    f = np.linspace(f0, f1, n) + rng.normal(0.0, jitter, n)
    phase = 2.0 * np.pi * np.cumsum(f) / SR
    return 2.0 * ((phase / (2.0 * np.pi)) % 1.0) - 1.0


def bandpass(x, lo, hi):
    sos = scipy.signal.butter(2, [lo, hi], btype="band", fs=SR, output="sos")
    return scipy.signal.sosfilt(sos, x)


def normalize(x, peak=0.5):
    x = np.asarray(x, dtype=np.float64)
    m = np.max(np.abs(x)) + 1e-9
    return x / m * peak


def croak(f0, f1, n_bursts, burst_len, gap, formant, seed, attack=0.03, decay=0.10):
    """粗哑嘎叫:多个锯齿 burst(音高抖动+下滑),过共振峰带通"""
    parts = []
    for i in range(n_bursts):
        b = saw_glide(f0, f1, burst_len, jitter=8.0, seed=seed + i * 7)
        b *= env(len(b), attack, decay)
        parts.append(b)
        if i < n_bursts - 1:
            parts.append(np.zeros(int(gap * SR)))
    x = np.concatenate(parts)
    x = bandpass(x, max(formant - 400, 50), formant + 400)
    # 整体淡出
    fade = int(0.05 * SR)
    if len(x) > fade:
        x[-fade:] *= np.linspace(1.0, 0.0, fade)
    return normalize(x)


def hurt_sound(seed, dur=0.4):
    """短促尖锐的受伤嘎叫:高频下滑 + 噪声起音"""
    rng = np.random.default_rng(seed)
    n = int(dur * SR)
    x = saw_glide(480, 240, dur, jitter=15.0, seed=seed) * env(n, 0.02, 0.18)
    noise_n = int(0.04 * SR)
    noise = rng.normal(0.0, 1.0, noise_n) * np.linspace(1.0, 0.0, noise_n)
    x[:noise_n] += bandpass(noise, 800, 3000) * 0.5
    x = bandpass(x, 500, 1400)
    return normalize(x)


def death_sound(seed):
    """垂死长叫:两段下滑嘎叫 + 沙哑噪声尾"""
    rng = np.random.default_rng(seed)
    dur1, gap, dur2 = 0.30, 0.12, 0.55
    x1 = saw_glide(380, 220, dur1, jitter=12.0, seed=seed) * env(int(dur1 * SR), 0.02, 0.12)
    x2 = saw_glide(220, 140, dur2, jitter=10.0, seed=seed + 3) * env(int(dur2 * SR), 0.02, 0.30)
    tail_n = int(0.20 * SR)
    tail = rng.normal(0.0, 1.0, tail_n) * np.linspace(1.0, 0.0, tail_n)
    x = np.concatenate([x1, np.zeros(int(gap * SR)), x2, bandpass(tail, 400, 1200) * 0.4])
    x = bandpass(x, 300, 1200)
    return normalize(x)


def flap_sound(seed, dur=0.20):
    """扇翅:带通噪声 swoosh + 低频噗声"""
    rng = np.random.default_rng(seed)
    n = int(dur * SR)
    x = rng.normal(0.0, 1.0, n) * env(n, 0.03, 0.12)
    x = bandpass(x, 600, 2500)
    thump_n = int(0.06 * SR)
    t = np.arange(thump_n) / SR
    x[:thump_n] += np.sin(2.0 * np.pi * 70.0 * t) * np.exp(-t * 40.0) * 0.4
    return normalize(x)


def write_ogg(name, samples):
    """WAV 临时文件 → OGG vorbis(q4, 44.1k mono)→ 删除临时文件"""
    OUT.mkdir(parents=True, exist_ok=True)
    wav_path = OUT / f"{name}.wav"
    ogg_path = OUT / f"{name}.ogg"
    scipy.io.wavfile.write(wav_path, SR, (samples * 32767).astype(np.int16))
    subprocess.run(
        [FFMPEG, "-y", "-hide_banner", "-loglevel", "error",
         "-i", str(wav_path), "-c:a", "libvorbis", "-q:a", "4", str(ogg_path)],
        check=True)
    wav_path.unlink()
    print(f"  {ogg_path.name}  ({len(samples) / SR:.2f}s)")


def main():
    print(f"生成白鹭音效 → {OUT}")
    # 鸣叫(听声辨位):粗哑多 burst 嘎叫
    write_ogg("ambient1", croak(280, 250, 2, 0.16, 0.10, 900, seed=1))
    write_ogg("ambient2", croak(290, 260, 3, 0.20, 0.12, 950, seed=2))
    write_ogg("ambient3", croak(270, 240, 4, 0.18, 0.10, 880, seed=3))
    # 受惊:更快更密更高音
    write_ogg("scared1", croak(360, 300, 4, 0.12, 0.06, 1100, seed=4))
    write_ogg("scared2", croak(370, 310, 5, 0.10, 0.05, 1150, seed=5))
    write_ogg("scared3", croak(350, 290, 6, 0.11, 0.05, 1080, seed=6))
    # 受伤:短促尖锐
    write_ogg("hurt1", hurt_sound(seed=7, dur=0.35))
    write_ogg("hurt2", hurt_sound(seed=8, dur=0.45))
    # 死亡:长下滑 + 沙哑尾
    write_ogg("death1", death_sound(seed=9))
    write_ogg("death2", death_sound(seed=10))
    # 扇翅:风声
    write_ogg("flap1", flap_sound(seed=11, dur=0.18))
    write_ogg("flap2", flap_sound(seed=12, dur=0.22))
    write_ogg("flap3", flap_sound(seed=13, dur=0.20))
    print("完成。M6 换真实录音时直接替换同名 ogg。")


if __name__ == "__main__":
    main()

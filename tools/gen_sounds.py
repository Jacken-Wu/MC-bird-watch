#!/usr/bin/env python3
"""鸟音效程序化合成(M2b 白鹭 / M4a 麻雀、山雀)。

用 numpy/scipy 按物种合成叫声与扇翅风声,ffmpeg 编码 OGG。
确定性种子 → 重复运行结果稳定,可迭代调参。

依赖:Python 3.11 + numpy + scipy + ffmpeg(路径见 FFMPEG)。
用法:python tools/gen_sounds.py
输出:sounds/little_egret/、sounds/sparrow/、sounds/tit/ 各 13 个 ogg
M6 真实录音采集后,直接替换同名文件即可,代码无需改动。
"""

import subprocess
from pathlib import Path

import numpy as np
import scipy.io.wavfile
import scipy.signal

SR = 44100
FFMPEG = r"D:\files\chocolatey\bin\ffmpeg.exe"
BASE = Path(__file__).resolve().parents[1] / "src" / "main" / "resources" / "assets" / "birdwatch" / "sounds"
OUT_EGRET = BASE / "little_egret"
OUT_SPARROW = BASE / "sparrow"
OUT_TIT = BASE / "tit"


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


def write_ogg(name, samples, out_dir):
    """WAV 临时文件 → OGG vorbis(q4, 44.1k mono)→ 删除临时文件"""
    out_dir.mkdir(parents=True, exist_ok=True)
    wav_path = out_dir / f"{name}.wav"
    ogg_path = out_dir / f"{name}.ogg"
    scipy.io.wavfile.write(wav_path, SR, (samples * 32767).astype(np.int16))
    subprocess.run(
        [FFMPEG, "-y", "-hide_banner", "-loglevel", "error",
         "-i", str(wav_path), "-c:a", "libvorbis", "-q:a", "4", str(ogg_path)],
        check=True)
    wav_path.unlink()
    print(f"  {out_dir.name}/{ogg_path.name}  ({len(samples) / SR:.2f}s)")


def cheep(f0, f1, dur, seed, formant, bw=1000, noise=0.25):
    """单声"叽":音高上滑-下滑 + 谐波 + 噪声,带通成形。

    麻雀/山雀通用短促音节:f0→f1 快速滑音(峰值在 1/4 处),轻微音高抖动,
    叠 2 次谐波与噪声增加颗粒感。
    """
    rng = np.random.default_rng(seed)
    n = int(dur * SR)
    t = np.arange(n) / SR
    peak = max(f0, f1) * 1.2
    f = np.interp(t, [0.0, 0.25 * dur, dur], [f0, peak, f1]) + rng.normal(0.0, 30.0, n)
    phase = 2.0 * np.pi * np.cumsum(f) / SR
    x = np.sin(phase) + 0.5 * np.sin(2.0 * phase)
    x += noise * rng.normal(0.0, 1.0, n)
    x = bandpass(x, max(formant - bw, 100), formant + bw)
    x *= env(n, 0.004, 0.09)
    return normalize(x)


def chirp_series(n_notes, f0, f1, note_len, gap, formant, seed, pitch_var=80, noise=0.25):
    """一串"叽叽喳喳":连续多个 cheep,音高逐次轻微随机漂移"""
    rng = np.random.default_rng(seed)
    parts = []
    for i in range(n_notes):
        df = rng.normal(0.0, pitch_var)
        parts.append(cheep(f0 + df, f1 + df, note_len, seed + i * 17, formant, noise=noise))
        parts.append(np.zeros(int(gap * SR)))
    return normalize(np.concatenate(parts))


def whistle(f0, f1, dur, seed, vib=5.0, harm=0.12):
    """清脆哨音:正弦滑音 + 颤音 + 少量二次谐波(山雀特征音)"""
    rng = np.random.default_rng(seed)
    n = int(dur * SR)
    t = np.arange(n) / SR
    f = np.linspace(f0, f1, n) + vib * np.sin(2.0 * np.pi * 7.5 * t)
    phase = 2.0 * np.pi * np.cumsum(f) / SR
    x = np.sin(phase) + harm * np.sin(2.0 * phase)
    x = bandpass(x, 2500, 6000)
    x *= env(n, 0.01, 0.10)
    return normalize(x)


def tit_phrase(seed, tee=3900.0, cher=3200.0, repeats=2, gap=0.07):
    """山雀双音节 'tee-cher' 短语:高音 tee 接低音 cher,重复 repeats 遍"""
    rng = np.random.default_rng(seed)
    parts = []
    for i in range(repeats):
        parts.append(whistle(tee + rng.normal(0.0, 60), tee * 0.97, 0.12, seed + i * 13))
        parts.append(np.zeros(int(0.015 * SR)))
        parts.append(whistle(cher + rng.normal(0.0, 60), cher * 0.96, 0.10, seed + i * 13 + 7))
        parts.append(np.zeros(int(gap * SR)))
    return normalize(np.concatenate(parts))


def sparrow_scared(seed):
    """受惊:更快更密更尖的叽喳串"""
    return chirp_series(8, 3600, 4000, 0.055, 0.035, 4300, seed, pitch_var=120, noise=0.35)


def tit_scared(seed):
    """受惊:急促高音 whistle 串"""
    rng = np.random.default_rng(seed)
    parts = []
    for i in range(7):
        parts.append(whistle(4400 + rng.normal(0.0, 80), 4200, 0.07, seed + i * 11, vib=10.0))
        parts.append(np.zeros(int(0.03 * SR)))
    return normalize(np.concatenate(parts))


def sparrow_hurt(seed, dur=0.16):
    """受伤:短促尖锐下滑吱声"""
    return cheep(3600, 2100, dur, seed, formant=2800, bw=900, noise=0.4)


def tit_hurt(seed, dur=0.15):
    """受伤:短促尖锐下滑哨(带噪声)*"""
    x = cheep(3300, 1900, dur, seed, formant=2500, bw=800, noise=0.35)
    return normalize(x)


def sparrow_death(seed):
    """垂死:两段下滑叽 + 噪声尾"""
    rng = np.random.default_rng(seed)
    x1 = cheep(3400, 2400, 0.16, seed, formant=2900, noise=0.3)
    gap = np.zeros(int(0.09 * SR))
    x2 = cheep(2400, 1500, 0.30, seed + 3, formant=2000, noise=0.4)
    tail_n = int(0.15 * SR)
    tail = rng.normal(0.0, 1.0, tail_n) * np.linspace(1.0, 0.0, tail_n)
    tail = bandpass(tail, 1500, 3500) * 0.3
    return normalize(np.concatenate([x1, gap, x2, tail]))


def tit_death(seed):
    """垂死:下滑长哨 + 噪声尾"""
    rng = np.random.default_rng(seed)
    x1 = whistle(3600, 3000, 0.22, seed, vib=8.0)
    gap = np.zeros(int(0.08 * SR))
    x2 = whistle(3000, 1900, 0.42, seed + 5, vib=10.0)
    tail_n = int(0.12 * SR)
    tail = rng.normal(0.0, 1.0, tail_n) * np.linspace(1.0, 0.0, tail_n)
    tail = bandpass(tail, 1600, 3200) * 0.25
    return normalize(np.concatenate([x1, gap, x2, tail]))


def small_flap(seed, dur=0.12):
    """小鸟扇翅:高频柔和 swoosh + 轻微噗声(比白鹭轻、高)"""
    rng = np.random.default_rng(seed)
    n = int(dur * SR)
    x = rng.normal(0.0, 1.0, n) * env(n, 0.02, 0.08)
    x = bandpass(x, 1500, 4500)
    thump_n = int(0.04 * SR)
    t = np.arange(thump_n) / SR
    x[:thump_n] += np.sin(2.0 * np.pi * 120.0 * t) * np.exp(-t * 50.0) * 0.25
    return normalize(x, peak=0.35)


def main():
    print(f"生成音效 → {BASE}")
    # ── 白鹭:粗哑嘎叫(见 croak/hurt_sound/death_sound/flap_sound)──
    print("[little_egret]")
    write_ogg("ambient1", croak(280, 250, 2, 0.16, 0.10, 900, seed=1), OUT_EGRET)
    write_ogg("ambient2", croak(290, 260, 3, 0.20, 0.12, 950, seed=2), OUT_EGRET)
    write_ogg("ambient3", croak(270, 240, 4, 0.18, 0.10, 880, seed=3), OUT_EGRET)
    # 受惊:更快更密更高音
    write_ogg("scared1", croak(360, 300, 4, 0.12, 0.06, 1100, seed=4), OUT_EGRET)
    write_ogg("scared2", croak(370, 310, 5, 0.10, 0.05, 1150, seed=5), OUT_EGRET)
    write_ogg("scared3", croak(350, 290, 6, 0.11, 0.05, 1080, seed=6), OUT_EGRET)
    # 受伤:短促尖锐
    write_ogg("hurt1", hurt_sound(seed=7, dur=0.35), OUT_EGRET)
    write_ogg("hurt2", hurt_sound(seed=8, dur=0.45), OUT_EGRET)
    # 死亡:长下滑 + 沙哑尾
    write_ogg("death1", death_sound(seed=9), OUT_EGRET)
    write_ogg("death2", death_sound(seed=10), OUT_EGRET)
    # 扇翅:风声
    write_ogg("flap1", flap_sound(seed=11, dur=0.18), OUT_EGRET)
    write_ogg("flap2", flap_sound(seed=12, dur=0.22), OUT_EGRET)
    write_ogg("flap3", flap_sound(seed=13, dur=0.20), OUT_EGRET)
    # ── 麻雀:叽喳(短促 cheep 串)──
    print("[sparrow]")
    write_ogg("ambient1", chirp_series(4, 2900, 3300, 0.08, 0.05, 3600, seed=21), OUT_SPARROW)
    write_ogg("ambient2", chirp_series(5, 3000, 3400, 0.07, 0.04, 3700, seed=22), OUT_SPARROW)
    write_ogg("ambient3", chirp_series(3, 2800, 3200, 0.10, 0.07, 3500, seed=23), OUT_SPARROW)
    write_ogg("scared1", sparrow_scared(seed=24), OUT_SPARROW)
    write_ogg("scared2", sparrow_scared(seed=25), OUT_SPARROW)
    write_ogg("scared3", sparrow_scared(seed=26), OUT_SPARROW)
    write_ogg("hurt1", sparrow_hurt(seed=27), OUT_SPARROW)
    write_ogg("hurt2", sparrow_hurt(seed=28, dur=0.20), OUT_SPARROW)
    write_ogg("death1", sparrow_death(seed=29), OUT_SPARROW)
    write_ogg("death2", sparrow_death(seed=30), OUT_SPARROW)
    write_ogg("flap1", small_flap(seed=31, dur=0.10), OUT_SPARROW)
    write_ogg("flap2", small_flap(seed=32, dur=0.13), OUT_SPARROW)
    write_ogg("flap3", small_flap(seed=33, dur=0.11), OUT_SPARROW)
    # ── 山雀:清脆哨音(tee-cher 双音)──
    print("[tit]")
    write_ogg("ambient1", tit_phrase(seed=41, tee=3900, cher=3200, repeats=2), OUT_TIT)
    write_ogg("ambient2", tit_phrase(seed=42, tee=4000, cher=3300, repeats=3), OUT_TIT)
    write_ogg("ambient3", tit_phrase(seed=43, tee=3800, cher=3100, repeats=2, gap=0.09), OUT_TIT)
    write_ogg("scared1", tit_scared(seed=44), OUT_TIT)
    write_ogg("scared2", tit_scared(seed=45), OUT_TIT)
    write_ogg("scared3", tit_scared(seed=46), OUT_TIT)
    write_ogg("hurt1", tit_hurt(seed=47), OUT_TIT)
    write_ogg("hurt2", tit_hurt(seed=48, dur=0.18), OUT_TIT)
    write_ogg("death1", tit_death(seed=49), OUT_TIT)
    write_ogg("death2", tit_death(seed=50), OUT_TIT)
    write_ogg("flap1", small_flap(seed=51, dur=0.10), OUT_TIT)
    write_ogg("flap2", small_flap(seed=52, dur=0.12), OUT_TIT)
    write_ogg("flap3", small_flap(seed=53, dur=0.11), OUT_TIT)
    print("完成。M6 换真实录音时直接替换同名 ogg。")


if __name__ == "__main__":
    main()

#!/usr/bin/env python3
"""Independent acoustic check of the Hamir babble generator.

Mixes target test sentences with several noise types at the SNRs used in the paper
(Liu et al., PoPETs 2021, eq. 1-3) and reports STOI and the word error rate of a
grammar-informed PocketSphinx attacker (it knows the sentence structure of the test set;
with its generic language model PocketSphinx cannot transcribe even clean espeak-ng speech).

Usage: evaluate.py <workdir> <hamir-cli.jar> [extra CLI args for a variant...]
Limits: synthetic espeak-ng speech and a weak 2010s recogniser. Results show relative
ranking of noise types only; they are not evidence about real voices or modern ASR.
"""
import glob, json, os, subprocess, sys, tempfile, wave
import numpy as np
from scipy.signal import resample_poly
from pystoi import stoi

FS = 16000
SNRS = [10, 5, 0, -5]
MODEL = "/usr/share/pocketsphinx/model/en-us"


def read_wav(path):
    with wave.open(path, "rb") as w:
        assert w.getsampwidth() == 2
        x = np.frombuffer(w.readframes(w.getnframes()), dtype="<i2").astype(np.float64)
        if w.getnchannels() == 2:
            x = x.reshape(-1, 2).mean(axis=1)
        fs = w.getframerate()
    if fs != FS:
        g = np.gcd(fs, FS)
        x = resample_poly(x, FS // g, fs // g)
    return x / 32768.0


def write_wav(path, x):
    y = np.clip(np.round(x * 32768.0), -32768, 32767).astype("<i2")
    with wave.open(path, "wb") as w:
        w.setnchannels(1); w.setsampwidth(2); w.setframerate(FS)
        w.writeframes(y.tobytes())


def trim(x, thr=0.01):
    idx = np.where(np.abs(x) > thr)[0]
    return x[idx[0]:idx[-1] + 1] if len(idx) else x


def stream(files, rng, seconds):
    """One 'talker': shuffled utterances back to back."""
    out, n = [], 0
    while n < seconds * FS:
        for f in rng.permutation(files):
            u = trim(read_wav(f))
            out.append(u); n += len(u)
    return np.concatenate(out)[: seconds * FS]


def babble(file_groups, rng, seconds):
    mix = np.zeros(seconds * FS)
    for files in file_groups:
        s = stream(files, rng, seconds + 3)
        off = rng.integers(0, 3 * FS)
        s = s[off: off + seconds * FS]
        mix += s / np.sqrt(np.mean(s ** 2))
    return mix


def mix_at(speech, noise, snr_db, rng):
    off = rng.integers(0, len(noise) - len(speech))
    n = noise[off: off + len(speech)]
    scale = np.sqrt(np.sum(speech ** 2) / (np.sum(n ** 2) * 10 ** (snr_db / 10)))
    m = speech + scale * n
    peak = np.max(np.abs(m))
    return (m / peak * 0.9 if peak > 0.9 else m), speech * (0.9 / peak if peak > 0.9 else 1.0)


def recognise(path, grammar):
    cmd = ["pocketsphinx_continuous", "-infile", path, "-hmm", MODEL + "/en-us",
           "-dict", MODEL + "/cmudict-en-us.dict", "-logfn", "/dev/null", "-jsgf", grammar]
    out = subprocess.run(cmd, capture_output=True, text=True).stdout
    return " ".join(out.split()).lower()


def wer_counts(ref, hyp):
    r, h = ref.split(), hyp.split()
    d = np.zeros((len(r) + 1, len(h) + 1), dtype=int)
    d[:, 0] = np.arange(len(r) + 1); d[0, :] = np.arange(len(h) + 1)
    for i in range(1, len(r) + 1):
        for j in range(1, len(h) + 1):
            d[i, j] = min(d[i - 1, j] + 1, d[i, j - 1] + 1, d[i - 1, j - 1] + (r[i - 1] != h[j - 1]))
    return d[len(r), len(h)], len(r)


def main():
    work, jar = sys.argv[1], sys.argv[2]
    extra = sys.argv[3:]
    rng = np.random.default_rng(7)
    refs = dict(l.rstrip("\n").split("\t") for l in open(os.path.join(work, "test", "ref.txt")))
    enroll = sorted(glob.glob(os.path.join(work, "enroll", "*.wav")))
    others = [sorted(glob.glob(os.path.join(d, "*.wav")))
              for d in sorted(glob.glob(os.path.join(work, "others", "v*")))]
    seconds = 150
    hamir_wav = os.path.join(work, "hamir.wav")
    subprocess.run(["java", "-jar", jar, "generate", "--in", os.path.join(work, "enroll"),
                    "--out", hamir_wav, "--seconds", str(seconds), "--seed", "11"] + extra,
                   check=True, capture_output=True)
    hamir = read_wav(hamir_wav)
    noises = {
        "white": rng.standard_normal(seconds * FS),
        "generic_babble_8_voices": babble(others, rng, seconds),
        "same_voice_whole_utterances_8": babble([enroll] * 8, rng, seconds),
        "hamir_cli": hamir,
    }
    grammar = os.path.join(work, "grammar.jsgf")
    results = {}
    tmp = tempfile.mkdtemp()

    def score(tag, make):
        st, e2, n = [], 0, 0
        for key, ref in sorted(refs.items()):
            clean = trim(read_wav(os.path.join(work, "test", key + ".wav")))
            mixed, ref_sig = make(clean)
            p = os.path.join(tmp, "m.wav")
            write_wav(p, mixed)
            st.append(stoi(ref_sig, mixed, FS, extended=False))
            c, b = wer_counts(ref, recognise(p, grammar)); e2 += c; n += b
        results[tag] = {"stoi": round(float(np.mean(st)), 3),
                        "wer_grammar": round(100 * e2 / n, 1)}
        print(tag, results[tag], flush=True)

    score("clean", lambda s: (s, s))
    for name, noise in noises.items():
        for snr in SNRS:
            score(f"{name}@{snr}dB", lambda s, noise=noise, snr=snr: mix_at(s, noise, snr, rng))
    # Level stability of the generated noise: dips let the protected speech through.
    win = FS // 2
    frames = hamir[: len(hamir) // win * win].reshape(-1, win)
    lv = 10 * np.log10(np.mean(frames ** 2, axis=1) + 1e-12)
    long_term = 10 * np.log10(np.mean(hamir ** 2))
    results["hamir_level"] = {"long_term_dbfs": round(float(long_term), 2),
                              "min_500ms_rel_db": round(float(lv.min() - long_term), 2),
                              "max_500ms_rel_db": round(float(lv.max() - long_term), 2),
                              "peak": round(float(np.max(np.abs(hamir))), 3)}
    print("hamir_level", results["hamir_level"])
    json.dump(results, open(os.path.join(work, "results.json"), "w"), indent=1)


if __name__ == "__main__":
    main()

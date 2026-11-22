#!/usr/bin/env python3
"""Synthesises a test corpus with espeak-ng: one target speaker (enrollment + test
sentences) and eight other speakers for a generic-babble baseline.

Usage: make_corpus.py <workdir> <target_voice>   e.g. make_corpus.py /tmp/ac en-us+m3
"""
import os, random, subprocess, sys

DET_ADJ = ["the old", "the young", "the tall", "the quiet", "the happy", "the tired",
           "a clever", "a careful", "a lazy", "a friendly", "the busy", "a lonely"]
NOUN = ["farmer", "teacher", "doctor", "sailor", "painter", "driver", "baker", "soldier",
        "student", "singer", "hunter", "writer"]
VERB = ["carried", "found", "bought", "counted", "painted", "dropped", "wanted", "washed",
        "moved", "opened", "sold", "watched"]
NUM = ["two", "three", "four", "five", "six", "seven", "eight", "nine", "ten", "twelve"]
OBJ = ["baskets", "letters", "bottles", "windows", "apples", "horses", "tables", "boxes",
       "flowers", "candles", "blankets", "hammers"]
PREP = ["across the river", "behind the house", "near the station", "under the bridge",
        "before the winter", "after the meeting", "inside the garden", "beside the market",
        "over the mountain", "around the corner"]

OTHER_VOICES = [("en-us+m1", 40), ("en-us+m2", 55), ("en-us+m4", 45), ("en-us+m7", 60),
                ("en-us+f1", 50), ("en-us+f3", 55), ("en-us+f4", 45), ("en-gb+m5", 50)]


def sentence(rng):
    return " ".join([rng.choice(DET_ADJ), rng.choice(NOUN), rng.choice(VERB),
                     rng.choice(NUM), rng.choice(OBJ), rng.choice(PREP)])


def unique_sentences(rng, n, seen):
    out = []
    while len(out) < n:
        s = sentence(rng)
        if s not in seen:
            seen.add(s)
            out.append(s)
    return out


def synth(voice, pitch, text, path):
    subprocess.run(["espeak-ng", "-v", voice, "-p", str(pitch), "-s", "150", "-w", path, text],
                   check=True)


def main():
    work, target = sys.argv[1], sys.argv[2]
    rng = random.Random(2021)
    seen = set()
    enroll = unique_sentences(rng, 80, seen)
    test = unique_sentences(rng, 40, seen)
    for sub in ("enroll", "test", "others"):
        os.makedirs(os.path.join(work, sub), exist_ok=True)
    for i, s in enumerate(enroll):
        synth(target, 50, s, os.path.join(work, "enroll", f"{i:03d}.wav"))
    with open(os.path.join(work, "test", "ref.txt"), "w") as f:
        for i, s in enumerate(test):
            synth(target, 50, s, os.path.join(work, "test", f"{i:03d}.wav"))
            f.write(f"{i:03d}\t{s}\n")
    for v, (voice, pitch) in enumerate(OTHER_VOICES):
        d = os.path.join(work, "others", f"v{v}")
        os.makedirs(d, exist_ok=True)
        for i, s in enumerate(unique_sentences(rng, 20, seen)):
            synth(voice, pitch, s, os.path.join(d, f"{i:03d}.wav"))
    # Grammar for the "informed attacker" recogniser: it knows the sentence structure.
    def alt(words):
        return " | ".join(words)
    with open(os.path.join(work, "grammar.jsgf"), "w") as f:
        f.write("#JSGF V1.0;\ngrammar sent;\npublic <s> = (%s) (%s) (%s) (%s) (%s) (%s);\n" % (
            alt(DET_ADJ), alt(NOUN), alt(VERB), alt(NUM), alt(OBJ), alt(PREP)))
    print("corpus ready:", work)


if __name__ == "__main__":
    main()

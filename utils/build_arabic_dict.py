#!/usr/bin/env python3
"""
Rebuilds app/src/main/assets/ime/dict/ar.json and ar_bigrams.json (see ATTRIBUTION.txt there).

  python utils/build_arabic_dict.py <ar_full.txt> <leipzig-sentences.txt>

ar_full.txt: https://github.com/hermitdave/FrequencyWords content/2018/ar/ar_full.txt ("word count" lines)
sentences:   https://downloads.wortschatz-leipzig.de/corpora/ara_news_2022_1M.tar.gz (*-sentences.txt, "id<TAB>text")

Words already in ar.json are always kept; the old bigrams (Tatoeba, conversational) are merged with the news ones.
"""
import json
import math
import re
import sys
from collections import Counter, defaultdict
from pathlib import Path

DICT_DIR = Path(__file__).resolve().parent.parent / "app/src/main/assets/ime/dict"
MAX_WORDS = 150_000
MIN_COUNT = 20          # per source; below this the lists are mostly typos
MIN_PAIR_COUNT = 3
NEXT_WORDS = 6
FREQ_MIN, FREQ_MAX = 120, 255

NEWS_WEIGHT = 0.6      # next words: everyday (Tatoeba) pairs beat news pairs ("صباح الخير" over "صباح اليوم")

# Harakat are kept: people do type شكراً / مرحباً, and those are separate dictionary entries.
LETTERS = "ء-يً-ْٰٱ"
WORD = re.compile(rf"^[{LETTERS}]{{1,30}}$")
MARKS = re.compile("ـ")   # tatweel
SEGMENT_BREAK = re.compile(rf"[^{LETTERS}\s]+")


def subtitle_counts(path):
    counts = {}
    with open(path, encoding="utf-8") as f:
        for line in f:
            word, _, count = line.rstrip("\n").rpartition(" ")
            word = MARKS.sub("", word)
            if WORD.match(word) and int(count) >= MIN_COUNT:
                counts[word] = counts.get(word, 0) + int(count)
    return counts


def news_counts(path):
    words, pairs = Counter(), Counter()
    with open(path, encoding="utf-8") as f:
        for line in f:
            text = MARKS.sub("", line.partition("\t")[2])
            for segment in SEGMENT_BREAK.split(text):
                tokens = [t for t in segment.split() if WORD.match(t)]
                words.update(tokens)
                pairs.update(zip(tokens, tokens[1:]))
    return words, pairs


def per_million(counts):
    total = sum(counts.values())
    return {w: c * 1e6 / total for w, c in counts.items()}


def main(full_path, sentences_path):
    old_words = json.loads((DICT_DIR / "ar.json").read_text(encoding="utf-8"))
    old_pairs = json.loads((DICT_DIR / "ar_bigrams.json").read_text(encoding="utf-8"))

    subs = per_million(subtitle_counts(full_path))
    news_words, news_pairs = news_counts(sentences_path)
    news = per_million({w: c for w, c in news_words.items() if c >= MIN_COUNT})

    # Subtitles are closer to how people type, news adds vocabulary; a word scores by its more common source.
    score = {w: max(subs.get(w, 0), news.get(w, 0)) for w in subs.keys() | news.keys()}
    chosen = sorted(score, key=score.get, reverse=True)[:MAX_WORDS]
    lo, hi = math.log(score[chosen[-1]]), math.log(score[chosen[0]])
    words = {w: round(FREQ_MIN + (FREQ_MAX - FREQ_MIN) * (math.log(score[w]) - lo) / (hi - lo)) for w in chosen}
    for w, f in old_words.items():
        words.setdefault(w, f)

    # Each source's next words are scaled to its own strongest pair, so short Tatoeba counts are not drowned out.
    merged = defaultdict(Counter)
    news_next = defaultdict(dict)
    for (a, b), c in news_pairs.items():
        if c >= MIN_PAIR_COUNT and a in words and b in words:
            news_next[a][b] = c
    for source, weight in ((old_pairs, 1.0), (news_next, NEWS_WEIGHT)):
        for prev, nexts in source.items():
            top = max(nexts.values())
            for word, c in nexts.items():
                if prev in words and word in words:
                    merged[prev][word] += weight * c / top
    bigrams = {
        prev: {w: max(1, round(s * 50)) for w, s in nexts.most_common(NEXT_WORDS)}
        for prev, nexts in sorted(merged.items())
    }

    ordered = dict(sorted(words.items(), key=lambda kv: -kv[1]))
    (DICT_DIR / "ar.json").write_text(json.dumps(ordered, ensure_ascii=False, separators=(",", ":")), encoding="utf-8")
    (DICT_DIR / "ar_bigrams.json").write_text(json.dumps(bigrams, ensure_ascii=False, separators=(",", ":")), encoding="utf-8")
    print(f"ar.json: {len(old_words)} -> {len(ordered)} words; ar_bigrams.json: {len(old_pairs)} -> {len(bigrams)} entries")


if __name__ == "__main__":
    main(*sys.argv[1:3])

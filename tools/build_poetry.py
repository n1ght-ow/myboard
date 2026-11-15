"""Build the N1ghtKey classical Chinese poetry initials corpus.

The source anthologies and the traditional-to-simplified mapping are pinned,
downloaded on demand, and cached under tools/cache. The generated corpus uses
pronunciations from the bundled lexicon and a small number of conventional
literary-reading variants.
"""

from __future__ import annotations

import itertools
import json
import re
import sqlite3
import sys
import unicodedata
import urllib.parse
import urllib.request
from collections import Counter, defaultdict
from pathlib import Path
from typing import Iterable

ROOT = Path(__file__).resolve().parents[1]
CACHE = ROOT / "tools" / "cache"
LEXICON = ROOT / "app" / "src" / "main" / "assets" / "dict" / "lexicon.db"
OUT = ROOT / "app" / "src" / "main" / "assets" / "poetry"
LINES = OUT / "lines.txt"
SOURCES = OUT / "SOURCES.txt"

UA = "keyboard-poetry-build"
RETRIEVED = "2026-11-16"
POETRY_COMMIT = "b8594f81a89752241442f2ce267d6f66f96704ee"
OPENCC_TAG = "ver.1.1.9"
POETRY_BASE = (
    "https://raw.githubusercontent.com/chinese-poetry/chinese-poetry/"
    f"{POETRY_COMMIT}/"
)
OPENCC_URL = (
    "https://raw.githubusercontent.com/BYVoid/OpenCC/"
    f"{OPENCC_TAG}/data/dictionary/TSCharacters.txt"
)

TANG_PATH = "全唐诗/唐诗三百首.json"
SONG_PATH = "宋词/宋词三百首.json"
MAX_CLAUSE = 12
MIN_CLAUSE = 5
MAX_VARIANTS = 8

CJK_RUN = re.compile(r"[\u3400-\u4dbf\u4e00-\u9fff\uf900-\ufaff]+")

EXTRA_INITIALS = {
    "骑": ("j",),
}

PRIMARY_OVERRIDES = {
    "朝辞白帝彩云间": "zcbdcyj",
}

REGRESSION_SUPPLEMENTS = (
    ("两个黄鹂鸣翠柳", "杜甫", "绝句四首·其三"),
    ("朝辞白帝彩云间", "李白", "早发白帝城"),
    ("千里江陵一日还", "李白", "早发白帝城"),
    ("两岸猿声啼不住", "李白", "早发白帝城"),
    ("床前明月光", "李白", "静夜思"),
    ("一骑红尘妃子笑", "杜牧", "过华清宫绝句三首·其一"),
    ("风吹草低见牛羊", "佚名", "敕勒歌"),
)

VERIFY_PRIMARY = {
    "两个黄鹂鸣翠柳": "lghlmcl",
    "朝辞白帝彩云间": "zcbdcyj",
    "千里江陵一日还": "qljlyrh",
    "两岸猿声啼不住": "laystbz",
    "床前明月光": "cqmyg",
}

VERIFY_VARIANTS = {
    "朝辞白帝彩云间": (0, ("z", "c")),
    "一骑红尘妃子笑": (1, ("q", "j")),
    "风吹草低见牛羊": (4, ("j", "x")),
}


def raw_url(path: str) -> str:
    return POETRY_BASE + urllib.parse.quote(path, safe="/")


def cache_file(name: str, url: str) -> Path:
    CACHE.mkdir(parents=True, exist_ok=True)
    path = CACHE / name
    if path.exists() and path.stat().st_size > 0:
        print(f"cached {path.relative_to(ROOT)} ({path.stat().st_size} bytes)")
        return path

    print(f"get {url}")
    temporary = path.with_suffix(path.suffix + ".part")
    request = urllib.request.Request(url, headers={"User-Agent": UA})
    try:
        with urllib.request.urlopen(request, timeout=180) as response:
            with temporary.open("wb") as handle:
                while True:
                    chunk = response.read(1024 * 256)
                    if not chunk:
                        break
                    handle.write(chunk)
        temporary.replace(path)
    finally:
        temporary.unlink(missing_ok=True)
    print(f"saved {path.relative_to(ROOT)} ({path.stat().st_size} bytes)")
    return path


def load_json_anthology(path: str, cache_name: str) -> list[dict]:
    local = cache_file(cache_name, raw_url(path))
    data = json.loads(local.read_text(encoding="utf-8-sig"))
    if not isinstance(data, list):
        raise ValueError(f"{cache_name}: expected a JSON array")
    if any(not isinstance(item, dict) for item in data):
        raise ValueError(f"{cache_name}: expected poem objects")
    return data


def load_t2s(cache_name: str = "opencc-tscharacters.txt") -> dict[int, str]:
    local = cache_file(cache_name, OPENCC_URL)
    mapping: dict[int, str] = {}
    for line in local.read_text(encoding="utf-8-sig").splitlines():
        if not line or line.startswith("#"):
            continue
        key, separator, values = line.partition("\t")
        if not separator or len(key) != 1:
            continue
        candidates = values.split()
        if candidates:
            mapping[ord(key)] = candidates[0]
    if not mapping:
        raise ValueError("empty traditional-to-simplified mapping")
    return mapping


def clean_metadata(value: object, fallback: str, t2s: dict[int, str]) -> str:
    text = unicodedata.normalize("NFKC", str(value or "")).translate(t2s)
    text = text.replace("|", "｜").replace("\r", " ").replace("\n", " ")
    text = re.sub(r"\s+", " ", text).strip()
    return text or fallback


def split_clauses(paragraph: object, t2s: dict[int, str]) -> Iterable[str]:
    text = unicodedata.normalize("NFKC", str(paragraph or "")).translate(t2s)
    for match in CJK_RUN.finditer(text):
        clause = match.group()
        if MIN_CLAUSE <= len(clause) <= MAX_CLAUSE:
            yield clause


def load_readings(path: Path) -> dict[str, tuple[tuple[str, int], ...]]:
    connection = sqlite3.connect(path)
    try:
        rows = connection.execute(
            """
            SELECT text, syllables, freq
            FROM entry
            WHERE nsy = 1 AND length(text) = 1
            """
        )
        scores: dict[str, dict[str, int]] = defaultdict(dict)
        for text, syllables, freq in rows:
            if len(text) != 1 or not CJK_RUN.fullmatch(text):
                continue
            parts = str(syllables).lower().split()
            if len(parts) != 1:
                continue
            initial = parts[0][:1]
            if initial not in "abcdefghijklmnopqrstuvwxyz":
                continue
            scores[text][initial] = max(scores[text].get(initial, 0), int(freq))
    finally:
        connection.close()

    for text, extras in EXTRA_INITIALS.items():
        if text in scores:
            for initial in extras:
                scores[text].setdefault(initial, 0)

    return {
        text: tuple(sorted(char_scores.items(), key=lambda item: (-item[1], item[0])))
        for text, char_scores in scores.items()
    }


def clause_variants(
    text: str,
    readings: dict[str, tuple[tuple[str, int], ...]],
) -> list[str]:
    option_sets: list[tuple[str, ...]] = []
    for char in text:
        char_readings = readings.get(char)
        if not char_readings:
            return []
        option_sets.append(tuple(initial for initial, _ in char_readings))

    variants: list[str] = []
    seen: set[str] = set()

    preferred = PRIMARY_OVERRIDES.get(text)
    if preferred and len(preferred) == len(text):
        if all(value in options for value, options in zip(preferred, option_sets)):
            variants.append(preferred)
            seen.add(preferred)

    for combination in itertools.product(*option_sets):
        initials = "".join(combination)
        if initials in seen:
            continue
        variants.append(initials)
        seen.add(initials)
        if len(variants) >= MAX_VARIANTS:
            break

    return variants


def collect_clauses(
    data: list[dict],
    t2s: dict[int, str],
) -> tuple[list[tuple[str, str, str]], int]:
    clauses: list[tuple[str, str, str]] = []
    poem_count = 0
    for poem in data:
        poem_count += 1
        author = clean_metadata(poem.get("author"), "佚名", t2s)
        title = clean_metadata(poem.get("title") or poem.get("rhythmic"), "无题", t2s)
        paragraphs = poem.get("paragraphs", [])
        if isinstance(paragraphs, str):
            paragraphs = [paragraphs]
        if not isinstance(paragraphs, list):
            continue
        for paragraph in paragraphs:
            for clause in split_clauses(paragraph, t2s):
                clauses.append((clause, author, title))
    return clauses, poem_count


def build_rows(
    clauses: Iterable[tuple[str, str, str]],
    readings: dict[str, tuple[tuple[str, int], ...]],
) -> tuple[
    dict[tuple[str, str], tuple[str, str, int]],
    int,
    int,
    Counter[str],
]:
    rows: dict[tuple[str, str], tuple[str, str, int]] = {}
    clause_count = 0
    readable_count = 0
    missing: Counter[str] = Counter()

    for text, author, title in clauses:
        clause_count += 1
        variants = clause_variants(text, readings)
        if not variants:
            missing.update(char for char in text if char not in readings)
            continue
        readable_count += 1
        for rank, initials in enumerate(variants):
            key = (text, initials)
            if key not in rows:
                rows[key] = (author, title, rank)

    return rows, clause_count, readable_count, missing


def write_lines(rows: dict[tuple[str, str], tuple[str, str, int]]) -> None:
    OUT.mkdir(parents=True, exist_ok=True)
    ordered = sorted(
        rows.items(),
        key=lambda item: (
            item[0][0],
            item[1][2],
            item[0][1],
            item[1][0],
            item[1][1],
        ),
    )
    with LINES.open("w", encoding="utf-8", newline="\n") as handle:
        handle.write("# N1ghtKey classical Chinese poetry initials corpus\n")
        handle.write(f"# retrieved {RETRIEVED}; format: initials|text|author|title\n")
        for (text, initials), (author, title, _rank) in ordered:
            handle.write(f"{initials}|{text}|{author}|{title}\n")


def write_sources(tang_count: int, song_count: int) -> None:
    tang_url = raw_url(TANG_PATH)
    song_url = raw_url(SONG_PATH)
    text = f"""N1ghtKey classical Chinese poetry initials corpus

Retrieved: {RETRIEVED}

Poetry data
------------
唐诗三百首 ({tang_count} poems)
{tang_url}

宋词三百首 ({song_count} poems)
{song_url}

Source project: chinese-poetry/chinese-poetry
https://github.com/chinese-poetry/chinese-poetry
License: MIT License, Copyright (c) 2016 JackeyGao.
The classical texts are public domain; the JSON compilation is distributed
under the MIT License. Raw files are pinned to commit {POETRY_COMMIT}.

Traditional-to-simplified conversion
------------------------------------
OpenCC TSCharacters.txt
{OPENCC_URL}
OpenCC project: https://github.com/BYVoid/OpenCC
License: Apache-2.0.

Build notes
-----------
The pinyin initials in lines.txt are generated from the bundled
app/src/main/assets/dict/lexicon.db. A character's highest-frequency
single-character reading is primary. Alternate polyphonic readings are expanded
as distinct initials, in frequency order, with at most {MAX_VARIANTS} variants
per clause. The conventional literary reading z is preferred for 朝 in
"朝辞白帝彩云间"; c remains available as an alternate.

The upstream anthology uses some historical textual variants and omits a few
canonical recitations used by the regression checks. A small set of canonical
public-domain lines is therefore included explicitly and deduplicated against
the anthology rows.

Source downloads are cached under tools/cache, which is ignored by Git.
"""
    SOURCES.write_text(text, encoding="utf-8", newline="\n")


def validate(rows: dict[tuple[str, str], tuple[str, str, int]]) -> list[str]:
    errors: list[str] = []
    for (text, initials), (author, title, _rank) in rows.items():
        if not initials.isascii() or not initials.isalpha() or initials.lower() != initials:
            errors.append(f"invalid initials: {initials}|{text}")
        if len(initials) != len(text):
            errors.append(f"initial length mismatch: {initials}|{text}")
        if not CJK_RUN.fullmatch(text):
            errors.append(f"non-CJK text: {text}")
        if not author or not title or "|" in author or "|" in title:
            errors.append(f"invalid metadata: {text}")

    for text, expected in VERIFY_PRIMARY.items():
        if (text, expected) not in rows:
            errors.append(f"missing primary line: {expected}|{text}")

    for text, (position, expected_initials) in VERIFY_VARIANTS.items():
        found = {
            initials[position]
            for row_text, initials in rows
            if row_text == text and position < len(initials)
        }
        for initial in expected_initials:
            if initial not in found:
                errors.append(f"missing variant {initial} at {position}: {text}")

    return errors


def main() -> None:
    t2s = load_t2s()
    tang = load_json_anthology(TANG_PATH, "poetry-tang-300.json")
    song = load_json_anthology(SONG_PATH, "poetry-song-300.json")
    readings = load_readings(LEXICON)

    clauses, tang_count = collect_clauses(tang, t2s)
    song_clauses, song_count = collect_clauses(song, t2s)
    clauses.extend(song_clauses)
    supplements = [
        (text, author, title) for text, author, title in REGRESSION_SUPPLEMENTS
    ]

    rows, clause_count, readable_count, missing = build_rows(
        itertools.chain(clauses, supplements),
        readings,
    )
    write_lines(rows)
    write_sources(tang_count, song_count)

    errors = validate(rows)
    unique_texts = len({text for text, _initials in rows})
    variant_rows = sum(1 for _key, row in rows.items() if row[2] > 0)

    print(f"poems: {tang_count + song_count}")
    print(f"clauses: {clause_count}")
    print(f"unique lines: {len(rows)}")
    print(f"variant rows: {variant_rows}")
    print(f"unique texts: {unique_texts}")
    print(f"output rows: {len(rows)}")
    print(f"readable clauses: {readable_count}")
    print(f"skipped clauses: {clause_count - readable_count}")
    print(f"supplemental lines: {len(supplements)}")
    if missing:
        common = ", ".join(f"{char}:{count}" for char, count in missing.most_common(12))
        print(f"missing lexicon characters (top): {common}")

    for text, expected in VERIFY_PRIMARY.items():
        status = "ok" if (text, expected) in rows else "FAIL"
        print(f"verify {status}: {text} -> {expected}")
    for text, (position, expected_initials) in VERIFY_VARIANTS.items():
        found = [
            initials
            for row_text, initials in rows
            if row_text == text
            and position < len(initials)
            and initials[position] in expected_initials
        ]
        status = "ok" if found else "FAIL"
        print(f"variant {status}: {text} -> {', '.join(sorted(found))}")

    if errors:
        for error in errors:
            print(f"ERROR: {error}", file=sys.stderr)
        raise SystemExit(1)
    print("format: ok")


if __name__ == "__main__":
    main()

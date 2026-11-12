"""Build the on-device lexicon from 雾凇拼音 and the English frequency list.

The Chinese tables are GPL-3.0 (iDvel/rime-ice). The English list is MIT
(Hermit Dave, FrequencyWords). Output is written into the app assets.
"""

from __future__ import annotations

import gzip
import sqlite3
import urllib.request
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
CACHE = ROOT / "tools" / "cache"
OUT = ROOT / "app" / "src" / "main" / "assets" / "dict"
UA = "keyboard-lexicon-build"

RIME = {
    "8105.dict.yaml": "https://raw.githubusercontent.com/iDvel/rime-ice/main/cn_dicts/8105.dict.yaml",
    "base.dict.yaml": "https://raw.githubusercontent.com/iDvel/rime-ice/main/cn_dicts/base.dict.yaml",
    "ext.dict.yaml": "https://raw.githubusercontent.com/iDvel/rime-ice/main/cn_dicts/ext.dict.yaml",
    "tencent.dict.yaml": "https://raw.githubusercontent.com/iDvel/rime-ice/main/cn_dicts/tencent.dict.yaml",
}
ENGLISH = "https://raw.githubusercontent.com/hermitdave/FrequencyWords/master/content/2018/en/en_50k.txt"

# Single-letter syllables that are real pinyin initials-as-syllables.
SHORT_OK = {"a", "e", "o", "m", "n"}


def download(url: str, dest: Path) -> None:
    dest.parent.mkdir(parents=True, exist_ok=True)
    if dest.exists() and dest.stat().st_size > 0:
        print(f"cached {dest.name} ({dest.stat().st_size})", flush=True)
        return
    print(f"get {url}", flush=True)
    temporary = dest.with_suffix(dest.suffix + ".part")
    req = urllib.request.Request(url, headers={"User-Agent": UA})
    with urllib.request.urlopen(req, timeout=180) as response, temporary.open("wb") as handle:
        while True:
            chunk = response.read(1024 * 256)
            if not chunk:
                break
            handle.write(chunk)
    temporary.replace(dest)
    print(f"saved {dest.name} ({dest.stat().st_size})", flush=True)


def normalize_pinyin(raw: str) -> str | None:
    text = (
        raw.strip()
        .lower()
        .replace("ü", "v")
        .replace("u:", "v")
        .replace("，", " ")
    )
    parts: list[str] = []
    for token in text.split():
        syllable = "".join(ch for ch in token if ch.isascii() and ch.isalpha())
        if not syllable or len(syllable) > 6:
            return None
        if not all("a" <= ch <= "z" for ch in syllable):
            return None
        if len(syllable) == 1 and syllable not in SHORT_OK:
            return None
        parts.append(syllable)
    if not parts:
        return None
    return " ".join(parts)


def iter_rime(path: Path):
    started = False
    saw_header = False
    with path.open(encoding="utf-8", errors="replace") as handle:
        for line in handle:
            stripped = line.strip()
            if not started:
                if stripped == "---":
                    saw_header = True
                    continue
                if stripped == "..." and saw_header:
                    started = True
                continue
            if not stripped or stripped.startswith("#"):
                continue
            cols = line.rstrip("\n").split("\t")
            if len(cols) < 2:
                continue
            word = cols[0].strip()
            if not word or word.startswith("#"):
                continue
            if not any(ord(ch) > 127 for ch in word):
                continue
            if len(word) > 16:
                continue
            syllables = normalize_pinyin(cols[1])
            if syllables is None:
                continue
            freq = 1
            if len(cols) >= 3:
                token = cols[2].strip().split()[0] if cols[2].strip() else ""
                try:
                    freq = int(float(token))
                except ValueError:
                    freq = 1
            if freq < 1:
                freq = 1
            if freq > 1_000_000_000:
                freq = 1_000_000_000
            yield word, syllables, freq


def keep_entry(name: str, word: str, freq: int) -> bool:
    # tencent.dict.yaml has no pinyin column, so iter_rime skips it.
    # Names and newer terms in 雾凇 often sit at weight 100–1999.
    # Common phrases still outrank these inside the decoder because a
    # frequent word scores above a weight-100 entry of the same length.
    if name == "8105.dict.yaml":
        return True
    if name == "base.dict.yaml":
        return len(word) == 1 or freq >= 100
    if name == "ext.dict.yaml":
        return 2 <= len(word) <= 6 and freq >= 100
    return False


def build_chinese() -> None:
    rows: dict[tuple[str, str], int] = {}
    syllables: set[str] = set(SHORT_OK)
    for name in ("8105.dict.yaml", "base.dict.yaml", "ext.dict.yaml"):
        path = CACHE / name
        seen = 0
        kept = 0
        for word, spelling, freq in iter_rime(path):
            seen += 1
            if not keep_entry(name, word, freq):
                continue
            key = (word, spelling)
            previous = rows.get(key, 0)
            if freq > previous:
                rows[key] = freq
            kept += 1
            for part in spelling.split(" "):
                syllables.add(part)
        print(f"{name}: read {seen}, kept {kept}, unique {len(rows)}", flush=True)
    OUT.mkdir(parents=True, exist_ok=True)
    db_path = OUT / "lexicon.db"
    if db_path.exists():
        db_path.unlink()
    con = sqlite3.connect(db_path)
    con.execute("PRAGMA journal_mode=OFF")
    con.execute("PRAGMA synchronous=OFF")
    con.execute(
        """
        CREATE TABLE entry (
            text TEXT NOT NULL,
            syllables TEXT NOT NULL,
            letters TEXT NOT NULL,
            initials TEXT NOT NULL,
            freq INTEGER NOT NULL,
            nsy INTEGER NOT NULL
        )
        """
    )
    batch = []
    for (word, spelling), freq in rows.items():
        letters = spelling.replace(" ", "")
        initials = "".join(part[0] for part in spelling.split())
        nsy = spelling.count(" ") + 1
        batch.append((word, spelling, letters, initials, freq, nsy))
        if len(batch) >= 5000:
            con.executemany("INSERT INTO entry VALUES (?,?,?,?,?,?)", batch)
            batch.clear()
    if batch:
        con.executemany("INSERT INTO entry VALUES (?,?,?,?,?,?)", batch)
    con.execute("CREATE INDEX idx_letters ON entry(letters, freq DESC)")
    con.execute("CREATE INDEX idx_initials ON entry(initials, freq DESC)")
    con.execute("CREATE INDEX idx_text ON entry(text)")
    con.execute("CREATE INDEX idx_char ON entry(nsy, syllables, freq DESC)")
    con.commit()
    con.execute("VACUUM")
    con.close()
    syl_path = OUT / "syllables.txt"
    syl_path.write_text("\n".join(sorted(syllables)) + "\n", encoding="utf-8")
    print(f"lexicon rows={len(rows)} syllables={len(syllables)} bytes={db_path.stat().st_size}")


def build_english() -> None:
    path = CACHE / "en_50k.txt"
    lines: list[str] = []
    with path.open(encoding="utf-8", errors="replace") as handle:
        for line in handle:
            bits = line.split()
            if len(bits) < 2:
                continue
            word = bits[0].strip().lower()
            if not word or len(word) > 32:
                continue
            if not all(("a" <= ch <= "z") or ch == "'" for ch in word):
                continue
            if word.startswith("'") or word.endswith("'"):
                continue
            try:
                freq = int(float(bits[1]))
            except ValueError:
                continue
            if freq > 1_000_000_000:
                freq = 1_000_000_000
            lines.append(f"{word}\t{freq}\n")
    dest = OUT / "english.tsv.gz"
    with gzip.open(dest, "wt", encoding="utf-8", newline="\n") as handle:
        handle.writelines(lines)
    print(f"english rows={len(lines)} bytes={dest.stat().st_size}")


def write_sources() -> None:
    text = """Chinese lexicon
Derived from 雾凇拼音 cn_dicts: every entry in 8105, base entries with weight >= 100,
and ext entries of 2–6 characters with weight >= 100.
Each row stores syllable initials so abbreviated pinyin can be queried.
The ext table is where 雾凇 keeps names and newer terms such as 特朗普.
Upstream: https://github.com/iDvel/rime-ice
License: GPL-3.0-only

English lexicon
Derived from FrequencyWords content/2018/en/en_50k.txt
Upstream: https://github.com/hermitdave/FrequencyWords
License: MIT
"""
    (OUT / "SOURCES.txt").write_text(text, encoding="utf-8")


def main() -> None:
    CACHE.mkdir(parents=True, exist_ok=True)
    for name, url in RIME.items():
        download(url, CACHE / name)
    download(ENGLISH, CACHE / "en_50k.txt")
    build_chinese()
    build_english()
    write_sources()


if __name__ == "__main__":
    main()

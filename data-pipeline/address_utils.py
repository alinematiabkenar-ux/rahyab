"""
Shared street & house-number normalization and address deduplication utilities
for the RahYab offline state pack pipeline.
"""

from __future__ import annotations

import math
import re

PERSIAN_ARABIC_DIGITS = str.maketrans("۰۱۲۳۴۵۶۷۸۹٠١٢٣٤٥٦٧٨٩", "01234567890123456789")

DIRECTIONAL_MAP: dict[str, str] = {
    "north": "n",
    "n": "n",
    "south": "s",
    "s": "s",
    "east": "e",
    "e": "e",
    "west": "w",
    "w": "w",
    "northeast": "ne",
    "ne": "ne",
    "northwest": "nw",
    "nw": "nw",
    "southeast": "se",
    "se": "se",
    "southwest": "sw",
    "sw": "sw",
}

STREET_SUFFIX_MAP: dict[str, str] = {
    "street": "st",
    "str": "st",
    "st": "st",
    "avenue": "ave",
    "av": "ave",
    "ave": "ave",
    "road": "rd",
    "rd": "rd",
    "drive": "dr",
    "drv": "dr",
    "dr": "dr",
    "boulevard": "blvd",
    "boul": "blvd",
    "blvd": "blvd",
    "lane": "ln",
    "ln": "ln",
    "court": "ct",
    "crt": "ct",
    "ct": "ct",
    "place": "pl",
    "pl": "pl",
    "highway": "hwy",
    "hwy": "hwy",
    "parkway": "pkwy",
    "pky": "pkwy",
    "pkwy": "pkwy",
    "circle": "cir",
    "cir": "cir",
    "terrace": "ter",
    "ter": "ter",
    "trail": "trl",
    "trl": "trl",
}

ADDRESS_DEDUP_MAX_DISTANCE_M = 30.0


def normalize_digits(text: str) -> str:
    return (text or "").translate(PERSIAN_ARABIC_DIGITS)


def normalize_house_number(number: str) -> str:
    cleaned = normalize_digits(number).strip().lower()
    cleaned = re.sub(r"[^0-9a-z\-/]+", "", cleaned)
    return cleaned


def normalize_street(street: str) -> str:
    """
    Normalize a US street name for matching & FTS indexing:
    - Converts Persian/Arabic digits, lowercases, strips punctuation
    - Normalizes directionals (North -> n, Northeast -> ne, etc.)
    - Normalizes street suffixes (Street -> st, Avenue -> ave, Parkway -> pkwy, etc.)
    """
    raw = normalize_digits(street).strip().lower()
    if not raw:
        return ""
    tokens = re.sub(r"[^\w]+", " ", raw.replace("_", " ")).split()
    if not tokens:
        return ""
    out: list[str] = []
    n = len(tokens)
    for idx, tok in enumerate(tokens):
        if tok in DIRECTIONAL_MAP and (idx == 0 or idx == n - 1):
            out.append(DIRECTIONAL_MAP[tok])
        elif tok in STREET_SUFFIX_MAP and idx > 0:
            out.append(STREET_SUFFIX_MAP[tok])
        elif tok in DIRECTIONAL_MAP and idx > 0 and idx == n - 2 and tokens[-1] in STREET_SUFFIX_MAP:
            out.append(DIRECTIONAL_MAP[tok])
        else:
            out.append(tok)
    return " ".join(out)


def _haversine_meters(lat1: float, lon1: float, lat2: float, lon2: float) -> float:
    r = 6_371_000.0
    phi1 = math.radians(lat1)
    phi2 = math.radians(lat2)
    dphi = math.radians(lat2 - lat1)
    dlambda = math.radians(lon2 - lon1)
    a = math.sin(dphi / 2.0) ** 2 + math.cos(phi1) * math.cos(phi2) * math.sin(dlambda / 2.0) ** 2
    return 2.0 * r * math.atan2(math.sqrt(a), math.sqrt(max(0.0, 1.0 - a)))


def deduplicate_addresses(
    overture_rows: list[tuple],
    osm_rows: list[tuple],
    max_distance_m: float = ADDRESS_DEDUP_MAX_DISTANCE_M,
) -> tuple[list[tuple], int]:
    """
    Deduplicate address tuples of shape:
      (number, street, unit, city, state, postcode, lat, lon, source)
    Same normalized (number, street) within `max_distance_m` (30 m) collapses to one row,
    preferring Overture coordinates over OSM.
    """
    cell_deg = 0.0005  # ~55 m lat / ~40 m lon; 3x3 neighborhood covers 30 m
    grid: dict[tuple[str, str, int, int], list[int]] = {}
    kept: list[tuple] = []
    removed = 0

    # Insert Overture rows first so Overture coordinates are always preferred
    for row in overture_rows + osm_rows:
        norm_num = normalize_house_number(row[0])
        norm_st = normalize_street(row[1])
        if not norm_num or not norm_st:
            continue
        lat, lon = row[6], row[7]
        clat = int(math.floor(lat / cell_deg))
        clon = int(math.floor(lon / cell_deg))

        is_dup = False
        for dlat in (-1, 0, 1):
            if is_dup:
                break
            for dlon in (-1, 0, 1):
                for existing_idx in grid.get((norm_num, norm_st, clat + dlat, clon + dlon), ()):
                    ex = kept[existing_idx]
                    if _haversine_meters(lat, lon, ex[6], ex[7]) <= max_distance_m:
                        is_dup = True
                        break
                if is_dup:
                    break

        if is_dup:
            removed += 1
        else:
            idx = len(kept)
            kept.append(row)
            grid.setdefault((norm_num, norm_st, clat, clon), []).append(idx)

    return kept, removed

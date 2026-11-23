"""Unit tests for RahYab v21 pipeline fixes (P1, P2, P5)."""

from __future__ import annotations

import datetime
import json
import sqlite3
import sys
import unittest
from pathlib import Path

PIPELINE_DIR = Path(__file__).resolve().parent
if str(PIPELINE_DIR) not in sys.path:
    sys.path.insert(0, str(PIPELINE_DIR))

import address_utils as au  # noqa: E402
import build_state_pack as bsp  # noqa: E402


class TestPipelineV21(unittest.TestCase):
    def test_p1_populate_addresses_fts_multiple_batches(self):
        """P1: populate_addresses_fts must index ALL rows across multiple batches (> batch_size)."""
        conn = sqlite3.connect(":memory:")
        cur = conn.cursor()
        cur.executescript(
            """
            CREATE TABLE addresses (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                number TEXT,
                street TEXT,
                unit TEXT,
                city TEXT,
                state TEXT,
                postcode TEXT,
                lat REAL,
                lon REAL,
                source TEXT
            );
            CREATE VIRTUAL TABLE addresses_fts USING fts5(
                number, street, city, postcode,
                content='addresses', content_rowid='id',
                tokenize='unicode61 remove_diacritics 2',
                prefix='1 2 3 4'
            );
            """
        )
        # Insert 137 rows and run with batch_size=25 (> 5 batches)
        rows = [
            (str(1000 + i), f"East {i}th Avenue", "", "Eugene", "OR", "97403", 44.05 + i * 0.0001, -123.08, "overture")
            for i in range(137)
        ]
        cur.executemany(
            "INSERT INTO addresses (number, street, unit, city, state, postcode, lat, lon, source) "
            "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
            rows,
        )
        conn.commit()

        inserted = au.populate_addresses_fts(conn, batch_size=25)
        self.assertEqual(inserted, 137)

        # Verify rows from both the first batch and the very last batch are searchable via FTS5
        first_hit = cur.execute(
            "SELECT a.number FROM addresses_fts JOIN addresses a ON a.id = addresses_fts.rowid "
            "WHERE addresses_fts MATCH 'number:1000 street:e street:0th street:ave'"
        ).fetchall()
        self.assertEqual(len(first_hit), 1)

        last_hit = cur.execute(
            "SELECT a.number FROM addresses_fts JOIN addresses a ON a.id = addresses_fts.rowid "
            "WHERE addresses_fts MATCH 'number:1136 street:e street:136th street:ave'"
        ).fetchall()
        self.assertEqual(len(last_hit), 1)
        conn.close()

    def test_p2_generate_geofabrik_dated_urls(self):
        """P2: dated URL generator handles both .../us/oregon-latest.osm.pbf and .../us/oregon/oregon-latest.osm.pbf."""
        ref_date = datetime.date(2026, 11, 22)
        candidates = [
            "https://download.geofabrik.de/north-america/us/oregon/oregon-latest.osm.pbf",
            "https://download.geofabrik.de/north-america/us/oregon-latest.osm.pbf",
        ]
        urls = bsp.generate_geofabrik_dated_urls(candidates, today=ref_date)

        # Each candidate gets 10 daily dates (Nov 22..Nov 13) + 1st of current month (Nov 01) + 1st of prev month (Oct 01) = 12 dates
        self.assertEqual(len(urls), 24)

        # Folder 1: .../us/oregon/oregon-<YYMMDD>.osm.pbf
        self.assertEqual(urls[0], "https://download.geofabrik.de/north-america/us/oregon/oregon-261122.osm.pbf")
        self.assertEqual(urls[1], "https://download.geofabrik.de/north-america/us/oregon/oregon-261121.osm.pbf")
        self.assertEqual(urls[9], "https://download.geofabrik.de/north-america/us/oregon/oregon-261113.osm.pbf")
        self.assertEqual(urls[10], "https://download.geofabrik.de/north-america/us/oregon/oregon-261101.osm.pbf")
        self.assertEqual(urls[11], "https://download.geofabrik.de/north-america/us/oregon/oregon-261001.osm.pbf")

        # Folder 2: .../us/oregon-<YYMMDD>.osm.pbf
        self.assertEqual(urls[12], "https://download.geofabrik.de/north-america/us/oregon-261122.osm.pbf")
        self.assertEqual(urls[22], "https://download.geofabrik.de/north-america/us/oregon-261101.osm.pbf")
        self.assertEqual(urls[23], "https://download.geofabrik.de/north-america/us/oregon-261001.osm.pbf")

    def test_p5_shared_street_normalization_table(self):
        """P5: Python normalize_street passes every entry in the shared 26-case table."""
        table_path = PIPELINE_DIR / "street_normalization_test_cases.json"
        cases = json.loads(table_path.read_text(encoding="utf-8"))
        self.assertGreaterEqual(len(cases), 20)
        for raw, expected in cases:
            with self.subTest(raw=raw):
                self.assertEqual(au.normalize_street(raw), expected)


if __name__ == "__main__":
    unittest.main()

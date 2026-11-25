#!/usr/bin/env python3
"""Unit tests for Phase X extra offline data pipeline (X1-X4)."""

from __future__ import annotations

import json
import os
import sqlite3
import sys
import tempfile
import unittest
from pathlib import Path
from unittest import mock

PIPELINE_DIR = Path(__file__).resolve().parent
if str(PIPELINE_DIR) not in sys.path:
    sys.path.insert(0, str(PIPELINE_DIR))

from build_state_pack import merge_manifests, write_pack_manifest_entry
from extras_builder import build_elevation_tiles, compute_elevation_bbox, validate_test_route_response
from extras_fra_hpms import (
    discover_fra_columns,
    discover_hpms_columns,
    encode_polyline6,
    extract_polylines_from_geometry,
    init_extras_schema,
    parse_fra_row,
    parse_hpms_row,
    simplify_polyline,
)


class ExtrasPipelineUnitTest(unittest.TestCase):
    def test_elevation_bbox_expands_and_snaps_to_integers(self):
        bbox = (-124.57, 41.99, -116.46, 46.29)
        min_x, min_y, max_x, max_y = compute_elevation_bbox(bbox)
        self.assertEqual((min_x, min_y, max_x, max_y), (-126, 41, -115, 47))

    def test_build_elevation_tiles_exact_cli_command(self):
        bbox = (-124.57, 41.99, -116.46, 46.29)
        with tempfile.TemporaryDirectory() as tmp:
            elev_dir = Path(tmp) / "elev"
            fake_tool = "/usr/local/bin/valhalla_build_elevation"
            captured_cmds: list[list[str]] = []

            def fake_run(cmd, **kwargs):
                captured_cmds.append(list(cmd))
                (elev_dir / "N44W124.hgt").write_bytes(b"hgt")
                return mock.Mock(returncode=0, stdout="ok", stderr="")

            with mock.patch("extras_builder.shutil.which", return_value=fake_tool), \
                 mock.patch("extras_builder.subprocess.run", side_effect=fake_run):
                result = build_elevation_tiles("or", bbox, elev_dir)

            self.assertEqual(result, elev_dir)
            self.assertEqual(len(captured_cmds), 1)
            self.assertEqual(
                captured_cmds[0],
                [
                    fake_tool,
                    "--from-bbox=-126,41,-115,47",
                    "--outdir",
                    str(elev_dir),
                    "-p",
                    str(os.cpu_count() or 4),
                    "-v",
                ],
            )

    def test_validate_test_route_response_checks_duration_maneuvers_and_lanes(self):
        good_route = {
            "trip": {
                "summary": {"time": 6600.0, "length": 178.0},
                "legs": [
                    {
                        "maneuvers": [
                            {"type": 1, "instruction": "Drive north"},
                            {"type": 20, "instruction": "Exit right", "lanes": [{"directions": ["right"]}]},
                            {"type": 4, "instruction": "Arrive"},
                        ]
                    }
                ],
            }
        }
        ok, msg = validate_test_route_response(good_route)
        self.assertTrue(ok, msg)

        too_fast = {
            "trip": {
                "summary": {"time": 1200.0, "length": 20.0},
                "legs": [{"maneuvers": [{"type": 1, "lanes": [{}]}, {"type": 4}]}],
            }
        }
        ok_fast, _ = validate_test_route_response(too_fast)
        self.assertFalse(ok_fast)

        no_lanes = {
            "trip": {
                "summary": {"time": 6600.0, "length": 178.0},
                "legs": [{"maneuvers": [{"type": 1}, {"type": 4}]}],
            }
        }
        ok_no_lanes, _ = validate_test_route_response(no_lanes)
        self.assertFalse(ok_no_lanes)

    def test_fra_column_discovery_and_filtering(self):
        cols = [
            {"fieldName": "crossingid", "name": "Crossing ID"},
            {"fieldName": "latitude", "name": "Latitude"},
            {"fieldName": "longitude", "name": "Longitude"},
            {"fieldName": "street", "name": "Street"},
            {"fieldName": "railroad", "name": "Railroad"},
            {"fieldName": "gates", "name": "Gates"},
            {"fieldName": "flashing_lights", "name": "Flashing Lights"},
            {"fieldName": "wdcode", "name": "Warning Device Code"},
            {"fieldName": "maintrk", "name": "Main Tracks"},
            {"fieldName": "othrtrk", "name": "Other Tracks"},
            {"fieldName": "daythru", "name": "Day Thru Trains"},
            {"fieldName": "nghtthru", "name": "Night Thru Trains"},
            {"fieldName": "crossingclosed", "name": "Crossing Closed"},
            {"fieldName": "posxing", "name": "Crossing Position"},
            {"fieldName": "typexing", "name": "Crossing Type"},
        ]
        cmap = discover_fra_columns(cols)
        or_bbox = (-124.6, 41.9, -116.4, 46.3)

        valid = parse_fra_row(
            {
                "crossingid": "759512A",
                "latitude": "44.0521",
                "longitude": "-123.0868",
                "street": "Willamette St",
                "railroad": "UP",
                "gates": "2",
                "flashing_lights": "2",
                "wdcode": "8",
                "maintrk": "2",
                "othrtrk": "1",
                "daythru": "10",
                "nghtthru": "6",
                "crossingclosed": "No",
                "posxing": "1",
                "typexing": "3",
            },
            cmap,
            or_bbox,
        )
        self.assertIsNotNone(valid)
        self.assertEqual(valid[0], "759512A")
        self.assertEqual(valid[5], 1)  # has_gates
        self.assertEqual(valid[6], 1)  # has_lights
        self.assertEqual(valid[7], 3)  # tracks
        self.assertEqual(valid[8], 16)  # daily_trains

        # Closed crossing is filtered out
        closed = parse_fra_row(
            {
                "crossingid": "759513B",
                "latitude": "44.0521",
                "longitude": "-123.0868",
                "crossingclosed": "Yes",
                "posxing": "1",
                "typexing": "3",
            },
            cmap,
            or_bbox,
        )
        self.assertIsNone(closed)

        # Private crossing is filtered out
        private = parse_fra_row(
            {
                "crossingid": "759514C",
                "latitude": "44.0521",
                "longitude": "-123.0868",
                "crossingclosed": "No",
                "posxing": "1",
                "typexing": "2",
            },
            cmap,
            or_bbox,
        )
        self.assertIsNone(private)

    def test_hpms_discovery_geometry_polyline6_and_filtering(self):
        cols = [
            {"fieldName": "speed_limit", "name": "Speed Limit", "dataTypeName": "number"},
            {"fieldName": "state_code", "name": "State Code", "dataTypeName": "text"},
            {"fieldName": "route_id", "name": "Route ID", "dataTypeName": "text"},
            {"fieldName": "the_geom", "name": "Geometry", "dataTypeName": "multiline"},
        ]
        cmap = discover_hpms_columns(cols)
        self.assertEqual(cmap["speed"], "speed_limit")
        self.assertEqual(cmap["geometry"], "the_geom")

        or_bbox = (-124.6, 41.9, -116.4, 46.3)
        row = {
            "speed_limit": "55",
            "state_code": "41",
            "route_id": "OR-126",
            "the_geom": {
                "type": "LineString",
                "coordinates": [
                    [-123.0900, 44.0500],
                    [-123.0850, 44.0500],
                    [-123.0800, 44.0500],
                ],
            },
        }
        parsed = parse_hpms_row(row, cmap, or_bbox)
        self.assertEqual(len(parsed), 1)
        speed_mph, route_name, min_lat, min_lon, max_lat, max_lon, poly = parsed[0]
        self.assertEqual(speed_mph, 55)
        self.assertEqual(route_name, "OR-126")
        self.assertAlmostEqual(min_lat, 44.05, places=4)
        self.assertTrue(len(poly) > 0)

        wkt_lines = extract_polylines_from_geometry("LINESTRING (-123.1 44.0, -123.0 44.1)")
        self.assertEqual(len(wkt_lines), 1)
        self.assertEqual(len(simplify_polyline(wkt_lines[0])), 2)
        self.assertTrue(len(encode_polyline6(wkt_lines[0])) > 0)

    def test_manifest_preserves_extras_and_optional_size_without_changing_core_totals(self):
        with tempfile.TemporaryDirectory() as tmp:
            out = Path(tmp)
            (out / "or.pmtiles").write_bytes(b"PMTiles" + b"0" * 100)
            (out / "or_valhalla.tar").write_bytes(b"V" * 200)
            (out / "or_places.db").write_bytes(b"P" * 300)

            extras_db = out / "or_extras.db"
            conn = sqlite3.connect(str(extras_db))
            init_extras_schema(conn)
            conn.execute("INSERT INTO meta (key, value) VALUES ('schema_version', '1')")
            conn.commit()
            conn.close()

            entry = write_pack_manifest_entry(
                pack_code="or",
                output_dir=out,
                base_url="https://example.com/packs",
                version="2026.11.24",
                build_version="2026.11.24",
            )
            # Core totalSizeBytes MUST remain pmtiles + valhalla + places only
            self.assertEqual(entry["totalSizeBytes"], 107 + 200 + 300)
            self.assertIn("extras", entry)
            self.assertEqual(entry["optionalSizeBytes"], extras_db.stat().st_size)
            self.assertEqual(entry["extras"]["filename"], "or_extras.db")

            merged_path = out / "manifest.json"
            merge_manifests(
                [out / "manifest_or.json"],
                merged_path,
                "https://example.com/packs",
                "2026.11.24",
            )
            merged = json.loads(merged_path.read_text(encoding="utf-8"))
            self.assertEqual(len(merged["states"]), 1)
            self.assertIn("extras", merged["states"][0])
            self.assertEqual(merged["states"][0]["optionalSizeBytes"], extras_db.stat().st_size)


if __name__ == "__main__":
    unittest.main()

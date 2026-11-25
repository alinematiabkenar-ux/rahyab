#!/usr/bin/env python3
"""Unit tests for Phase X extra offline data pipeline (X1-X4, FX1-FX4)."""

from __future__ import annotations

import io
import json
import os
import sqlite3
import sys
import tempfile
import unittest
import urllib.error
import urllib.parse
from contextlib import redirect_stdout
from pathlib import Path
from unittest import mock

PIPELINE_DIR = Path(__file__).resolve().parent
if str(PIPELINE_DIR) not in sys.path:
    sys.path.insert(0, str(PIPELINE_DIR))

from build_state_pack import merge_manifests, write_pack_manifest_entry
from extras_builder import build_elevation_tiles, compute_elevation_bbox, validate_test_route_response
from extras_fra_hpms import (
    FRA_VERIFIED_COL_MAP,
    discover_fra_columns,
    discover_hpms_columns,
    encode_polyline6,
    extract_polylines_from_geometry,
    http_get_json,
    init_extras_schema,
    parse_fra_row,
    parse_hpms_row,
    populate_fra_crossings,
    populate_hpms_speed,
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

    def test_fra_verified_fields_wdcode_and_skip_rules(self):
        or_bbox = (-124.6, 41.9, -116.4, 46.3)
        base = {
            "crossingid": "100001A",
            "latitude": "44.0521",
            "longitude": "-123.0868",
            "statecode": "41",
            "street": "Main St",
            "railroadname": "Union Pacific",
            "crossingclosed": "No",
            "crossingpositioncode": "1",
            "crossingtypecode": "3",
            "countroadwaygatearms": "0",
            "countflashinglightpair": "0",
            "countmastmountedflashinglight": "0",
            "numberofmaintracks": "2",
            "numberofsidingtracks": "1",
            "totaldaylightthrutrains": "8",
            "totalnighttimethrutrains": "4",
            "totalswitchingtrains": "3",
        }

        # wdcode "8" -> has_gates=1, has_lights=1
        row_wd8 = dict(base, wdcode="8")
        parsed_8 = parse_fra_row(row_wd8, FRA_VERIFIED_COL_MAP, or_bbox)
        self.assertIsNotNone(parsed_8)
        self.assertEqual(parsed_8[4], "Union Pacific")
        self.assertEqual(parsed_8[5], 1)  # has_gates
        self.assertEqual(parsed_8[6], 1)  # has_lights
        self.assertEqual(parsed_8[7], 3)  # tracks = 2 + 1
        self.assertEqual(parsed_8[8], 15)  # daily_trains = 8 + 4 + 3

        # wdcode "7" -> has_gates=0, has_lights=1
        row_wd7 = dict(base, crossingid="100002B", wdcode="7")
        parsed_7 = parse_fra_row(row_wd7, FRA_VERIFIED_COL_MAP, or_bbox)
        self.assertIsNotNone(parsed_7)
        self.assertEqual(parsed_7[5], 0)  # has_gates
        self.assertEqual(parsed_7[6], 1)  # has_lights

        # Closed crossing -> skipped
        self.assertIsNone(
            parse_fra_row(dict(base, crossingclosed="Yes", wdcode="8"), FRA_VERIFIED_COL_MAP, or_bbox)
        )
        # Private crossing (crossingtypecode "2") -> skipped
        self.assertIsNone(
            parse_fra_row(dict(base, crossingtypecode="2", wdcode="8"), FRA_VERIFIED_COL_MAP, or_bbox)
        )
        # Grade-separated crossing (crossingpositioncode "2") -> skipped
        self.assertIsNone(
            parse_fra_row(dict(base, crossingpositioncode="2", wdcode="8"), FRA_VERIFIED_COL_MAP, or_bbox)
        )

    def test_populate_fra_uses_within_box_and_falls_back_to_statecode(self):
        or_bbox = (-124.57, 41.99, -116.46, 46.29)
        sample_rows = [
            {
                "crossingid": "759512A",
                "latitude": "44.0521",
                "longitude": "-123.0868",
                "street": "Willamette St",
                "railroadname": "UP",
                "crossingclosed": "No",
                "crossingpositioncode": "1",
                "crossingtypecode": "3",
                "wdcode": "8",
                "countroadwaygatearms": "0",
                "countflashinglightpair": "0",
                "countmastmountedflashinglight": "0",
                "numberofmaintracks": "2",
                "numberofsidingtracks": "0",
                "totaldaylightthrutrains": "5",
                "totalnighttimethrutrains": "3",
                "totalswitchingtrains": "2",
            },
            {
                "crossingid": "759513B",
                "latitude": "44.0600",
                "longitude": "-123.0900",
                "street": "Oak St",
                "railroadname": "BNSF",
                "crossingclosed": "No",
                "crossingpositioncode": "1",
                "crossingtypecode": "3",
                "wdcode": "7",
                "countroadwaygatearms": "0",
                "countflashinglightpair": "0",
                "countmastmountedflashinglight": "0",
                "numberofmaintracks": "1",
                "numberofsidingtracks": "0",
                "totaldaylightthrutrains": "2",
                "totalnighttimethrutrains": "1",
                "totalswitchingtrains": "0",
            },
            # Closed, private, grade-separated, and outside bbox -> all skipped
            {
                "crossingid": "CLOSED1",
                "latitude": "44.0521",
                "longitude": "-123.0868",
                "crossingclosed": "Yes",
                "crossingpositioncode": "1",
                "crossingtypecode": "3",
                "wdcode": "8",
            },
            {
                "crossingid": "PRIV1",
                "latitude": "44.0521",
                "longitude": "-123.0868",
                "crossingclosed": "No",
                "crossingpositioncode": "1",
                "crossingtypecode": "2",
                "wdcode": "8",
            },
            {
                "crossingid": "GRADE1",
                "latitude": "44.0521",
                "longitude": "-123.0868",
                "crossingclosed": "No",
                "crossingpositioncode": "2",
                "crossingtypecode": "3",
                "wdcode": "8",
            },
            {
                "crossingid": "OUTSIDE1",
                "latitude": "34.0500",
                "longitude": "-118.2500",
                "crossingclosed": "No",
                "crossingpositioncode": "1",
                "crossingtypecode": "3",
                "wdcode": "8",
            },
        ]

        # 1. Primary path: within_box(geocoded_lat_long, MAX_LAT, MIN_LON, MIN_LAT, MAX_LON)
        primary_urls: list[str] = []

        def fake_primary(url: str, timeout_sec: int = 45):
            primary_urls.append(url)
            return sample_rows

        conn = sqlite3.connect(":memory:")
        init_extras_schema(conn)
        with mock.patch("extras_fra_hpms.http_get_json", side_effect=fake_primary):
            count = populate_fra_crossings(conn, "or", or_bbox)

        self.assertEqual(count, 2)
        self.assertEqual(len(primary_urls), 1)
        qs = urllib.parse.parse_qs(urllib.parse.urlparse(primary_urls[0]).query)
        where_val = qs["$where"][0]
        self.assertIn("within_box(geocoded_lat_long,", where_val)
        self.assertEqual(
            where_val,
            "within_box(geocoded_lat_long, 46.290000, -124.570000, 41.990000, -116.460000)",
        )
        self.assertEqual(qs["$order"][0], "crossingid")
        self.assertEqual(qs["$limit"][0], "20000")
        self.assertIn("railroadname", qs["$select"][0])

        rows_in_db = {
            r[0]: r
            for r in conn.execute(
                "SELECT crossing_id, has_gates, has_lights, tracks, daily_trains, railroad FROM fra_crossings"
            ).fetchall()
        }
        self.assertEqual(rows_in_db["759512A"], ("759512A", 1, 1, 2, 10, "UP"))
        self.assertEqual(rows_in_db["759513B"], ("759513B", 0, 1, 1, 3, "BNSF"))
        conn.close()

        # 2. Fallback path: primary within_box raises HTTPError -> uses statecode='41'
        fallback_urls: list[str] = []

        def fake_fallback(url: str, timeout_sec: int = 45):
            fallback_urls.append(url)
            if len(fallback_urls) == 1:
                raise urllib.error.HTTPError(url, 400, "Bad Request", hdrs=None, fp=io.BytesIO(b"bad within_box"))
            return sample_rows

        conn2 = sqlite3.connect(":memory:")
        init_extras_schema(conn2)
        with mock.patch("extras_fra_hpms.http_get_json", side_effect=fake_fallback):
            count_fb = populate_fra_crossings(conn2, "or", or_bbox)

        self.assertEqual(count_fb, 2)
        self.assertEqual(len(fallback_urls), 2)
        qs_fb = urllib.parse.parse_qs(urllib.parse.urlparse(fallback_urls[1]).query)
        self.assertEqual(qs_fb["$where"][0], "statecode='41'")
        self.assertEqual(qs_fb["$order"][0], "crossingid")
        conn2.close()

        # 3. Zero-row warning printed when 0 rows inserted
        conn3 = sqlite3.connect(":memory:")
        init_extras_schema(conn3)
        buf = io.StringIO()
        with mock.patch("extras_fra_hpms.http_get_json", return_value=[]), redirect_stdout(buf):
            count_zero = populate_fra_crossings(conn3, "or", or_bbox)
        self.assertEqual(count_zero, 0)
        self.assertIn("[warn] extras step fra: 0 rows inserted", buf.getvalue())
        conn3.close()

    def test_populate_hpms_uses_numeric_stateid_and_route_number_fallback(self):
        or_bbox = (-124.57, 41.99, -116.46, 46.29)
        captured_urls: list[str] = []

        sample_rows = [
            {
                "speed_limit": 65,
                "routename": "I-5",
                "route_number": 5,
                "line": {
                    "type": "LineString",
                    "coordinates": [[-123.0900, 44.0500], [-123.0800, 44.0600]],
                },
            },
            {
                "speed_limit": "55",
                "routename": "",
                "route_number": 126,
                "line": {
                    "type": "MultiLineString",
                    "coordinates": [
                        [[-123.1200, 44.0400], [-123.1000, 44.0400]],
                        [[-123.0700, 44.0400], [-123.0500, 44.0400]],
                    ],
                },
            },
            # Out of speed range (< 10 mph) -> skipped
            {
                "speed_limit": 5,
                "routename": "Alley",
                "route_number": 0,
                "line": {
                    "type": "LineString",
                    "coordinates": [[-123.0900, 44.0500], [-123.0800, 44.0500]],
                },
            },
        ]

        def fake_get(url: str, timeout_sec: int = 45):
            captured_urls.append(url)
            return sample_rows

        conn = sqlite3.connect(":memory:")
        init_extras_schema(conn)
        with mock.patch("extras_fra_hpms.http_get_json", side_effect=fake_get):
            count = populate_hpms_speed(conn, "or", or_bbox)

        self.assertEqual(count, 3)  # 1 from LineString + 2 from MultiLineString
        self.assertEqual(len(captured_urls), 1)
        qs = urllib.parse.parse_qs(urllib.parse.urlparse(captured_urls[0]).query)
        where_val = qs["$where"][0]
        self.assertIn("stateid = 41", where_val)
        self.assertNotIn("'41'", where_val)
        self.assertNotIn("upper(", where_val.lower())
        self.assertEqual(where_val, "stateid = 41 AND speed_limit > 0")
        self.assertEqual(qs["$select"][0], "speed_limit,routename,route_number,line")
        self.assertEqual(qs["$order"][0], ":id")
        self.assertEqual(qs["$limit"][0], "20000")

        stored = conn.execute("SELECT speed_mph, route_name FROM hpms_speed ORDER BY id").fetchall()
        self.assertEqual(stored, [(65, "I-5"), (55, "Route 126"), (55, "Route 126")])
        conn.close()

        # Zero-row warning printed when 0 rows inserted
        conn2 = sqlite3.connect(":memory:")
        init_extras_schema(conn2)
        buf = io.StringIO()
        with mock.patch("extras_fra_hpms.http_get_json", return_value=[]), redirect_stdout(buf):
            count_zero = populate_hpms_speed(conn2, "or", or_bbox)
        self.assertEqual(count_zero, 0)
        self.assertIn("[warn] extras step hpms: 0 rows inserted", buf.getvalue())
        conn2.close()

    def test_http_get_json_logs_status_and_first_300_chars_on_http_error(self):
        long_body = ("ERR_DETAIL_" * 50).encode("utf-8")
        err = urllib.error.HTTPError(
            "https://example.com/resource.json",
            400,
            "Bad Request",
            hdrs=None,
            fp=io.BytesIO(long_body),
        )
        buf = io.StringIO()
        with mock.patch("extras_fra_hpms.urllib.request.urlopen", side_effect=err), redirect_stdout(buf):
            with self.assertRaises(urllib.error.HTTPError):
                http_get_json("https://example.com/resource.json")
        out = buf.getvalue()
        self.assertIn("400", out)
        self.assertIn(long_body.decode("utf-8")[:300], out)
        self.assertNotIn(long_body.decode("utf-8")[:305], out)

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

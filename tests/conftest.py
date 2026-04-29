from __future__ import annotations

import pytest

SAMPLE_GPX = """<?xml version="1.0" encoding="UTF-8"?>
<gpx version="1.1" creator="gpx-link-test">
  <wpt lat="45.5" lon="-122.5">
    <name>Alpha</name>
    <ele>100.0</ele>
    <desc>First</desc>
  </wpt>
  <wpt lat="46.0" lon="-123.0">
    <name>Beta</name>
  </wpt>
</gpx>
"""

EMPTY_TRACK_GPX = """<?xml version="1.0" encoding="UTF-8"?>
<gpx version="1.1" creator="gpx-link-test">
  <trk><name>Empty</name></trk>
</gpx>
"""


@pytest.fixture
def sample_gpx_path(tmp_path):
    p = tmp_path / "sample.gpx"
    p.write_text(SAMPLE_GPX, encoding="utf-8")
    return p

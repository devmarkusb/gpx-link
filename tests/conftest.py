from __future__ import annotations

import pytest

SAMPLE_GPX = """<?xml version="1.0" encoding="UTF-8"?>
<gpx version="1.1" creator="gpx-link-test">
  <wpt lat="45.5" lon="-122.5">
    <name>Alpha</name>
    <ele>100.0</ele>
    <desc>First</desc>
    <type>Trailhead</type>
    <sym>Trail Head</sym>
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

TRACK_GPX = """<?xml version="1.0" encoding="UTF-8"?>
<gpx version="1.1" creator="gpx-link-test">
  <trk>
    <name>Loop</name>
    <trkseg>
      <trkpt lat="45.51" lon="-122.50"></trkpt>
      <trkpt lat="45.52" lon="-122.51"></trkpt>
      <trkpt lat="45.53" lon="-122.52"></trkpt>
    </trkseg>
  </trk>
</gpx>
"""

ROUTE_GPX = """<?xml version="1.0" encoding="UTF-8"?>
<gpx version="1.1" creator="gpx-link-test">
  <rte>
    <name>Path</name>
    <rtept lat="46.01" lon="-123.02"></rtept>
    <rtept lat="46.02" lon="-123.03"></rtept>
  </rte>
</gpx>
"""


@pytest.fixture
def sample_gpx_path(tmp_path):
    p = tmp_path / "sample.gpx"
    p.write_text(SAMPLE_GPX, encoding="utf-8")
    return p

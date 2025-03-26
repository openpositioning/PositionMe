import json
import numpy as np

def load_json_records(json_path):
    """Load JSON records sorted by timestamp."""
    with open(json_path, 'r', encoding='utf-8') as f:
        return sorted([json.loads(line) for line in f if line.strip()],
                    key=lambda x: x.get("timestamp", 0))

def latlon_to_local_xy(lat, lon, lat0, lon0):
    """Convert lat/lon (degrees) to local x,y (meters)."""
    R = 6378137  # Earth radius in meters
    dlat = np.radians(lat - lat0)
    dlon = np.radians(lon - lon0)
    lat_avg = np.radians((lat + lat0) / 2.0)
    x = dlon * R * np.cos(lat_avg)
    y = dlat * R
    return x, y

def local_xy_to_latlon(x, y, lat0, lon0):
    """Convert local x,y (meters) back to lat/lon (degrees)."""
    R = 6378137
    dlat = y / R
    dlon = x / (R * np.cos(np.radians(lat0)))
    lat = lat0 + np.degrees(dlat)
    lon = lon0 + np.degrees(dlon)
    return lat, lon

def get_origin_from_first_gnss(data):
    """Return the first valid GNSS lat/lon as the origin."""
    for rec in data:
        if rec.get("gnssLat") is not None and rec.get("gnssLon") is not None:
            return rec["gnssLat"], rec["gnssLon"]
    return None, None

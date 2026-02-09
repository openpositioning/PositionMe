package com.openpositioning.PositionMe.presentation.fragment; 
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import com.google.android.gms.maps.GoogleMap;
import com.google.android.gms.maps.model.BitmapDescriptor;
import com.google.android.gms.maps.model.BitmapDescriptorFactory;
import com.google.android.gms.maps.model.GroundOverlay;
import com.google.android.gms.maps.model.GroundOverlayOptions;
import com.google.android.gms.maps.model.LatLng;
import com.google.android.gms.maps.model.Polygon;
import com.google.android.gms.maps.model.PolygonOptions;
import com.google.android.gms.maps.model.Polyline;
import com.google.android.gms.maps.model.PolylineOptions;
import com.google.android.gms.maps.model.LatLngBounds;
import com.openpositioning.PositionMe.data.model.FloorplanModels;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
public class IndoorMapFragment {
    private final GoogleMap mMap;
    private final GroundOverlay[] groundOverlays;
    private int currentFloor = 0;
    public IndoorMapFragment(@NonNull GoogleMap map, int floorNumber) {
        this.mMap = map;
        this.groundOverlays = new GroundOverlay[Math.max(floorNumber, 1)];
    }
    private static final String TAG = "IndoorMapFragment";
    @Nullable private GroundOverlay remoteOverlay;
    @NonNull private final List<GroundOverlay> remoteOverlays = new ArrayList<>();
    private final OkHttpClient remoteClient = new OkHttpClient();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    @Nullable private FloorplanModels.Venue selectedVenue = null;
    @Nullable private FloorplanModels.Floor selectedFloor = null;

    private int overlayGeneration = 0;
    @NonNull private final List<Polygon> remoteWallPolygons = new ArrayList<>();
    @NonNull private final List<Polyline> remoteWallLines = new ArrayList<>();
    public void addFloor(int floorIndex, int drawableResId, LatLngBounds bounds) {
        clearRemoteOverlayOnly();
        clearRemoteWallsOnly();
        BitmapDescriptor image = BitmapDescriptorFactory.fromResource(drawableResId);
        GroundOverlayOptions groundOverlayOptions = new GroundOverlayOptions()
                .image(image)
                .positionFromBounds(bounds)
                .visible(floorIndex == currentFloor)
                .transparency(0.2f);
        groundOverlays[floorIndex] = mMap.addGroundOverlay(groundOverlayOptions);
    }
    public void switchFloor(int floorIndex) {
        clearRemoteOverlayOnly();
        clearRemoteWallsOnly();

        if (floorIndex < 0 || floorIndex >= groundOverlays.length) {
            return; // Prevent index out of bounds
        }
        for (GroundOverlay overlay : groundOverlays) {
            if (overlay != null) {
                overlay.setVisible(false);
            }
        }
        GroundOverlay selectedOverlay = groundOverlays[floorIndex];
        if (selectedOverlay != null) {
            selectedOverlay.setVisible(true);
        }
        currentFloor = floorIndex;
    }
    public void hideMap() {
        setIndoorVisible(false);
    }

    public void setIndoorVisible(boolean visible) {
        for (int i = 0; i < groundOverlays.length; i++) {
            GroundOverlay overlay = groundOverlays[i];
            if (overlay != null) {
                overlay.setVisible(visible && i == currentFloor);
            }
        }
        if (remoteOverlay != null) remoteOverlay.setVisible(visible);
        for (Polygon p : remoteWallPolygons) {
            try { if (p != null) p.setVisible(visible); } catch (Exception ignore) {}
        }
        for (Polyline l : remoteWallLines) {
            try { if (l != null) l.setVisible(visible); } catch (Exception ignore) {}
        }
    }
    public void setSelectedVenue(@Nullable FloorplanModels.Venue venue) {
        this.selectedVenue = venue;
        this.selectedFloor = null;
    }



    public void showFloor(@Nullable FloorplanModels.Floor floor) {
        if (floor == null || mMap == null) return;
        this.selectedFloor = floor;

        final int gen = ++overlayGeneration;

        try {
            Log.d(TAG, "showFloor(gen=" + gen + ") venue=" +
                    (selectedVenue != null ? selectedVenue.venueName : "null") +
                    " floor=" + floor.toString() +
                    " hasGeom=" + floor.hasGeometry() +
                    " hasImg=" + (floor.imageUrl != null && !floor.imageUrl.trim().isEmpty()));
        } catch (Exception ignore) {}

        for (GroundOverlay o : groundOverlays) {
            if (o != null) o.setVisible(false);
        }
        clearRemoteOverlayOnly();
        clearRemoteWallsOnly();
        LatLngBounds boundsTmp = (floor.bounds != null)
                ? floor.bounds
                : (selectedVenue != null ? selectedVenue.bounds : null);
        try {
            LatLng target = (mMap != null) ? mMap.getCameraPosition().target : null;
            if (boundsTmp != null && target != null) {
                boundsTmp = bestBoundsForTarget(boundsTmp, target);
            }
        } catch (Exception ignore) {}
        LatLngBounds bounds = boundsTmp;
        if (bounds == null && selectedVenue != null && selectedVenue.outline != null && selectedVenue.outline.size() >= 2) {
            double minLat = Double.POSITIVE_INFINITY, maxLat = Double.NEGATIVE_INFINITY;
            double minLng = Double.POSITIVE_INFINITY, maxLng = Double.NEGATIVE_INFINITY;
            for (LatLng p : selectedVenue.outline) {
                if (p == null) continue;
                minLat = Math.min(minLat, p.latitude);
                maxLat = Math.max(maxLat, p.latitude);
                minLng = Math.min(minLng, p.longitude);
                maxLng = Math.max(maxLng, p.longitude);
            }
            if (Double.isFinite(minLat) && Double.isFinite(maxLat) && Double.isFinite(minLng) && Double.isFinite(maxLng)) {
                try { bounds = new LatLngBounds(new LatLng(minLat, minLng), new LatLng(maxLat, maxLng)); }
                catch (Exception ignore) {}
            }
        }
        if (bounds == null) {
            LatLng target = null;
            try { target = mMap.getCameraPosition().target; } catch (Exception ignore) {}
            if (target != null) {
                bounds = approxSquareBounds(target, 30.0);
                Log.w(TAG, "Floor has no bounds; using approximate bounds around camera target.");
            }
        }
        final LatLngBounds finalBounds = bounds;
        if (floor.hasGeometry()) {
            drawVectorWalls(floor);
            return;
        }
        if (finalBounds == null) {
            Log.w(TAG, "Floor has no bounds; cannot place overlay.");
            return;
        }
        if (floor.imageUrl == null || floor.imageUrl.trim().isEmpty()) {
            Log.w(TAG, "Floor has no imageUrl and no geometry.");
            return;
        }
        String url = floor.imageUrl.trim();
        if (url.startsWith("//")) url = "https:" + url;
        if (url.startsWith("/")) url = "https://openpositioning.org" + url;
        if (url.startsWith("http://openpositioning.org")) {
            url = url.replace("http://openpositioning.org", "https://openpositioning.org");
        }
        Request req = new Request.Builder()
                .url(url)
                .addHeader("Accept", "image/*")
                .build();
        remoteClient.newCall(req).enqueue(new Callback() {
            @Override public void onFailure(@NonNull Call call, @NonNull IOException e) {
                Log.e(TAG, "Image download failed: " + e.getMessage());
            }
            @Override public void onResponse(@NonNull Call call, @NonNull Response response) throws IOException {
                if (!response.isSuccessful() || response.body() == null) {
                    Log.e(TAG, "Image HTTP " + response.code());
                    return;
                }
                byte[] bytes = response.body().bytes();
                Bitmap bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
                if (bmp == null) {
                    Log.e(TAG, "Bitmap decode failed");
                    return;
                }
                mainHandler.post(() -> {
                    if (gen != overlayGeneration) {
                        Log.d(TAG, "Ignoring stale image overlay (gen=" + gen + ", now=" + overlayGeneration + ")");
                        return;
                    }
                    clearRemoteOverlayOnly();
                    GroundOverlayOptions opts = new GroundOverlayOptions()
                            .image(BitmapDescriptorFactory.fromBitmap(bmp))
                            .positionFromBounds(finalBounds)
                            .transparency(0.15f)
                            .zIndex(2000f);
                    remoteOverlay = mMap.addGroundOverlay(opts);
                    if (remoteOverlay != null) remoteOverlays.add(remoteOverlay);
                });
            }
        });
    }
    private static @Nullable LatLngBounds swapBoundsLatLon(@Nullable LatLngBounds b) {
        if (b == null) return null;
        LatLng sw = b.southwest;
        LatLng ne = b.northeast;
        LatLng sw2 = new LatLng(sw.longitude, sw.latitude);
        LatLng ne2 = new LatLng(ne.longitude, ne.latitude);
        try {
            return new LatLngBounds(
                    new LatLng(Math.min(sw2.latitude, ne2.latitude), Math.min(sw2.longitude, ne2.longitude)),
                    new LatLng(Math.max(sw2.latitude, ne2.latitude), Math.max(sw2.longitude, ne2.longitude))
            );
        } catch (Exception e) {
            return null;
        }
    }
    private static @NonNull LatLngBounds bestBoundsForTarget(@NonNull LatLngBounds original, @NonNull LatLng target) {
        LatLngBounds swapped = swapBoundsLatLon(original);
        if (swapped == null) return original;
        LatLng c1 = new LatLng(
                (original.southwest.latitude + original.northeast.latitude) / 2.0,
                (original.southwest.longitude + original.northeast.longitude) / 2.0
        );
        LatLng c2 = new LatLng(
                (swapped.southwest.latitude + swapped.northeast.latitude) / 2.0,
                (swapped.southwest.longitude + swapped.northeast.longitude) / 2.0
        );
        float[] r1 = new float[1];
        android.location.Location.distanceBetween(target.latitude, target.longitude, c1.latitude, c1.longitude, r1);
        float[] r2 = new float[1];
        android.location.Location.distanceBetween(target.latitude, target.longitude, c2.latitude, c2.longitude, r2);
        if (r1[0] > 2000 && r2[0] < r1[0] * 0.2f) return swapped;
        return original;
    }
    private static @NonNull LatLngBounds approxSquareBounds(@NonNull LatLng center, double halfSizeMeters) {
        double dLat = halfSizeMeters / 111_111.0;
        double cos = Math.cos(Math.toRadians(center.latitude));
        if (cos < 0.01) cos = 0.01;
        double dLng = halfSizeMeters / (111_111.0 * cos);
        LatLng sw = new LatLng(center.latitude - dLat, center.longitude - dLng);
        LatLng ne = new LatLng(center.latitude + dLat, center.longitude + dLng);
        return new LatLngBounds(sw, ne);
    }


    @Nullable
    private static LatLng centroid(@Nullable List<LatLng> pts) {
        if (pts == null || pts.isEmpty()) return null;
        double lat = 0, lng = 0;
        int n = 0;
        for (LatLng p : pts) {
            if (p == null) continue;
            lat += p.latitude;
            lng += p.longitude;
            n++;
        }
        if (n == 0) return null;
        return new LatLng(lat / n, lng / n);
    }

    private static boolean pointInPolygon(@NonNull LatLng p, @NonNull List<LatLng> poly) {
        boolean inside = false;
        double x = p.longitude;
        double y = p.latitude;
        int n = poly.size();
        for (int i = 0, j = n - 1; i < n; j = i++) {
            LatLng pi = poly.get(i);
            LatLng pj = poly.get(j);
            if (pi == null || pj == null) continue;
            double xi = pi.longitude, yi = pi.latitude;
            double xj = pj.longitude, yj = pj.latitude;
            boolean intersect = ((yi > y) != (yj > y)) &&
                    (x < (xj - xi) * (y - yi) / ((yj - yi) == 0 ? 1e-12 : (yj - yi)) + xi);
            if (intersect) inside = !inside;
        }
        return inside;
    }

    @Nullable
    private static LatLngBounds boundsOf(@Nullable List<LatLng> pts) {
        if (pts == null || pts.size() < 2) return null;
        double minLat = Double.POSITIVE_INFINITY, maxLat = Double.NEGATIVE_INFINITY;
        double minLng = Double.POSITIVE_INFINITY, maxLng = Double.NEGATIVE_INFINITY;
        for (LatLng p : pts) {
            if (p == null) continue;
            minLat = Math.min(minLat, p.latitude);
            maxLat = Math.max(maxLat, p.latitude);
            minLng = Math.min(minLng, p.longitude);
            maxLng = Math.max(maxLng, p.longitude);
        }
        if (!Double.isFinite(minLat) || !Double.isFinite(maxLat) || !Double.isFinite(minLng) || !Double.isFinite(maxLng)) return null;
        try {
            return new LatLngBounds(new LatLng(minLat, minLng), new LatLng(maxLat, maxLng));
        } catch (Exception e) {
            return null;
        }
    }

    private static boolean inBounds(@Nullable LatLng p, @Nullable LatLngBounds b) {
        if (p == null || b == null) return true;
        try {
            return p.latitude >= b.southwest.latitude && p.latitude <= b.northeast.latitude &&
                    p.longitude >= b.southwest.longitude && p.longitude <= b.northeast.longitude;
        } catch (Exception e) {
            return true;
        }
    }
    private void clearRemoteWallsOnly() {
        final int prevPolys = remoteWallPolygons.size();
        final int prevLines = remoteWallLines.size();
        for (Polygon p : remoteWallPolygons) {
            try { if (p != null) p.remove(); } catch (Exception ignore) {}
        }
        remoteWallPolygons.clear();
        for (Polyline l : remoteWallLines) {
            try { if (l != null) l.remove(); } catch (Exception ignore) {}
        }
        remoteWallLines.clear();

        if (prevPolys > 0 || prevLines > 0) {
            Log.d(TAG, "clearRemoteWallsOnly: removed polys=" + prevPolys + " lines=" + prevLines);
        }
    }

    private void drawVectorWalls(@NonNull FloorplanModels.Floor floor) {
        if (mMap == null) return;

        final List<LatLng> clipOutline = (selectedVenue != null) ? selectedVenue.outline : null;
        final LatLngBounds clipBounds = boundsOf(clipOutline);
        final boolean hasClip = (clipOutline != null && clipOutline.size() >= 3 && clipBounds != null);

        final int stroke = Color.argb(230, 0, 0, 0);
        final int fill   = Color.argb(0, 0, 0, 0);
        final float strokeW = 2.0f;

        if (hasClip) {
            try {
                PolygonOptions bg = new PolygonOptions()
                        .addAll(clipOutline)
                        .strokeWidth(0f)
                        .fillColor(Color.argb(140, 255, 255, 255))
                        .zIndex(2050f);
                Polygon bgPoly = mMap.addPolygon(bg);
                if (bgPoly != null) remoteWallPolygons.add(bgPoly);
            } catch (Exception ignore) {}
        }

        int keptPolys = 0;
        int keptLines = 0;

        if (floor.wallPolygons != null) {
            for (List<LatLng> ring : floor.wallPolygons) {
                if (ring == null || ring.size() < 3) continue;
                if (hasClip) {
                    LatLng c = centroid(ring);
                    if (c == null) continue;
                    if (!inBounds(c, clipBounds)) continue;
                    if (!pointInPolygon(c, clipOutline)) continue;
                }
                try {
                    PolygonOptions opts = new PolygonOptions()
                            .addAll(ring)
                            .strokeWidth(strokeW)
                            .strokeColor(stroke)
                            .fillColor(fill)
                            .zIndex(2100f);
                    Polygon p = mMap.addPolygon(opts);
                    if (p != null) {
                        remoteWallPolygons.add(p);
                        keptPolys++;
                    }
                } catch (Exception ignore) {}
            }
        }

        if (floor.wallLines != null) {
            for (List<LatLng> path : floor.wallLines) {
                if (path == null || path.size() < 2) continue;
                if (hasClip) {
                    LatLng c = centroid(path);
                    if (c == null) continue;
                    if (!inBounds(c, clipBounds)) continue;
                    if (!pointInPolygon(c, clipOutline)) continue;
                }
                try {
                    PolylineOptions opts = new PolylineOptions()
                            .addAll(path)
                            .width(strokeW)
                            .color(stroke)
                            .zIndex(2100f);
                    Polyline l = mMap.addPolyline(opts);
                    if (l != null) {
                        remoteWallLines.add(l);
                        keptLines++;
                    }
                } catch (Exception ignore) {}
            }
        }

        Log.d(TAG, "drawVectorWalls: keptPolys=" + keptPolys + " keptLines=" + keptLines +
                " totalPolys=" + (floor.wallPolygons != null ? floor.wallPolygons.size() : 0) +
                " totalLines=" + (floor.wallLines != null ? floor.wallLines.size() : 0) +
                (hasClip ? " clip=outline" : ""));
    }
    public void clearOverlay() {
        for (int i = 0; i < groundOverlays.length; i++) {
            if (groundOverlays[i] != null) {
                groundOverlays[i].remove();
                groundOverlays[i] = null;
            }
        }
        currentFloor = 0;
        clearRemoteOverlayOnly();
        clearRemoteWallsOnly();
        selectedVenue = null;
        selectedFloor = null;
    }
    private void clearRemoteOverlayOnly() {
        int n = 0;
        try {
            n = remoteOverlays.size();
            for (GroundOverlay o : remoteOverlays) {
                try { if (o != null) o.remove(); } catch (Exception ignore) {}
            }
            remoteOverlays.clear();
        } catch (Exception ignore) {}

        if (remoteOverlay != null) {
            try { remoteOverlay.remove(); } catch (Exception ignore) {}
            remoteOverlay = null;
        }
        if (n > 0) {
            Log.d(TAG, "clearRemoteOverlayOnly: removed remote overlays=" + n);
        }
    }
}

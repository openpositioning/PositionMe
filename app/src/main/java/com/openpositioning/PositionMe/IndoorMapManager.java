package com.openpositioning.PositionMe;



import android.graphics.Color;
import android.util.Log;

import com.google.android.gms.maps.GoogleMap;
import com.google.android.gms.maps.model.BitmapDescriptorFactory;
import com.google.android.gms.maps.model.GroundOverlay;
import com.google.android.gms.maps.model.GroundOverlayOptions;
import com.google.android.gms.maps.model.LatLng;
import com.google.android.gms.maps.model.LatLngBounds;
import com.google.android.gms.maps.model.Polyline;
import com.google.android.gms.maps.model.PolylineOptions;

import java.util.Arrays;
import java.util.List;

/**
 * Class used to manage indoor floor map overlays
 * Currently used by RecordingFragment
 * @see BuildingPolygon Describes the bounds of buildings and the methods to check if point is
 * in the building
 * @author Arun Gopalakrishnan
 */
public class IndoorMapManager {
    // Google Map 实例
    private GoogleMap gMap;
    // 存储当前的 GroundOverlay
    private GroundOverlay groundOverlay;
    // 当前用户位置
    private LatLng currentLocation;
    // 是否显示了室内地图
    private boolean isIndoorMapSet = false;
    // 当前楼层
    private int currentFloor;
    // 当前建筑的楼层高度
    private float floorHeight;

    // Nucleus Building 的楼层图片
    private final List<Integer> NUCLEUS_MAPS = Arrays.asList(
            R.drawable.nucleuslg, R.drawable.nucleusg, R.drawable.nucleus1,
            R.drawable.nucleus2, R.drawable.nucleus3);

    // Library Building 的楼层图片
    private final List<Integer> LIBRARY_MAPS = Arrays.asList(
            R.drawable.libraryg, R.drawable.library1, R.drawable.library2,
            R.drawable.library3);

    // Fleeming Building 的楼层图片
    private final List<Integer> fleeming_MAPS = Arrays.asList(
            R.drawable.f0g, R.drawable.f1, R.drawable.f2,
            R.drawable.f3);

    private final List<Integer> Hudson_MAPS = Arrays.asList(
            R.drawable.h0g, R.drawable.h1, R.drawable.h2);

    // Nucleus 和 Library 的边界
    private final LatLngBounds NUCLEUS = new LatLngBounds(
            BuildingPolygon.NUCLEUS_SW,
            BuildingPolygon.NUCLEUS_NE
    );
    private final LatLngBounds LIBRARY = new LatLngBounds(
            BuildingPolygon.LIBRARY_SW,
            BuildingPolygon.LIBRARY_NE
    );

    // Fleeming Building 的边界
    private final LatLngBounds FLEEMING = new LatLngBounds(
            new LatLng(55.9220823, -3.1732186), // ✅ 西南角 (SW)
            new LatLng(55.9225463, -3.1726908)  // ✅ 东北角 (NE)
    );




    // 各建筑的楼层高度
    public static final float NUCLEUS_FLOOR_HEIGHT = 4.2F;
    public static final float LIBRARY_FLOOR_HEIGHT = 3.6F;
    public static final float FLEEMING_FLOOR_HEIGHT = 3.6F;

    public static final float HUDSON_FLOOR_HEIGHT = 3.6F;

    /**
     * Constructor to set the map instance
     * @param map The map on which the indoor floor map overlays are set
     */
    public IndoorMapManager(GoogleMap map){
        this.gMap=map;
    }

    /**
     * Function to update the current location of user and display the indoor map
     * if user in building with indoor map available
     * @param currentLocation new location of user
     */
    public void setCurrentLocation(LatLng currentLocation){
        this.currentLocation=currentLocation;
        setBuildingOverlay();
    }

    /**
     * Function to obtain the current building's floor height
     * @return the floor height of the current building the user is in
     */
    public float getFloorHeight() {
        return floorHeight;
    }

    /**
     * Getter to obtain if currently an indoor floor map is being displayed
     * @return true if an indoor map is visible to the user, false otherwise
     */
    public boolean getIsIndoorMapSet(){
        return isIndoorMapSet;
    }

    /**
     * Setting the new floor of a user and displaying the indoor floor map accordingly
     * (if floor exists in building)
     * @param newFloor the floor the user is at
     * @param autoFloor flag if function called by auto-floor feature
     */
    public void setCurrentFloor(int newFloor, boolean autoFloor) {
        if (BuildingPolygon.inNucleus(currentLocation)){
            //Special case for nucleus when auto-floor is being used
            if (autoFloor) {
                // If nucleus add bias floor as lower-ground floor referred to as floor 0
                newFloor += 1;
            }
            // If within bounds and different from floor map currently being shown
            if (newFloor>=0 && newFloor<NUCLEUS_MAPS.size() && newFloor!=this.currentFloor) {
                groundOverlay.setImage(BitmapDescriptorFactory.fromResource(NUCLEUS_MAPS.get(newFloor)));
                this.currentFloor=newFloor;
            }
        }
        else if (BuildingPolygon.inLibrary(currentLocation)){
            // If within bounds and different from floor map currently being shown
            if (newFloor>=0 && newFloor<LIBRARY_MAPS.size() && newFloor!=this.currentFloor) {
                groundOverlay.setImage(BitmapDescriptorFactory.fromResource(LIBRARY_MAPS.get(newFloor)));
                this.currentFloor=newFloor;
            }
        }

        else if (BuildingPolygon.inFleeming(currentLocation)){
            // If within bounds and different from floor map currently being shown
            if (newFloor>=0 && newFloor<fleeming_MAPS.size() && newFloor!=this.currentFloor) {
                groundOverlay.setImage(BitmapDescriptorFactory.fromResource(fleeming_MAPS.get(newFloor)));
                this.currentFloor=newFloor;
            }
        }

        else if (BuildingPolygon.inFleeming(currentLocation)){
            // If within bounds and different from floor map currently being shown
            if (newFloor>=0 && newFloor<Hudson_MAPS.size() && newFloor!=this.currentFloor) {
                groundOverlay.setImage(BitmapDescriptorFactory.fromResource(Hudson_MAPS.get(newFloor)));
                this.currentFloor=newFloor;
            }
        }
    }

    /**
     * Increments the Current Floor and changes to higher floor's map (if a higher floor exists)
     */
    public void increaseFloor(){
        this.setCurrentFloor(currentFloor+1,false);
    }

    /**
     * Decrements the Current Floor and changes to the lower floor's map (if a lower floor exists)
     */
    public void decreaseFloor(){
        this.setCurrentFloor(currentFloor-1,false);
    }

    /**
     * Sets the map overlay for the building if user's current
     * location is in building and is not already set
     * Removes the overlay if user no longer in building
     */
    private void setBuildingOverlay() {
        try {
            if (BuildingPolygon.inNucleus(currentLocation) && !isIndoorMapSet) {
                groundOverlay = gMap.addGroundOverlay(new GroundOverlayOptions()
                        .image(BitmapDescriptorFactory.fromResource(R.drawable.nucleusg))
                        .positionFromBounds(NUCLEUS));
                isIndoorMapSet = true;
                currentFloor = 1;
                floorHeight = NUCLEUS_FLOOR_HEIGHT;
            }
            else if (BuildingPolygon.inLibrary(currentLocation) && !isIndoorMapSet) {
                groundOverlay = gMap.addGroundOverlay(new GroundOverlayOptions()
                        .image(BitmapDescriptorFactory.fromResource(R.drawable.libraryg))
                        .positionFromBounds(LIBRARY));
                isIndoorMapSet = true;
                currentFloor = 0;
                floorHeight = LIBRARY_FLOOR_HEIGHT;
            }
            else if (BuildingPolygon.inFleeming(currentLocation) && !isIndoorMapSet) {
                groundOverlay = gMap.addGroundOverlay(new GroundOverlayOptions()
                        .image(BitmapDescriptorFactory.fromResource(R.drawable.f0g))
                        .position(BuildingPolygon.Fleeming_CENTER, 26f, 77f) // 设置宽高
                        .bearing(-31)); // 设置旋转角度
                isIndoorMapSet = true;
                currentFloor = 0;
                floorHeight = FLEEMING_FLOOR_HEIGHT;
            }
            else if (BuildingPolygon.inHudson(currentLocation) && !isIndoorMapSet) {
                groundOverlay = gMap.addGroundOverlay(new GroundOverlayOptions()
                        .image(BitmapDescriptorFactory.fromResource(R.drawable.h0g))
                        .position(BuildingPolygon.Hudson_CENTER, 35f, 18f) // 设置宽高
                        .bearing(-31)); // 设置旋转角度
                isIndoorMapSet = true;
                currentFloor = 0;
                floorHeight = FLEEMING_FLOOR_HEIGHT;
            }
            else if (!BuildingPolygon.inLibrary(currentLocation) &&
                    !BuildingPolygon.inNucleus(currentLocation) && isIndoorMapSet &&
                    !BuildingPolygon.inFleeming(currentLocation) && isIndoorMapSet&&
                    !BuildingPolygon.inHudson(currentLocation) && isIndoorMapSet) {
                if (groundOverlay != null) {
                    groundOverlay.remove();  // ✅ **确保不是 null 再删除**
                    groundOverlay = null;
                }
                isIndoorMapSet = false;
                currentFloor = 0;
            }

        } catch (Exception ex) {
            Log.e("Error with overlay, Exception:", ex.toString());
        }
    }


    /**
     * Function used to set the indication of available floor maps for building using green Polylines
     * along the building's boundaries.
     */
    public void setIndicationOfIndoorMap(){
        //Indicator for Nucleus Building
        List<LatLng> points=BuildingPolygon.NUCLEUS_POLYGON;
        // Closing Boundary
        points.add(BuildingPolygon.NUCLEUS_POLYGON.get(0));
        gMap.addPolyline(new PolylineOptions().color(Color.GREEN)
                .addAll(points));

        // Indicator for the Library Building
        points=BuildingPolygon.LIBRARY_POLYGON;
        // Closing Boundary
        points.add(BuildingPolygon.LIBRARY_POLYGON.get(0));
        gMap.addPolyline(new PolylineOptions().color(Color.GREEN)
                .addAll(points));

        // 直接使用 4 个点定义 Fleeming Building 的边界
        List<LatLng> fleemingPolygon = Arrays.asList(
                new LatLng(55.9221059, -3.1723130), // 西南 (Southwest)
                new LatLng(55.9222226, -3.1719519), // 东南 (Southeast)
                new LatLng(55.9228053, -3.1726003), // 东北 (Northeast)
                new LatLng(55.9226930, -3.1729124), // 西北 (Northwest)
                new LatLng(55.9221059, -3.1723130)  // **闭合边界**
        );

        // 在 Google Maps 上绘制绿色 Polyline
        gMap.addPolyline(new PolylineOptions()
                .color(Color.GREEN) // 绿色边界
                .width(5)           // 线条宽度
                .addAll(fleemingPolygon));

        List<LatLng> HudsonPolygon = Arrays.asList(
                new LatLng(55.9223633, -3.1715301), // 西南 (Southwest)
                new LatLng(55.9225434, -3.1710165), // 东南 (Southeast)
                new LatLng(55.9226656, -3.1711522), // 东北 (Northeast)
                new LatLng(55.9224837, -3.1716374), // 西北 (Northwest)
                new LatLng(55.9223633, -3.1715301)  // **闭合边界**
        );

        // 在 Google Maps 上绘制绿色 Polyline
        gMap.addPolyline(new PolylineOptions()
                .color(Color.GREEN) // 绿色边界
                .width(5)           // 线条宽度
                .addAll(HudsonPolygon));

    }



}


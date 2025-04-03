package com.openpositioning.PositionMe.presentation.trajmap;

import android.content.Context;

import com.google.android.gms.maps.GoogleMap;
import com.google.android.gms.maps.model.BitmapDescriptorFactory;
import com.google.android.gms.maps.model.LatLng;
import com.google.android.gms.maps.model.Marker;
import com.google.android.gms.maps.model.MarkerOptions;
import com.openpositioning.PositionMe.R;
import com.openpositioning.PositionMe.utils.UtilFunctions;

import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/**
 * Utility class for managing and updating markers on a Google Map, representing various points of interest within a building,
 * such as medical rooms, emergency exits, lifts, toilets, drinking water fountains, and accessible routes.
 *
 * This class provides static methods to update the markers based on the current floor and building. Each method clears any
 * existing markers of the specified type, retrieves the relevant locations for the current floor and building (currently
 * hardcoded for the "nucleus" building), and adds new markers at those locations.
 *
 * Marker icons are created using {@link UtilFunctions#getBitmapFromVector(Context, int)} to convert vector drawables into
 * BitmapDescriptor objects suitable for Google Map markers.
 *
 * All methods require a {@link GoogleMap} instance to add markers to, the current floor and building as integers and strings
 * respectively, a {@link List} to store the created {@link Marker} objects (which is cleared and updated by each method),
 * and a {@link Context} for accessing resources.
 *
 * @author Lai Gan
 */

public class TrajectoryMapMaker {

    // updateMedicalRoomMarkers
    public static void updateMedicalRoomMarkers(
            GoogleMap gMap,
            int currentFloor,
            String currentBuilding,
            List<Marker> medicalRoomMarkers,
            Context context
    ) {
        // Remove existing markers first to avoid duplicates
        for (Marker marker : medicalRoomMarkers) {
            marker.remove();
        }
        medicalRoomMarkers.clear();

        // Determine the placement of MedicalRoom based on floor and building name.
        List<LatLng> medicalRoomLocations = null;
        if (currentFloor == 0 && Objects.equals(currentBuilding, "nucleus")) {
            medicalRoomLocations = Arrays.asList(
            );
        }
        else if (currentFloor == 1 && Objects.equals(currentBuilding, "nucleus")) {
            medicalRoomLocations = Arrays.asList(
            );
        }
        else if (currentFloor == 2 && Objects.equals(currentBuilding, "nucleus")) {
            medicalRoomLocations = Arrays.asList(
                    new LatLng(55.923291498633766, -3.1743306666612625)
            );
        }
        else if (currentFloor == 3 && Objects.equals(currentBuilding, "nucleus")) {
            medicalRoomLocations = Arrays.asList(
            );
        }
        else if (currentFloor == 4 && Objects.equals(currentBuilding, "nucleus")) {
            medicalRoomLocations = Arrays.asList(
            );
        }

        // If not null, add Markers sequentially.
        if (medicalRoomLocations != null) {
            for (LatLng location : medicalRoomLocations) {
                Marker marker = gMap.addMarker(new MarkerOptions()
                        .position(location)
                        .flat(true)
                        .title("Medical Room")
                        .icon(BitmapDescriptorFactory.fromBitmap(
                                UtilFunctions.getBitmapFromVector(context, R.drawable.iso_first_aid_icon)))
                );
                if (marker != null) {
                    medicalRoomMarkers.add(marker);
                }
            }
        }
    }


    /**
     * Updates the emergency exit markers on the map based on the current floor and building.
     * Clears any existing markers and adds new markers for the specified location.
     *
     * @param gMap The GoogleMap instance to add markers to.
     * @param currentFloor The current floor of the building.
     * @param currentBuilding The current building name.
     * @param emergencyExitMarkers A list to store the added emergency exit markers.  This list is cleared and repopulated with new markers.
     * @param context The application context, used for accessing resources.
     */
    public static void updateEmergencyExitMarkers(
            GoogleMap gMap,
            int currentFloor,
            String currentBuilding,
            List<Marker> emergencyExitMarkers,
            Context context
    ) {
        for (Marker marker : emergencyExitMarkers) {
            marker.remove();
        }
        emergencyExitMarkers.clear();

        List<LatLng> exitLocations = null;

        if (currentFloor == 0 && Objects.equals(currentBuilding, "nucleus")) {
            exitLocations = Arrays.asList(
                    new LatLng(55.92327684586336, -3.174331672489643),
                    new LatLng(55.92305836864246, -3.174259588122368),
                    new LatLng(55.923040334354255, -3.174433596432209)
            );
        }
        else if (currentFloor == 1 && Objects.equals(currentBuilding, "nucleus")) {
            exitLocations = Arrays.asList(
                    new LatLng(55.922839326615474, -3.17451573908329),
                    new LatLng(55.92283913875728, -3.1742961332201958),
                    new LatLng(55.92330295784777, -3.174157999455929),
                    new LatLng(55.92287501965453, -3.174012154340744),
                    new LatLng(55.92301422219288, -3.173900842666626),
                    new LatLng(55.92308936505573, -3.1738971546292305),
                    new LatLng(55.923300515720484, -3.1740912795066833)
            );
        }
        else if (currentFloor == 2 && Objects.equals(currentBuilding, "nucleus")) {
            exitLocations = Arrays.asList(
                    new LatLng(55.92303563792365, -3.174491263926029),
                    new LatLng(55.92327647015123, -3.174472488462925)
            );
        }
        else if (currentFloor == 3 && Objects.equals(currentBuilding, "nucleus")) {
            exitLocations = Arrays.asList(
            );
        }
        else if (currentFloor == 4 && Objects.equals(currentBuilding, "nucleus")) {
            exitLocations = Arrays.asList(
            );
        }


        if (exitLocations != null) {
            for (LatLng location : exitLocations) {
                Marker marker = gMap.addMarker(new MarkerOptions()
                        .position(location)
                        .flat(true)
                        .title("Emergency Exit")
                        .icon(BitmapDescriptorFactory.fromBitmap(
                                UtilFunctions.getBitmapFromVector(context, R.drawable.iso_emergency_exit)))
                );
                if (marker != null) {
                    emergencyExitMarkers.add(marker);
                }
            }
        }
    }

    /**
     * Updates the lift markers on the Google Map based on the current floor and building.
     * This method clears any existing lift markers and adds new markers at predefined locations
     * for the specified floor and building. Currently, only the "nucleus" building is supported.
     *
     * @param gMap The Google Map instance on which to display the markers.
     * @param currentFloor The current floor of the building.
     * @param currentBuilding The current building name (e.g., "nucleus").
     * @param liftMarkers A list to store the Marker objects representing the lifts.  This list is cleared and updated by the method.
     * @param context The application context, used to access resources.
     */
    public static void updateLiftMarkers(
            GoogleMap gMap,
            int currentFloor,
            String currentBuilding,
            List<Marker> liftMarkers,
            Context context
    ) {
        for (Marker marker : liftMarkers) {
            marker.remove();
        }
        liftMarkers.clear();

        List<LatLng> liftLocations = null;
        if (currentFloor == 0 && Objects.equals(currentBuilding, "nucleus")) {
            liftLocations = Arrays.asList(
                    new LatLng(55.92303883149652, -3.1743890047073364),
                    new LatLng(55.923040334354255, -3.1743494421243668),
                    new LatLng(55.92304277649792, -3.1743118911981583)
            );
        }
        else if (currentFloor == 1 && Objects.equals(currentBuilding, "nucleus")) {
            liftLocations = Arrays.asList(
                    new LatLng(55.923027560061676, -3.1743665412068367),
                    new LatLng(55.92303075363521, -3.17432664334774),
                    new LatLng(55.923030565777935, -3.174288421869278)
            );
        }
        else if (currentFloor == 2 && Objects.equals(currentBuilding, "nucleus")) {
            liftLocations = Arrays.asList(
                    new LatLng(55.92304484292707, -3.17434910684824),
                    new LatLng(55.923045970070206, -3.1743139028549194),
                    new LatLng(55.92304390364111, -3.1743836402893066)
            );
        }
        else if (currentFloor == 3 && Objects.equals(currentBuilding, "nucleus")) {
            liftLocations = Arrays.asList(
                    new LatLng(55.92304484292707, -3.17434910684824),
                    new LatLng(55.923045970070206, -3.1743139028549194),
                    new LatLng(55.92304390364111, -3.1743836402893066)
            );
        }
        else if (currentFloor == 4 && Objects.equals(currentBuilding, "nucleus")) {
            liftLocations = Arrays.asList(
                    new LatLng(55.92304484292707, -3.17434910684824),
                    new LatLng(55.923045970070206, -3.1743139028549194),
                    new LatLng(55.92304390364111, -3.1743836402893066)
            );
        }

        if (liftLocations != null) {
            for (LatLng location : liftLocations) {
                Marker marker = gMap.addMarker(new MarkerOptions()
                        .position(location)
                        .flat(true)
                        .title("Lift")
                        .icon(BitmapDescriptorFactory.fromBitmap(
                                UtilFunctions.getBitmapFromVector(context, R.drawable.iso_lift)))
                );
                if (marker != null) {
                    liftMarkers.add(marker);
                }
            }
        }
    }

    /**
     * Updates the markers on the map representing accessible toilet locations based on the current floor and building.
     *
     * This method clears any existing accessible toilet markers from the map and the provided list,
     * then adds new markers for the accessible toilets on the specified floor of the given building.
     * The toilet locations are hardcoded for each floor and building combination.  If no accessible
     * toilets are defined for the current floor/building, no new markers are added.
     *
     * @param gMap The GoogleMap object on which to display the markers.
     * @param currentFloor The current floor number of the building.
     * @param currentBuilding The name or identifier of the current building.
     * @param accessibleToiletMarkers A list to store the Marker objects representing the accessible toilets.  This list is cleared and updated by the method.
     * @param context The application context, used to access resources like the toilet icon.
     */
    public static void updateAccessibleToiletMarkers(
            GoogleMap gMap,
            int currentFloor,
            String currentBuilding,
            List<Marker> accessibleToiletMarkers,
            Context context
    ) {
        for (Marker marker : accessibleToiletMarkers) {
            marker.remove();
        }
        accessibleToiletMarkers.clear();

        List<LatLng> toiletLocations = null;

        if (currentFloor == 0 && Objects.equals(currentBuilding, "nucleus")) {
            toiletLocations = Arrays.asList(
            );
        }
        else if (currentFloor == 1 && Objects.equals(currentBuilding, "nucleus")) {
            toiletLocations = Arrays.asList(
                    new LatLng(55.923273276597925, -3.1740865856409073),
                    new LatLng(55.922906391930155, -3.174562007188797)
            );
        }
        else if (currentFloor == 2 && Objects.equals(currentBuilding, "nucleus")) {
            toiletLocations = Arrays.asList(
            );
        }
        else if (currentFloor == 3 && Objects.equals(currentBuilding, "nucleus")) {
            toiletLocations = Arrays.asList(
            );
        }
        else if (currentFloor == 4 && Objects.equals(currentBuilding, "nucleus")) {
            toiletLocations = Arrays.asList(
            );
        }

        if (toiletLocations != null) {
            for (LatLng location : toiletLocations) {
                Marker marker = gMap.addMarker(new MarkerOptions()
                        .position(location)
                        .flat(true)
                        .title("Accessible Toilet")
                        .icon(BitmapDescriptorFactory.fromBitmap(
                                UtilFunctions.getBitmapFromVector(context, R.drawable.iso_accessible__toilet)))
                );
                if (marker != null) {
                    accessibleToiletMarkers.add(marker);
                }
            }
        }
    }

    /**
     * Updates the drinking water markers on the map based on the current floor and building.
     * Clears any existing markers and adds new markers for drinking water locations on the specified floor of the building.
     *
     * @param gMap The GoogleMap instance to add markers to.
     * @param currentFloor The current floor of the building.
     * @param currentBuilding The current building.
     * @param drinkingWaterMarkers A list to store the added drinking water markers.  Markers that are added to the map are also added to this list so they can be cleared later.
     * @param context The application context.
     */ // Example: Update Drinking Water marker
    public static void updateDrinkingWaterMarkers(
            GoogleMap gMap,
            int currentFloor,
            String currentBuilding,
            List<Marker> drinkingWaterMarkers,
            Context context
    ) {
        for (Marker marker : drinkingWaterMarkers) {
            marker.remove();
        }
        drinkingWaterMarkers.clear();

        List<LatLng> waterLocations = null;

        if (currentFloor == 0 && Objects.equals(currentBuilding, "nucleus")) {
            waterLocations = Arrays.asList(
            );
        }
        else if (currentFloor == 1 && Objects.equals(currentBuilding, "nucleus")) {
            waterLocations = Arrays.asList(
                    new LatLng(55.922907331219456, -3.1744718179106712)
            );
        }
        else if (currentFloor == 2 && Objects.equals(currentBuilding, "nucleus")) {
            waterLocations = Arrays.asList(
            );
        }
        else if (currentFloor == 3 && Objects.equals(currentBuilding, "nucleus")) {
            waterLocations = Arrays.asList(
            );
        }
        else if (currentFloor == 4 && Objects.equals(currentBuilding, "nucleus")) {
            waterLocations = Arrays.asList(
            );
        }

        if (waterLocations != null) {
            for (LatLng location : waterLocations) {
                Marker marker = gMap.addMarker(new MarkerOptions()
                        .position(location)
                        .flat(true)
                        .title("Drinking Water")
                        .icon(BitmapDescriptorFactory.fromBitmap(
                                UtilFunctions.getBitmapFromVector(context, R.drawable.iso_drinking_water)))
                );
                if (marker != null) {
                    drinkingWaterMarkers.add(marker);
                }
            }
        }
    }

    /**
     * Updates the toilet markers displayed on the map based on the currently selected floor and building.
     * Clears any existing toilet markers and adds new ones if toilet locations are defined for the
     * specified floor and building.
     *
     * @param gMap The Google Map instance on which to display the markers.
     * @param currentFloor The currently selected floor of the building.
     * @param currentBuilding The currently selected building.
     * @param toiletMarkers A list to store the currently displayed toilet markers.  This list is cleared and updated within the method.
     * @param context The application context, used to access resources like the toilet icon.
     */
    public static void updateToiletMarkers(
            GoogleMap gMap,
            int currentFloor,
            String currentBuilding,
            List<Marker> toiletMarkers,
            Context context
    ) {
        for (Marker marker : toiletMarkers) {
            marker.remove();
        }
        toiletMarkers.clear();

        List<LatLng> toiletLocations = null;

        if (currentFloor == 0 && Objects.equals(currentBuilding, "nucleus")) {
            toiletLocations = Arrays.asList(
                    new LatLng(55.92308617148703, -3.174508698284626)
            );
        }
        else if (currentFloor == 1 && Objects.equals(currentBuilding, "nucleus")) {
            toiletLocations = Arrays.asList(
                    new LatLng(55.9229417091921, -3.174556642770767)
            );
        }
        else if (currentFloor == 2 && Objects.equals(currentBuilding, "nucleus")) {
            toiletLocations = Arrays.asList(
                    new LatLng(55.92308617148703, -3.174508698284626)
            );
        }
        else if (currentFloor == 3 && Objects.equals(currentBuilding, "nucleus")) {
            toiletLocations = Arrays.asList(
                    new LatLng(55.92308617148703, -3.174508698284626)
            );
        }
        else if (currentFloor == 4 && Objects.equals(currentBuilding, "nucleus")) {
            toiletLocations = Arrays.asList(
            );
        }

        if (toiletLocations != null) {
            for (LatLng location : toiletLocations) {
                Marker marker = gMap.addMarker(new MarkerOptions()
                        .position(location)
                        .flat(true)
                        .title("Toilet")
                        .icon(BitmapDescriptorFactory.fromBitmap(
                                UtilFunctions.getBitmapFromVector(context, R.drawable.iso_toilet_icon)))
                );
                if (marker != null) {
                    toiletMarkers.add(marker);
                }
            }
        }
    }


    /**
     * Updates the markers indicating accessible routes on the map.
     * This method clears any existing accessible route markers and adds new markers based on the
     * current floor and building.  The locations of accessible routes are hardcoded for the "nucleus"
     * building and specific floors.  If the current location doesn't correspond to a known accessible
     * route location, no markers are added.
     *
     * @param gMap The GoogleMap instance on which to display the markers.
     * @param currentFloor The current floor of the building.
     * @param currentBuilding The current building name.
     * @param accessibleRouteMarkers A list to store the Marker objects representing accessible routes.
     *                               Markers added in this method are appended to this list.  Markers
     *                               previously present in this list will be removed from the map and
     *                               cleared from the list.
     * @param context The application context.  Used for accessing resources (e.g., the marker icon).
     */
    public static void updateAccessibleRouteMarkers(
            GoogleMap gMap,
            int currentFloor,
            String currentBuilding,
            List<Marker> accessibleRouteMarkers,
            Context context
    ) {
        for (Marker marker : accessibleRouteMarkers) {
            marker.remove();
        }
        accessibleRouteMarkers.clear();

        List<LatLng> accessibleLocations = null;

        if (currentFloor == 0 && Objects.equals(currentBuilding, "nucleus")) {
            accessibleLocations = Arrays.asList(
            );
        }
        else if (currentFloor == 1 && Objects.equals(currentBuilding, "nucleus")) {
            accessibleLocations = Arrays.asList(
                    new LatLng(55.92284627736777, -3.174579441547394),
                    new LatLng(55.92327102232486, -3.1744134798645973)
            );
        }
        else if (currentFloor == 2 && Objects.equals(currentBuilding, "nucleus")) {
            accessibleLocations = Arrays.asList(
            );
        }
        else if (currentFloor == 3 && Objects.equals(currentBuilding, "nucleus")) {
            accessibleLocations = Arrays.asList(
            );
        }
        else if (currentFloor == 4 && Objects.equals(currentBuilding, "nucleus")) {
            accessibleLocations = Arrays.asList(
            );
        }

        if (accessibleLocations != null) {
            for (LatLng location : accessibleLocations) {
                Marker marker = gMap.addMarker(new MarkerOptions()
                        .position(location)
                        .flat(true)
                        .title("Accessible Route")
                        .icon(BitmapDescriptorFactory.fromBitmap(
                                UtilFunctions.getBitmapFromVector(context, R.drawable.iso_symbol_of_access)))
                );
                if (marker != null) {
                    accessibleRouteMarkers.add(marker);
                }
            }
        }
    }

}

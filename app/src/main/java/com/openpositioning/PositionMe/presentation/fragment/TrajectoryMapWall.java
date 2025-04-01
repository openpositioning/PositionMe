package com.openpositioning.PositionMe.presentation.fragment;

import android.graphics.Color;
import com.google.android.gms.maps.GoogleMap;
import com.google.android.gms.maps.model.LatLng;
import com.google.android.gms.maps.model.PolylineOptions;

import java.util.Arrays;
import java.util.List;

public class TrajectoryMapWall {

    public static void drawWalls(GoogleMap gMap) {
        if (gMap == null) return;

        List<LatLng> groundPointsAlderGroundWall = Arrays.asList(
                new LatLng(55.92331122350826,  -3.1744644418358803),
                new LatLng(55.9232702709005,  -3.1744711473584175),
                new LatLng(55.92327064661266,  -3.17419420927763),
                new LatLng(55.92311848287593, -3.17419420927763),
                new LatLng(55.9231173557349,  -3.174203597009182),
                new LatLng(55.92302417863057, -3.17420594394207)
        );

        for (int i = 0; i < groundPointsAlderGroundWall.size() - 1; i++) {
            LatLng start = groundPointsAlderGroundWall.get(i);
            LatLng end = groundPointsAlderGroundWall.get(i + 1);

            gMap.addPolyline(new PolylineOptions()
                    .add(start, end)
                    .color(Color.DKGRAY)
                    .width(8f)
                    .zIndex(3));  // 保证显示在轨迹上层
        }

        List<LatLng> groundPointsELMGroundWall = Arrays.asList(
                new LatLng(55.92328548724146, -3.1738948076963425),
                new LatLng(55.92328623866554, -3.1741197779774666),
//                new LatLng(55.923244346750266, -3.1741201132535934),
//                new LatLng(55.923244346750266, -3.1741097196936607),
//                new LatLng(55.923227063973826, -3.174109384417534),
//                new LatLng(55.923227439686436, -3.1741217896342278),
////                new LatLng(55.92322161614051, -3.174121454358101),
//                new LatLng(55.92322124042784, -3.1741097196936607),
//                new LatLng(55.92318366914247, -3.1741097196936607),
//                new LatLng(55.923184232712, -3.1741217896342278),
                new LatLng(55.92311998573056, -3.174123466014862),
//                new LatLng(55.92311923430324, -3.1741107255220413),
//                new LatLng(55.92326613806612, -3.1740979850292206),
//                new LatLng(55.92326369593649, -3.173898495733738),
//                new LatLng(55.9231757791679, -3.1738998368382454),
                new LatLng(55.92311923430324, -3.174075521528721),
                new LatLng(55.92297965642705, -3.174077533185482),
                new LatLng(55.92297195426851, -3.1738948076963425),
                new LatLng(55.92291634839558, -3.1738948076963425),
                new LatLng(55.92289624760431, -3.1739461049437523),
                new LatLng(55.92293043772902, -3.1739886850118637),
                new LatLng(55.9229214205562, -3.174011819064617)
        );

        for (int i = 0; i < groundPointsELMGroundWall.size() - 1; i++) {
            LatLng start = groundPointsELMGroundWall.get(i);
            LatLng end = groundPointsELMGroundWall.get(i + 1);

            gMap.addPolyline(new PolylineOptions()
                    .add(start, end)
                    .color(Color.DKGRAY)
                    .width(8f)
                    .zIndex(3));  // 保证显示在轨迹上层
        }

        List<LatLng> groundPointsNCCafeWall = Arrays.asList(
                new LatLng(55.92284458664434, -3.1745556369423866),
                new LatLng(55.92283951447364, -3.174089267849922),
                new LatLng(55.92287877681464, -3.174116089940071),
                new LatLng(55.92289474474097, -3.1740785390138626)
        );

        for (int i = 0; i < groundPointsNCCafeWall.size() - 1; i++) {
            LatLng start = groundPointsNCCafeWall.get(i);
            LatLng end = groundPointsNCCafeWall.get(i + 1);

            gMap.addPolyline(new PolylineOptions()
                    .add(start, end)
                    .color(Color.DKGRAY)
                    .width(8f)
                    .zIndex(3));  // 保证显示在轨迹上层
        }
        List<LatLng> groundPointsNCKitchenWall = Arrays.asList(
                new LatLng(55.92290207119912, -3.1745777651667595),
                new LatLng(55.92290244691486, -3.1745345145463943),
                new LatLng(55.92292480199627, -3.1745100393891335),
                new LatLng(55.922924989854025, -3.1744228675961494),
                new LatLng(55.92298397714947, -3.1744201853871346),
                new LatLng(55.922984352864454, -3.174312226474285),
                new LatLng(55.92290488906723, -3.174312561750412),
                new LatLng(55.92290582835658, -3.174174763262272)
        );

        for (int i = 0; i < groundPointsNCKitchenWall.size() - 1; i++) {
            LatLng start = groundPointsNCKitchenWall.get(i);
            LatLng end = groundPointsNCKitchenWall.get(i + 1);

            gMap.addPolyline(new PolylineOptions()
                    .add(start, end)
                    .color(Color.DKGRAY)
                    .width(8f)
                    .zIndex(3));  // 保证显示在轨迹上层
        }

        List<LatLng> groundPointsNCGroundLiftWall = Arrays.asList(
                new LatLng(55.9229062040723, -3.174283392727375),
                new LatLng(55.92299337002261, -3.174283392727375),
                new LatLng(55.922997315028674, -3.1744228675961494),
                new LatLng(55.92300915004439, -3.174421191215515),
                new LatLng(55.92300990147385, -3.174336366355419),
                new LatLng(55.92302549363161, -3.174336366355419),
                new LatLng(55.92302267577222, -3.1742676347494125)
        );

        for (int i = 0; i < groundPointsNCGroundLiftWall.size() - 1; i++) {
            LatLng start = groundPointsNCGroundLiftWall.get(i);
            LatLng end = groundPointsNCGroundLiftWall.get(i + 1);

            gMap.addPolyline(new PolylineOptions()
                    .add(start, end)
                    .color(Color.DKGRAY)
                    .width(8f)
                    .zIndex(3));  // 保证显示在轨迹上层
        }
        List<LatLng> groundPointsNCGroundNorthWall = Arrays.asList(
                new LatLng(55.9233059635427, -3.1744245439767838),
                new LatLng(55.92330370927152, -3.1738927960395813)
        );

        for (int i = 0; i < groundPointsNCGroundNorthWall.size() - 1; i++) {
            LatLng start = groundPointsNCGroundNorthWall.get(i);
            LatLng end = groundPointsNCGroundNorthWall.get(i + 1);

            gMap.addPolyline(new PolylineOptions()
                    .add(start, end)
                    .color(Color.DKGRAY)
                    .width(8f)
                    .zIndex(3));  // 保证显示在轨迹上层
        }
    }
}

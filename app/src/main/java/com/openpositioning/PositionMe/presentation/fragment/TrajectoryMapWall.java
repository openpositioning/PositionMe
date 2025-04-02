package com.openpositioning.PositionMe.presentation.fragment;

import android.graphics.Color;
import com.google.android.gms.maps.GoogleMap;
import com.google.android.gms.maps.model.LatLng;
import com.google.android.gms.maps.model.Polyline;
import com.google.android.gms.maps.model.PolylineOptions;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;


public class TrajectoryMapWall {

    // 保存所有墙体线条的引用，方便 remove
    private static final List<Polyline> wallPolylines = new ArrayList<>();

    public static void drawWalls(GoogleMap gMap, int currentFloor, String currentBuilding) {
        if (gMap == null) return;

        // 🔄 先清除旧的墙
        for (Polyline line : wallPolylines) {
            line.remove();
        }
        wallPolylines.clear();

        // ✅ 判断楼层 + 建筑（比如只在 nucleus 的 ground 楼才画）
        if (currentFloor == 1 && Objects.equals(currentBuilding, "nucleus")) {

//            // 🧱 示例：第一段墙
//            List<LatLng> groundPointsAlderGroundWall = Arrays.asList(
//                    new LatLng(55.92331122350826,  -3.1744644418358803),
//                    new LatLng(55.9232702709005,  -3.1744711473584175),
//                    new LatLng(55.92327064661266,  -3.17419420927763),
//                    new LatLng(55.92311848287593, -3.17419420927763),
//                    new LatLng(55.9231173557349,  -3.174203597009182),
//                    new LatLng(55.92302417863057, -3.17420594394207)
//            );
//            addWallPolyline(gMap, groundPointsAlderGroundWall);
//
//            // 🧱 示例：第二段墙
//            List<LatLng> groundPointsELMGroundWall = Arrays.asList(
//                    new LatLng(55.92328548724146, -3.1738948076963425),
//                    new LatLng(55.92328623866554, -3.1741197779774666),
//                    new LatLng(55.92311998573056, -3.174123466014862),
//                    new LatLng(55.92311923430324, -3.174075521528721),
//                    new LatLng(55.92297965642705, -3.174077533185482),
//                    new LatLng(55.92297195426851, -3.1738948076963425),
//                    new LatLng(55.92291634839558, -3.1738948076963425),
//                    new LatLng(55.92289624760431, -3.1739461049437523),
//                    new LatLng(55.92293043772902, -3.1739886850118637),
//                    new LatLng(55.9229214205562, -3.174011819064617)
//            );
//            addWallPolyline(gMap, groundPointsELMGroundWall);

            // 🧱 示例：第一段墙
            List<LatLng> groundPointsEasierAlderGroundWall = Arrays.asList(
                    new LatLng(55.92302380291602, -3.1742052733898163),
                    new LatLng(55.9233059635427, -3.174182139337063)
            );
            addWallPolyline(gMap, groundPointsEasierAlderGroundWall);

            // 🧱 示例：第二段墙
            List<LatLng> groundPointsEasierELMGroundWall = Arrays.asList(
                    new LatLng(55.92297946856955, -3.1740735098719597),
                    new LatLng(55.9231190464464, -3.174114413559437),
                    new LatLng(55.92330183071211, -3.174116760492325)
            );
            addWallPolyline(gMap, groundPointsEasierELMGroundWall);


            // 🧱 示例：第三段墙
            List<LatLng> groundPointsNCCafeWall = Arrays.asList(
                    new LatLng(55.92284458664434, -3.1745556369423866),
                    new LatLng(55.92283951447364, -3.174089267849922),
                    new LatLng(55.92287877681464, -3.174116089940071),
                    new LatLng(55.92289474474097, -3.1740785390138626)
            );
            addWallPolyline(gMap, groundPointsNCCafeWall);

            // 🧱 示例：第四段墙
            List<LatLng> groundPointsNCKitchenWall = Arrays.asList(
                    new LatLng(55.92290207119912, -3.1745777651667595),
//                    new LatLng(55.92290244691486, -3.1745345145463943),
//                    new LatLng(55.92292480199627, -3.1745100393891335),
//                    new LatLng(55.922924989854025, -3.1744228675961494),
//                    new LatLng(55.92298397714947, -3.1744201853871346),
//                    new LatLng(55.922984352864454, -3.174312226474285),
//                    new LatLng(55.92290488906723, -3.174312561750412),
                    new LatLng(55.92290582835658, -3.174174763262272)
            );
            addWallPolyline(gMap, groundPointsNCKitchenWall);


            // 🧱 示例：第五段墙
            List<LatLng> groundPointsNCGroundLiftWall = Arrays.asList(
                    new LatLng(55.9229062040723, -3.174283392727375),
                    new LatLng(55.92299337002261, -3.174283392727375),
                    new LatLng(55.922997315028674, -3.1744228675961494),
                    new LatLng(55.92300915004439, -3.174421191215515)
            );
            addWallPolyline(gMap, groundPointsNCGroundLiftWall);

            // 🧱 示例：第六段墙
            List<LatLng> groundPointsNCGroundNorthWall = Arrays.asList(
                    new LatLng(55.9233059635427, -3.1744245439767838),
                    new LatLng(55.92330370927152, -3.1738927960395813)
            );
            addWallPolyline(gMap, groundPointsNCGroundNorthWall);
        }

        // ✅ 判断楼层 + 建筑（比如只在 nucleus 的 first 楼才画）
        if (currentFloor == 2 && Objects.equals(currentBuilding, "nucleus")) {

            // 🧱 示例：第一段墙
            List<LatLng> firstPointsCareersServiceWall = Arrays.asList(
                    new LatLng(55.92301347076353, -3.174433261156082),
                    new LatLng(55.92301290719148, -3.1743400543928146),
                    new LatLng(55.92289831404127, -3.1743377074599266),
                    new LatLng(55.922897562609656, -3.1745868176221848)
            );
            addWallPolyline(gMap, firstPointsCareersServiceWall);

            // 🧱 示例：第二段墙
            List<LatLng> firstPointsAtriumWall = Arrays.asList(
                    new LatLng(55.923013282906176, -3.174215666949749),
                    new LatLng(55.92301459790755, -3.1742947921156883),
                    new LatLng(55.92292968629804, -3.174295462667942),
                    new LatLng(55.922930249871285, -3.1741851568222046),
                    new LatLng(55.922989800731074, -3.1741848215460777)
            );
            addWallPolyline(gMap, firstPointsAtriumWall);

            // 🧱 示例：第三段墙
            List<LatLng> firstPointsSouthWall = Arrays.asList(
                    new LatLng(55.92286825676544, -3.1745848059654236),
                    new LatLng(55.92287220178422, -3.17430317401886),
                    new LatLng(55.92289962904656, -3.174302503466606),
                    new LatLng(55.922905264782976, -3.174174427986145),
                    new LatLng(55.922836133026166, -3.174099326133728),
                    new LatLng(55.9229214205562, -3.1738978251814842),
                    new LatLng(55.922979844284555, -3.1739648804068565),
                    new LatLng(55.92296199781739, -3.174014836549759),
                    new LatLng(55.92297326927132, -3.1741278246045113),
                    new LatLng(55.923042588640726, -3.1741365417838097),
                    new LatLng(55.923045970070206, -3.1738941371440887)
            );
            addWallPolyline(gMap, firstPointsSouthWall);

            // 🧱 示例：第四段墙
            List<LatLng> firstPointsOAKWall = Arrays.asList(
                    new LatLng(55.92303977078261, -3.174293451011181),
                    new LatLng(55.923276094439075, -3.174290433526039),
                    new LatLng(55.92327684586336, -3.1744403019547462)
            );
            addWallPolyline(gMap, firstPointsOAKWall);


            // 🧱 示例：第五段墙
            List<LatLng> firstPointsFeatureStairWall = Arrays.asList(
                    new LatLng(55.92306738578342, -3.1742243841290474),
                    new LatLng(55.92326613806612, -3.174225725233555),
                    new LatLng(55.92327496730266, -3.17416001111269)
            );
            addWallPolyline(gMap, firstPointsFeatureStairWall);

            // 🧱 示例：第六段墙
            List<LatLng> groundPointsNCGroundNorthWall = Arrays.asList(

            );
            addWallPolyline(gMap, groundPointsNCGroundNorthWall);
        }
    }

    // ✅ 工具函数：画线并保存引用
    private static void addWallPolyline(GoogleMap gMap, List<LatLng> wallPoints) {
        for (int i = 0; i < wallPoints.size() - 1; i++) {
            LatLng start = wallPoints.get(i);
            LatLng end = wallPoints.get(i + 1);
            Polyline polyline = gMap.addPolyline(new PolylineOptions()
                    .add(start, end)
                    .color(Color.DKGRAY)
                    .width(8f)
                    .zIndex(3));
            wallPolylines.add(polyline);
        }
    }
}
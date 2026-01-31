package com.openpositioning.PositionMe.data.remote;

import com.google.gson.JsonObject;
import retrofit2.Call;
import retrofit2.http.Body;
import retrofit2.http.POST;

public interface FloorplanApiService {
    // Define the method to request the indoor map API
    // The value of the @POST annotation is a path relative to the base URL.
    @POST("live/floorplan/request")
    Call<JsonObject> requestFloorplans(@Body JsonObject payload);
}
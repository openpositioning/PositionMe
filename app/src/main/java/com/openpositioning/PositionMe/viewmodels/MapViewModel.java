package com.openpositioning.PositionMe.viewmodels;


import android.util.Log;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.ViewModel;
import com.google.gson.JsonObject;
import com.openpositioning.PositionMe.data.remote.FloorplanApiService;
import com.openpositioning.PositionMe.data.remote.RetrofitClient;
import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;

public class MapViewModel extends ViewModel {

    private FloorplanApiService apiService;
    // LiveData is used to store the floor plan response obtained from the API.
    private MutableLiveData<JsonObject> floorplanResponse = new MutableLiveData<>();
    // LiveData is used to store the venue IDs selected by the user on the map.
    private MutableLiveData<String> selectedVenueId = new MutableLiveData<>();

    // The ViewModel constructor initializes the network service here.
    public MapViewModel() {
        apiService = RetrofitClient.getClient().create(FloorplanApiService.class);
    }

    // Public getters allow the UI layer (Fragment) to observe this data.
    public LiveData<JsonObject> getFloorplanResponse() {
        return floorplanResponse;
    }

    public LiveData<String> getSelectedVenueId() {
        return selectedVenueId;
    }

    // The public setter allows the UI layer to update the selected venue ID.
    public void setSelectedVenueId(String venueId) {
        selectedVenueId.setValue(venueId);
    }

    /**
     * Asynchronously request nearby indoor map data based on the given latitude and longitude.
     * @param latitude Current latitude
     * @param longitude Current latitude
     */
    public void fetchNearbyFloorplans(double latitude, double longitude) {
        JsonObject payload = new JsonObject();
        payload.addProperty("latitude", latitude);
        payload.addProperty("longitude", longitude);

        Log.d("MapViewModel", "Fetching floorplans for location: " + latitude + ", " + longitude);

        // Use Retrofit to initiate asynchronous network requests.
        apiService.requestFloorplans(payload).enqueue(new Callback<JsonObject>() {
            @Override
            public void onResponse(Call<JsonObject> call, Response<JsonObject> response) {
                if (response.isSuccessful() && response.body() != null) {
                    floorplanResponse.setValue(response.body());
                    Log.d("MapViewModel", "Floorplans API response received successfully.");
                } else {
                    Log.e("MapViewModel", "API Error, response code: " + response.code());
                    floorplanResponse.setValue(null);
                }
            }

            @Override
            public void onFailure(Call<JsonObject> call, Throwable t) {
                Log.e("MapViewModel", "API request failed.", t);
                floorplanResponse.setValue(null);
            }
        });
    }
}
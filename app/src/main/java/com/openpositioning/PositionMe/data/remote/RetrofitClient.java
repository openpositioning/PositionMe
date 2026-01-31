package com.openpositioning.PositionMe.data.remote;

import retrofit2.Retrofit;
import retrofit2.converter.gson.GsonConverterFactory;

public class RetrofitClient {
    // API base URL
    private static final String BASE_URL = "https://openpositioning.org/api/";
    private static Retrofit retrofit = null;

    // Obtain a Retrofit instance using the Singleton pattern.
    public static Retrofit getClient() {
        if (retrofit == null) {
            retrofit = new Retrofit.Builder()
                    .baseUrl(BASE_URL)
                    .addConverterFactory(GsonConverterFactory.create()) // Parse JSON using Gson
                    .build();
        }
        return retrofit;
    }
}
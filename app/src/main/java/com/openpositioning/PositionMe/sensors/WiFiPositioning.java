package com.openpositioning.PositionMe.sensors;

import android.content.Context;
import android.util.Log;

import com.android.volley.Request;
import com.android.volley.RequestQueue;
import com.android.volley.toolbox.JsonObjectRequest;
import com.android.volley.toolbox.Volley;
import com.google.android.gms.maps.model.LatLng;

import org.json.JSONException;
import org.json.JSONObject;

/**
 * Sends JSON with { "radio":"wifi", "samples":[...], "timestamp":... } to
 * https://openpositioning.org/api/position/fine, parses the response and displays the position.
 */
public class WiFiPositioning {
    // 使用旧端点，不使用 /v1，以避免 405。
    private static final String url = "https://openpositioning.org/api/position/fine";
    private RequestQueue requestQueue;

    private LatLng wifiLocation;
    private int floor = 0;

    public WiFiPositioning(Context context) {
        this.requestQueue = Volley.newRequestQueue(context.getApplicationContext());
    }

    public LatLng getWifiLocation() {
        return wifiLocation;
    }

    public int getFloor() {
        return floor;
    }

    /**
     * POST request without callback.
     */
    public void request(JSONObject jsonWifiFeatures) {
        JsonObjectRequest jsonObjectRequest = new JsonObjectRequest(
                Request.Method.POST,
                url,
                jsonWifiFeatures,
                response -> {
                    try {
                        wifiLocation = new LatLng(response.getDouble("lat"), response.getDouble("lon"));
                        floor = response.getInt("floor");
                        displayPosition();
                    } catch (JSONException e) {
                        Log.e("WiFiPositioning", "Error parsing response: " + e.getMessage() + " " + response);
                    }
                },
                error -> {
                    if (error.networkResponse != null) {
                        int sc = error.networkResponse.statusCode;
                        Log.e("WiFiPositioning", "Response Code: " + sc + ", " + error.getMessage());
                    } else {
                        Log.e("WiFiPositioning", "Error: " + error.getMessage());
                    }
                }
        );
        requestQueue.add(jsonObjectRequest);
    }

    /**
     * POST request with callback.
     */
    public void request(JSONObject jsonWifiFeatures, final VolleyCallback callback) {
        JsonObjectRequest jsonObjectRequest = new JsonObjectRequest(
                Request.Method.POST,
                url,
                jsonWifiFeatures,
                response -> {
                    try {
                        Log.d("WiFiPositioning", "Response JSON: " + response.toString());
                        wifiLocation = new LatLng(response.getDouble("lat"), response.getDouble("lon"));
                        floor = response.getInt("floor");
                        displayPosition();
                        callback.onSuccess(wifiLocation, floor);
                    } catch (JSONException e) {
                        Log.e("WiFiPositioning", "Error parsing response: " + e.getMessage() + " " + response);
                        callback.onError("Error parsing response: " + e.getMessage());
                    }
                },
                error -> {
                    if (error.networkResponse != null) {
                        int sc = error.networkResponse.statusCode;
                        Log.e("WiFiPositioning", "Response Code: " + sc + ", " + error.getMessage());
                        callback.onError("HTTP " + sc + ": " + error.getMessage());
                    } else {
                        Log.e("WiFiPositioning", "Error: " + error.getMessage());
                        callback.onError("Error: " + error.getMessage());
                    }
                }
        );
        requestQueue.add(jsonObjectRequest);
    }

    private void displayPosition() {
        if (wifiLocation != null) {
            Log.d("WiFiPositioning", "WiFi Position: (" + wifiLocation.latitude + ", " + wifiLocation.longitude + "), floor=" + floor);
        } else {
            Log.d("WiFiPositioning", "WiFi Position not available");
        }
    }

    public interface VolleyCallback {
        void onSuccess(LatLng location, int floor);
        void onError(String message);
    }
}

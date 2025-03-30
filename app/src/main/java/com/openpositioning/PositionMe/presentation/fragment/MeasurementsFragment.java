package com.openpositioning.PositionMe.presentation.fragment;

import android.os.Bundle;
import android.os.Handler;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.cardview.widget.CardView;
import androidx.constraintlayout.widget.ConstraintLayout;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.gms.maps.model.LatLng;
import com.openpositioning.PositionMe.R;
import com.openpositioning.PositionMe.sensors.SensorFusion;
import com.openpositioning.PositionMe.sensors.SensorTypes;
import com.openpositioning.PositionMe.sensors.WiFiPositioning;
import com.openpositioning.PositionMe.sensors.Wifi;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.List;
import java.util.Map;

/**
 * A simple {@link Fragment} subclass.
 * 此 Fragment 用于显示各传感器数据以及 WiFi 定位结果。
 * WiFi 定位结果将在 wifiTitle 对应的 TextView 中显示，
 * 而 WiFi 网络的详细数据则在 RecyclerView（id: wifiList）中显示。
 *
 * @author Mate
 */
public class MeasurementsFragment extends Fragment {

    // Static constant for refresh time in milliseconds
    private static final long REFRESH_TIME = 5000;

    // 单例 SensorFusion 类，处理所有传感器数据
    private SensorFusion sensorFusion;
    // UI 更新 Handler
    private Handler refreshDataHandler;

    // UI 元素：上部分为传感器测量列表，下部分为 WiFi 数据展示
    private ConstraintLayout sensorMeasurementList;
    private RecyclerView wifiListView;
    // 用于显示 WiFi 定位结果（同时保留 WiFi 图标及标题）
    private TextView wifiTitleTextView;

    // 字符串前缀数组，用于显示加速度、陀螺仪等数据
    private int[] prefaces;
    private int[] gnssPrefaces;

    // WiFi 定位组件
    private WiFiPositioning wiFiPositioning;

    /**
     * Public default constructor, empty.
     */
    public MeasurementsFragment() {
        // Required empty public constructor
    }

    /**
     * 在 onCreate 中获取 SensorFusion 实例、初始化字符串前缀和 Handler。
     */
    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        sensorFusion = SensorFusion.getInstance();
        prefaces = new int[]{R.string.x, R.string.y, R.string.z};
        gnssPrefaces = new int[]{R.string.lati, R.string.longi};
        refreshDataHandler = new Handler();
    }

    /**
     * 在 onCreateView 中加载布局并启动 UI 刷新任务。
     */
    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container,
                             Bundle savedInstanceState) {
        View rootView = inflater.inflate(R.layout.fragment_measurements, container, false);
        getActivity().setTitle("Sensor Measurements");
        // 启动 UI 刷新任务
        refreshDataHandler.post(refreshTableTask);
        return rootView;
    }

    /**
     * 当 Fragment 暂停时，停止刷新任务。
     */
    @Override
    public void onPause() {
        refreshDataHandler.removeCallbacks(refreshTableTask);
        super.onPause();
    }

    /**
     * 当 Fragment 恢复时，重启刷新任务。
     */
    @Override
    public void onResume() {
        refreshDataHandler.postDelayed(refreshTableTask, REFRESH_TIME);
        super.onResume();
    }

    /**
     * 在 onViewCreated 中获得布局中各个控件的引用，并设置 RecyclerView 的布局管理器。
     */
    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        sensorMeasurementList = view.findViewById(R.id.sensorMeasurementList);
        wifiListView = view.findViewById(R.id.wifiList);
        wifiListView.setLayoutManager(new LinearLayoutManager(getActivity()));
        // 根据布局文件，wifiTitle 的 TextView 用于显示 WiFi 定位结果（同时作为标题）
        wifiTitleTextView = view.findViewById(R.id.wifiTitle);
        // 初始化 WiFi 定位组件
        wiFiPositioning = new WiFiPositioning(getActivity());
    }

    /**
     * 定时任务：每 REFRESH_TIME 毫秒刷新一次界面。
     * 1. 更新传感器数据（例如加速度、陀螺仪、GNSS 数据）。
     * 2. 获取 WiFi 数据，并在 RecyclerView 中显示 WiFi 网络列表。
     * 3. 生成 WiFi 指纹后调用 WiFiPositioning.request(…) 进行定位，
     *    定位结果将在 wifiTitleTextView 中显示。
     */
    private final Runnable refreshTableTask = new Runnable() {
        @Override
        public void run() {
            // 更新传感器测量数据
            Map<SensorTypes, float[]> sensorValueMap = sensorFusion.getSensorValueMap();
            for (SensorTypes st : SensorTypes.values()) {
                CardView cardView = (CardView) sensorMeasurementList.getChildAt(st.ordinal());
                ConstraintLayout currentRow = (ConstraintLayout) cardView.getChildAt(0);
                float[] values = sensorValueMap.get(st);
                for (int i = 0; i < values.length; i++) {
                    String valueString;
                    if (values.length == 1) {
                        valueString = getString(R.string.level, String.format("%.2f", values[0]));
                    } else if (values.length == 2) {
                        if (st == SensorTypes.GNSSLATLONG)
                            valueString = getString(gnssPrefaces[i], String.format("%.2f", values[i]));
                        else
                            valueString = getString(prefaces[i], String.format("%.2f", values[i]));
                    } else {
                        valueString = getString(prefaces[i], String.format("%.2f", values[i]));
                    }
                    ((TextView) currentRow.getChildAt(i + 1)).setText(valueString);
                }
            }
            // 更新 WiFi 网络列表
            List<Wifi> wifiObjects = sensorFusion.getWifiList();
            if (wifiObjects != null && !wifiObjects.isEmpty()) {
                wifiListView.setAdapter(new WifiListAdapter(wifiObjects));
                // 构造 WiFi 指纹 JSON 并请求 WiFi 定位
                try {
                    JSONObject fingerprint = createWifiFingerprint(wifiObjects);
                    wiFiPositioning.request(fingerprint, new WiFiPositioning.VolleyCallback() {
                        @Override
                        public void onSuccess(LatLng location, int floor) {
                            getActivity().runOnUiThread(() ->
                                    wifiTitleTextView.setText("WiFi: " + location.latitude + ", " + location.longitude + " Floor: " + floor)
                            );
                        }
                        @Override
                        public void onError(String message) {
                            getActivity().runOnUiThread(() ->
                                    wifiTitleTextView.setText("WiFi: Error - " + message)
                            );
                        }
                    });
                } catch (JSONException e) {
                    Log.e("MeasurementsFragment", "Error creating WiFi fingerprint: " + e.getMessage());
                }
            } else {
                // 如果当前没有 WiFi 数据，则显示无覆盖信息
                getActivity().runOnUiThread(() ->
                        wifiTitleTextView.setText("WiFi: No coverage")
                );
            }
            // 重新调度任务
            refreshDataHandler.postDelayed(this, REFRESH_TIME);
        }
    };

    /**
     * 生成 WiFi 指纹 JSON，指纹格式：{"wf": { "bssid1": level1, "bssid2": level2, ... }}
     *
     * @param wifiList 当前扫描到的 WiFi 对象列表
     * @return 指纹 JSON 对象
     * @throws JSONException
     */
    private JSONObject createWifiFingerprint(List<Wifi> wifiList) throws JSONException {
        JSONObject fingerprint = new JSONObject();
        JSONObject wf = new JSONObject();
        for (Wifi wifi : wifiList) {
            wf.put(String.valueOf(wifi.getBssid()), wifi.getLevel());
        }
        fingerprint.put("wf", wf);
        return fingerprint;
    }

    /**
     * 内部定义的 RecyclerView 适配器，用于显示 WiFi 数据。
     */
    private class WifiListAdapter extends RecyclerView.Adapter<WifiListAdapter.WifiViewHolder> {

        private List<Wifi> wifiList;

        public WifiListAdapter(List<Wifi> wifiList) {
            this.wifiList = wifiList;
        }

        @NonNull
        @Override
        public WifiViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View view = LayoutInflater.from(parent.getContext()).inflate(android.R.layout.simple_list_item_1, parent, false);
            return new WifiViewHolder(view);
        }

        @Override
        public void onBindViewHolder(@NonNull WifiViewHolder holder, int position) {
            Wifi wifi = wifiList.get(position);
            holder.wifiInfoText.setText(wifi.toString());
        }

        @Override
        public int getItemCount() {
            return wifiList.size();
        }

        class WifiViewHolder extends RecyclerView.ViewHolder {
            TextView wifiInfoText;

            public WifiViewHolder(@NonNull View itemView) {
                super(itemView);
                wifiInfoText = (TextView) itemView.findViewById(android.R.id.text1);
            }
        }
    }
}

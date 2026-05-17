package com.example.offpeak;

import androidx.appcompat.app.AppCompatActivity;

import android.os.Bundle;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONObject;
import android.widget.TextView;
import android.view.View;
import androidx.core.content.ContextCompat;
import android.content.Intent;
import android.content.Context;
import android.util.Log;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.os.Build;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import android.Manifest;
import android.content.pm.PackageManager;
import android.widget.Toast;
import com.google.firebase.messaging.FirebaseMessaging;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import androidx.annotation.NonNull;

public class MainActivity extends AppCompatActivity {

    private WebView mapWebView;
    private View statusDot;
    private TextView statusText;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler handler = new Handler(Looper.getMainLooper());
    
    private double currentLat = 3.4516; // Cali default
    private double currentLng = -76.5320;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        mapWebView = findViewById(R.id.mapWebView);
        statusDot = findViewById(R.id.statusDot);
        statusText = findViewById(R.id.statusText);
        
        WebSettings webSettings = mapWebView.getSettings();
        webSettings.setJavaScriptEnabled(true);
        webSettings.setDomStorageEnabled(true);
        webSettings.setAllowUniversalAccessFromFileURLs(true);
        webSettings.setAllowFileAccessFromFileURLs(true);
        webSettings.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        
        mapWebView.addJavascriptInterface(new WebAppInterface(), "Android");

        findViewById(R.id.btnCloseRoute).setOnClickListener(v -> {
            findViewById(R.id.routeDetailsCard).setVisibility(View.GONE);
            mapWebView.evaluateJavascript("javascript:if(currentRouteLayer){map.removeLayer(currentRouteLayer);}", null);
        });
        
        mapWebView.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String url) {
                fetchHeatmapData();
                requestLocation();
            }
        });
        
        mapWebView.loadUrl("file:///android_asset/map.html");

        // Configurar navegación al Planificador (SCRUM-37) - Panel Central
        findViewById(R.id.pulsePanel).setOnClickListener(v -> {
            startActivity(new Intent(MainActivity.this, PlannerActivity.class));
        });

        // Configurar navegación al Planificador (SCRUM-37) - Botón Menú
        findViewById(R.id.btnPlanner).setOnClickListener(v -> {
            startActivity(new Intent(MainActivity.this, PlannerActivity.class));
        });

        // Configurar navegación a Alertas (Bandeja de entrada)
        findViewById(R.id.btnAlerts).setOnClickListener(v -> {
            startActivity(new Intent(MainActivity.this, AlertsActivity.class));
        });

        // Configurar Notificaciones Push (SCRUM-42)
        createNotificationChannel();
        askNotificationPermission();
        
        // Obtener Token de FCM para depuración
        FirebaseMessaging.getInstance().getToken()
            .addOnCompleteListener(task -> {
                if (!task.isSuccessful()) {
                    Log.w("MainActivity", "Error al obtener token FCM", task.getException());
                    return;
                }
                String token = task.getResult();
                Log.d("MainActivity", "FCM Token: " + token);
            });
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            String name = "Incidentes Críticos";
            String description = "Notificaciones sobre cierres de vías y emergencias";
            int importance = NotificationManager.IMPORTANCE_HIGH;
            NotificationChannel channel = new NotificationChannel(MyFirebaseMessagingService.CHANNEL_ID, name, importance);
            channel.setDescription(description);
            NotificationManager notificationManager = getSystemService(NotificationManager.class);
            notificationManager.createNotificationChannel(channel);
        }
    }

    private final ActivityResultLauncher<String> requestPermissionLauncher =
            registerForActivityResult(new ActivityResultContracts.RequestPermission(), isGranted -> {
                if (isGranted) {
                    Toast.makeText(this, "Permiso concedido", Toast.LENGTH_SHORT).show();
                    getLocation();
                } else {
                    Toast.makeText(this, "Permiso denegado, se usará ubicación por defecto", Toast.LENGTH_LONG).show();
                }
            });

    private void requestLocation() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            requestPermissionLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION);
        } else {
            getLocation();
        }
    }

    private void getLocation() {
        try {
            LocationManager locationManager = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
            if (locationManager != null) {
                Location location = locationManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER);
                if (location == null) {
                    location = locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER);
                }
                
                if (location != null) {
                    currentLat = location.getLatitude();
                    currentLng = location.getLongitude();
                    handler.post(() -> mapWebView.evaluateJavascript("javascript:setUserLocation(" + currentLat + ", " + currentLng + ");", null));
                } else {
                    locationManager.requestSingleUpdate(LocationManager.NETWORK_PROVIDER, new LocationListener() {
                        @Override
                        public void onLocationChanged(@NonNull Location loc) {
                            currentLat = loc.getLatitude();
                            currentLng = loc.getLongitude();
                            handler.post(() -> mapWebView.evaluateJavascript("javascript:setUserLocation(" + currentLat + ", " + currentLng + ");", null));
                        }
                    }, Looper.getMainLooper());
                }
            }
        } catch (SecurityException e) {
            Log.e("MainActivity", "Location permission denied", e);
        }
    }

    private void askNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
                    PackageManager.PERMISSION_GRANTED) {
                // To avoid replacing the location launcher, we can use a basic permission request or just ignore it for this fix.
                requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 101);
            }
        }
    }

    public class WebAppInterface {
        @android.webkit.JavascriptInterface
        public void onMarkerClick(double lat, double lng, String name) {
            handler.post(() -> Toast.makeText(MainActivity.this, "Calculando ruta para: " + name, Toast.LENGTH_SHORT).show());
            fetchSmartRoute(lat, lng, name);
        }
    }

    private void fetchSmartRoute(double destLat, double destLng, String name) {
        executor.execute(() -> {
            try {
                String baseUrl = getString(R.string.api_heatmap_url);
                String routeUrl = baseUrl.replace("heatmap", "rerouting/recommend");
                
                URL url = new URL(routeUrl);
                HttpURLConnection connection = (HttpURLConnection) url.openConnection();
                connection.setRequestMethod("POST");
                connection.setRequestProperty("Content-Type", "application/json; utf-8");
                connection.setRequestProperty("Accept", "application/json");
                connection.setDoOutput(true);
                connection.setConnectTimeout(60000);
                connection.setReadTimeout(60000);
                
                // Usar ubicación real, aumentamos el radio a 50000 (50km) por si el GPS está lejos de Cali
                String jsonInputString = "{\"latitud\": " + currentLat + ", \"longitud\": " + currentLng + ", \"radio_metros\": 50000, \"limite\": 1}";
                
                try(java.io.OutputStream os = connection.getOutputStream()) {
                    byte[] input = jsonInputString.getBytes("utf-8");
                    os.write(input, 0, input.length);           
                }

                BufferedReader reader = new BufferedReader(new InputStreamReader(connection.getInputStream(), "utf-8"));
                StringBuilder response = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    response.append(line.trim());
                }
                reader.close();
                
                String jsonResponse = response.toString();
                JSONArray jsonArray = new JSONArray(jsonResponse);
                if (jsonArray.length() > 0) {
                    JSONObject alt = jsonArray.getJSONObject(0);
                    int time = alt.optInt("tiempo_viaje_minutos", 15);
                    String weather = alt.optString("clima_actual", "Despejado");
                    String incidents = alt.optString("incidencias_viales", "Ninguna");
                    String reason = alt.optString("razon_desvio", "Ruta recomendada.");
                    
                    JSONObject geojson = alt.optJSONObject("geometria_ruta");
                    String geojsonStr = geojson != null ? geojson.toString().replace("'", "\\'").replace("\n", "") : "{}";
                    
                    handler.post(() -> {
                        TextView routeTitle = findViewById(R.id.routeTitle);
                        TextView routeTime = findViewById(R.id.routeTime);
                        TextView routeWeather = findViewById(R.id.routeWeather);
                        TextView routeIncidents = findViewById(R.id.routeIncidents);
                        TextView routeReason = findViewById(R.id.routeReason);
                        View routeCard = findViewById(R.id.routeDetailsCard);
                        
                        routeTitle.setText("Ruta hacia " + name);
                        routeTime.setText(time + " min");
                        routeWeather.setText(weather);
                        routeIncidents.setText(incidents);
                        routeReason.setText(reason);
                        
                        routeCard.setVisibility(View.VISIBLE);
                        
                        if (!geojsonStr.equals("{}")) {
                            String jsCommand = "javascript:drawRoute('" + geojsonStr + "');";
                            mapWebView.evaluateJavascript(jsCommand, null);
                        } else {
                            mapWebView.evaluateJavascript("javascript:showOfflineMode('Backend no actualizado en Render');", null);
                        }
                    });
                } else {
                    handler.post(() -> {
                        Toast.makeText(MainActivity.this, "Estás demasiado lejos de los lugares (Fuera de rango)", Toast.LENGTH_LONG).show();
                    });
                }
            } catch (Exception e) {
                Log.e("MainActivity", "Error fetching route: " + e.getMessage(), e);
                handler.post(() -> {
                    Toast.makeText(MainActivity.this, "Error de red: " + e.getMessage(), Toast.LENGTH_LONG).show();
                    mapWebView.evaluateJavascript("javascript:showOfflineMode('Error conectando al backend');", null);
                });
            }
        });
    }

    private void fetchHeatmapData() {
        executor.execute(() -> {
            try {
                // Conexión al backend en Render (URL centralizada en strings.xml)
                String urlString = getString(R.string.api_heatmap_url);
                URL url = new URL(urlString);
                HttpURLConnection connection = (HttpURLConnection) url.openConnection();
                connection.setRequestMethod("GET");
                connection.setConnectTimeout(60000); 
                connection.setReadTimeout(60000);
                
                BufferedReader reader = new BufferedReader(new InputStreamReader(connection.getInputStream()));
                StringBuilder response = new StringBuilder();
                String line;
                
                while ((line = reader.readLine()) != null) {
                    response.append(line);
                }
                reader.close();
                
                final String jsonResponse = response.toString();
                
                // Procesar el JSON para calcular el promedio de la zona
                JSONArray jsonArray = new JSONArray(jsonResponse);
                double totalIntensity = 0;
                int validPoints = 0;
                
                for (int i = 0; i < jsonArray.length(); i++) {
                    JSONObject point = jsonArray.getJSONObject(i);
                    double intensity = point.getDouble("intensity");
                    if (intensity < 9.9) { // Ignorar el código 999
                        totalIntensity += intensity;
                        validPoints++;
                    }
                }
                
                double average = validPoints > 0 ? (totalIntensity / validPoints) : 0.0;
                
                handler.post(() -> {
                    // Actualizar Semáforo Nativo
                    if (average >= 0.7) {
                        statusText.setText("CRITICAL FLOW");
                        statusText.setTextColor(ContextCompat.getColor(this, R.color.status_critical));
                        statusDot.setBackgroundResource(R.drawable.dot_critical);
                    } else if (average >= 0.4) {
                        statusText.setText("MODERATE FLOW");
                        statusText.setTextColor(ContextCompat.getColor(this, R.color.status_moderate));
                        statusDot.setBackgroundResource(R.drawable.dot_moderate);
                    } else {
                        statusText.setText("OPTIMAL FLOW");
                        statusText.setTextColor(ContextCompat.getColor(this, R.color.status_optimal));
                        statusDot.setBackgroundResource(R.drawable.dot_optimal);
                    }
                    
                    // Actualizar el mapa
                    String jsCommand = "javascript:loadDynamicHeatmap('" + jsonResponse.replace("'", "\\'") + "');";
                    mapWebView.evaluateJavascript(jsCommand, null);
                });
                
            } catch (Exception e) {
                e.printStackTrace();
                final String errorMsg = e.getMessage() != null ? e.getMessage().replace("'", "\\'") : "Error desconocido";
                handler.post(() -> mapWebView.evaluateJavascript("javascript:showOfflineMode('" + errorMsg + "');", null));
            }
        });
    }
}
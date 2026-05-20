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
import android.media.RingtoneManager;
import android.net.Uri;
import androidx.core.app.NotificationCompat;

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
        
        // Suscribirse al tema de incidentes críticos
        FirebaseMessaging.getInstance().subscribeToTopic("critical_incidents")
            .addOnCompleteListener(task -> {
                if (task.isSuccessful()) {
                    Log.d("MainActivity", "Suscrito al tema: critical_incidents");
                } else {
                    Log.e("MainActivity", "Fallo al suscribirse al tema", task.getException());
                }
            });
        
        // También suscribirse a "alerts" por si acaso
        FirebaseMessaging.getInstance().subscribeToTopic("alerts");

        // Manejar datos de notificación si la app se abrió desde una notificación en segundo plano
        handleNotificationIntent(getIntent());
        
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

        startAlertsPolling();
    }

    private void handleNotificationIntent(Intent intent) {
        if (intent != null && intent.getExtras() != null) {
            String title = intent.getStringExtra("title");
            String body = intent.getStringExtra("body");
            if (title != null && body != null) {
                // Guardar la notificación si viene de los extras (caso background)
                saveNotificationLocally(title, body);
                Log.d("MainActivity", "Notificación recibida via Intent: " + title);
            }
        }
    }

    private void saveNotificationLocally(String title, String body) {
        try {
            android.content.SharedPreferences prefs = getSharedPreferences("offpeak_alerts", Context.MODE_PRIVATE);
            String existingAlerts = prefs.getString("alerts_list", "[]");
            org.json.JSONArray alertsArray = new org.json.JSONArray(existingAlerts);
            
            // Evitar duplicados simples si el timestamp es muy cercano (opcional)
            org.json.JSONObject newAlert = new org.json.JSONObject();
            newAlert.put("title", title);
            newAlert.put("body", body);
            newAlert.put("timestamp", System.currentTimeMillis());
            
            alertsArray.put(newAlert);
            prefs.edit().putString("alerts_list", alertsArray.toString()).apply();
        } catch (Exception e) {
            Log.e("MainActivity", "Error saving notification from intent", e);
        }
    }

    @Override
    protected void onNewIntent(@androidx.annotation.NonNull Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleNotificationIntent(intent);
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

    private String getCategoryByName(String name) {
        if (name == null) return null;
        String lowercaseName = name.toLowerCase();
        if (lowercaseName.contains("jardín plaza") || lowercaseName.contains("chipichape") || lowercaseName.contains("centro comercial")) {
            return "centro_comercial";
        } else if (lowercaseName.contains("perro") || lowercaseName.contains("amor") || lowercaseName.contains("parque")) {
            return "parque";
        } else if (lowercaseName.contains("bulevar") || lowercaseName.contains("río") || lowercaseName.contains("rio") || lowercaseName.contains("público") || lowercaseName.contains("publico")) {
            return "espacio_publico";
        }
        return null;
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
                
                String escapedName = name != null ? name.replace("\"", "\\\"") : "";
                String category = getCategoryByName(name);
                String jsonInputString;
                if (category != null) {
                    jsonInputString = "{\"latitud\": " + currentLat + ", \"longitud\": " + currentLng + ", \"categoria_objetivo\": \"" + category + "\", \"radio_metros\": 50000, \"limite\": 1, \"nombre_destino\": \"" + escapedName + "\"}";
                } else {
                    jsonInputString = "{\"latitud\": " + currentLat + ", \"longitud\": " + currentLng + ", \"radio_metros\": 50000, \"limite\": 1, \"nombre_destino\": \"" + escapedName + "\"}";
                }
                
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
                    String recommendedName = alt.optString("nombre", name);
                    
                    JSONObject geojson = alt.optJSONObject("geometria_ruta");
                    String geojsonStr = geojson != null ? geojson.toString().replace("'", "\\'").replace("\n", "") : "{}";
                    
                    handler.post(() -> {
                        TextView routeTitle = findViewById(R.id.routeTitle);
                        TextView routeTime = findViewById(R.id.routeTime);
                        TextView routeWeather = findViewById(R.id.routeWeather);
                        TextView routeIncidents = findViewById(R.id.routeIncidents);
                        TextView routeReason = findViewById(R.id.routeReason);
                        View routeCard = findViewById(R.id.routeDetailsCard);
                        
                        routeTitle.setText("Ruta hacia " + recommendedName);
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

    private void startAlertsPolling() {
        handler.post(new Runnable() {
            @Override
            public void run() {
                fetchAlertsFromBackend();
                handler.postDelayed(this, 10000); // Polling every 10 seconds
            }
        });
    }

    private void fetchAlertsFromBackend() {
        executor.execute(() -> {
            try {
                String baseUrl = getString(R.string.api_heatmap_url);
                String alertsUrl = baseUrl.replace("heatmap", "alerts/");
                
                URL url = new URL(alertsUrl);
                HttpURLConnection connection = (HttpURLConnection) url.openConnection();
                connection.setRequestMethod("GET");
                connection.setConnectTimeout(15000);
                connection.setReadTimeout(15000);
                
                int responseCode = connection.getResponseCode();
                if (responseCode == HttpURLConnection.HTTP_OK) {
                    BufferedReader reader = new BufferedReader(new InputStreamReader(connection.getInputStream(), "utf-8"));
                    StringBuilder response = new StringBuilder();
                    String line;
                    while ((line = reader.readLine()) != null) {
                        response.append(line.trim());
                    }
                    reader.close();
                    
                    String jsonResponse = response.toString();
                    JSONArray jsonArray = new JSONArray(jsonResponse);
                    
                    android.content.SharedPreferences prefs = getSharedPreferences("offpeak_alerts", Context.MODE_PRIVATE);
                    String notifiedIdsStr = prefs.getString("notified_alert_ids", "[]");
                    JSONArray notifiedIds = new JSONArray(notifiedIdsStr);
                    
                    java.util.Set<String> notifiedSet = new java.util.HashSet<>();
                    for (int i = 0; i < notifiedIds.length(); i++) {
                        notifiedSet.add(notifiedIds.getString(i));
                    }
                    
                    boolean newAlertAdded = false;
                    String localAlertsStr = prefs.getString("alerts_list", "[]");
                    JSONArray localAlerts = new JSONArray(localAlertsStr);
                    // Use a set of local IDs to avoid duplicating in the list
                    java.util.Set<String> localIds = new java.util.HashSet<>();
                    for (int i = 0; i < localAlerts.length(); i++) {
                        JSONObject obj = localAlerts.getJSONObject(i);
                        if (obj.has("id")) {
                            localIds.add(obj.getString("id"));
                        }
                    }
                    
                    // Process alerts from oldest to newest
                    for (int i = jsonArray.length() - 1; i >= 0; i--) {
                        JSONObject alertObj = jsonArray.getJSONObject(i);
                        String alertId = alertObj.getString("id");
                        String msg = alertObj.getString("mensaje");
                        String venueName = alertObj.optString("venue_nombre", "OFFPEAK");
                        String tipo = alertObj.optString("tipo", "alerta");
                        
                        // If not notified yet
                        if (!notifiedSet.contains(alertId)) {
                            String title = "Alerta Crítica: " + venueName;
                            if (tipo.contains("normalizado")) {
                                title = "Normalización: " + venueName;
                            }
                            showLocalNotification(title, msg);
                            
                            notifiedIds.put(alertId);
                            notifiedSet.add(alertId);
                            newAlertAdded = true;
                        }
                        
                        // If not in local alerts list, add it
                        if (!localIds.contains(alertId)) {
                            JSONObject newAlert = new JSONObject();
                            newAlert.put("id", alertId);
                            newAlert.put("title", tipo.contains("normalizado") ? "Normalización: " + venueName : "Alerta Crítica: " + venueName);
                            newAlert.put("body", msg);
                            
                            String dateStr = alertObj.optString("generada_en");
                            long ts = System.currentTimeMillis();
                            try {
                                if (dateStr != null && !dateStr.isEmpty()) {
                                    // Remove 'Z' or replace timezone offset
                                    dateStr = dateStr.replace("Z", "+0000");
                                    // Clean subseconds if there are more than 3
                                    int dotIndex = dateStr.indexOf('.');
                                    int plusIndex = dateStr.indexOf('+');
                                    if (dotIndex != -1 && plusIndex != -1) {
                                        String ms = dateStr.substring(dotIndex + 1, plusIndex);
                                        if (ms.length() > 3) {
                                            dateStr = dateStr.substring(0, dotIndex + 4) + dateStr.substring(plusIndex);
                                        }
                                    }
                                    java.text.SimpleDateFormat sdf = new java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSZ", java.util.Locale.US);
                                    java.util.Date d = sdf.parse(dateStr);
                                    if (d != null) ts = d.getTime();
                                }
                            } catch (Exception e) {
                                Log.e("MainActivity", "Error parsing date: " + dateStr, e);
                            }
                            newAlert.put("timestamp", ts);
                            localAlerts.put(newAlert);
                            localIds.add(alertId);
                            newAlertAdded = true;
                        }
                    }
                    
                    if (newAlertAdded) {
                        prefs.edit()
                            .putString("notified_alert_ids", notifiedIds.toString())
                            .putString("alerts_list", localAlerts.toString())
                            .apply();
                    }
                }
            } catch (Exception e) {
                Log.e("MainActivity", "Error fetching alerts", e);
            }
        });
    }

    private void showLocalNotification(String title, String body) {
        Uri defaultSoundUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION);
        NotificationCompat.Builder notificationBuilder =
                new NotificationCompat.Builder(this, MyFirebaseMessagingService.CHANNEL_ID)
                        .setSmallIcon(R.drawable.ic_launcher_foreground)
                        .setContentTitle(title)
                        .setContentText(body)
                        .setStyle(new NotificationCompat.BigTextStyle().bigText(body))
                        .setAutoCancel(true)
                        .setSound(defaultSoundUri)
                        .setPriority(NotificationCompat.PRIORITY_HIGH);

        NotificationManager notificationManager =
                (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    MyFirebaseMessagingService.CHANNEL_ID,
                    "Incidentes Críticos",
                    NotificationManager.IMPORTANCE_HIGH);
            channel.setDescription("Notificaciones sobre cierres de vías y emergencias");
            notificationManager.createNotificationChannel(channel);
        }

        int notificationId = (title + body).hashCode();
        notificationManager.notify(notificationId, notificationBuilder.build());
    }
}
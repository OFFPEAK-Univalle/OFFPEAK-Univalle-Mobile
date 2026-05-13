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


public class MainActivity extends AppCompatActivity {

    private WebView mapWebView;
    private View statusDot;
    private TextView statusText;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler handler = new Handler(Looper.getMainLooper());

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
        
        mapWebView.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String url) {
                fetchHeatmapData();
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
                    Toast.makeText(this, "Notificaciones activadas", Toast.LENGTH_SHORT).show();
                } else {
                    Toast.makeText(this, "No recibirás alertas críticas", Toast.LENGTH_LONG).show();
                }
            });

    private void askNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
                    PackageManager.PERMISSION_GRANTED) {
                requestPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS);
            }
        }
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
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
    }

    private void fetchHeatmapData() {
        executor.execute(() -> {
            try {
                // Para emulador usa 10.0.2.2, para celular físico usa la IP de tu PC (ej. 192.168.x.x)
                URL url = new URL("http://192.168.110.40:8000/api/v1/heatmap");
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
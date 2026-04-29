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

public class MainActivity extends AppCompatActivity {

    private WebView mapWebView;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler handler = new Handler(Looper.getMainLooper());

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        mapWebView = findViewById(R.id.mapWebView);
        
        WebSettings webSettings = mapWebView.getSettings();
        webSettings.setJavaScriptEnabled(true);
        webSettings.setDomStorageEnabled(true);
        webSettings.setAllowUniversalAccessFromFileURLs(true);
        webSettings.setAllowFileAccessFromFileURLs(true);
        webSettings.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        
        mapWebView.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String url) {
                // Cuando el mapa termina de cargar la base, pedir los datos al servidor
                fetchHeatmapData();
            }
        });
        
        mapWebView.loadUrl("file:///android_asset/map.html");
    }

    private void fetchHeatmapData() {
        executor.execute(() -> {
            try {
                // URL mágica (127.0.0.1). El túnel USB redirigirá esto directamente a la PC.
                URL url = new URL("http://127.0.0.1:8000/api/v1/heatmap");
                HttpURLConnection connection = (HttpURLConnection) url.openConnection();
                connection.setRequestMethod("GET");
                connection.setConnectTimeout(60000); // 60 segundos max para darle tiempo a BestTime
                connection.setReadTimeout(60000);
                
                BufferedReader reader = new BufferedReader(new InputStreamReader(connection.getInputStream()));
                StringBuilder response = new StringBuilder();
                String line;
                
                while ((line = reader.readLine()) != null) {
                    response.append(line);
                }
                reader.close();
                
                final String jsonResponse = response.toString();
                
                // Enviar los datos al WebView en el hilo principal
                handler.post(() -> {
                    // Escapar comillas para JS
                    String jsCommand = "javascript:loadDynamicHeatmap('" + jsonResponse.replace("'", "\\'") + "');";
                    mapWebView.evaluateJavascript(jsCommand, null);
                });
                
            } catch (Exception e) {
                e.printStackTrace();
                final String errorMsg = e.getMessage() != null ? e.getMessage().replace("'", "\\'") : "Error desconocido";
                // Si falla, mostrar el OFFLINE MODE con el error en la etiqueta
                handler.post(() -> mapWebView.evaluateJavascript("javascript:showOfflineMode('" + errorMsg + "');", null));
            }
        });
    }
}
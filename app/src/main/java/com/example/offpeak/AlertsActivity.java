package com.example.offpeak;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import androidx.appcompat.app.AppCompatActivity;
import org.json.JSONArray;
import org.json.JSONObject;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class AlertsActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_alerts);

        ImageView btnBack = findViewById(R.id.btnBack);
        btnBack.setOnClickListener(v -> finish());

        LinearLayout container = findViewById(R.id.alertsContainer);
        TextView emptyStateText = findViewById(R.id.emptyStateText);

        SharedPreferences prefs = getSharedPreferences("offpeak_alerts", Context.MODE_PRIVATE);
        String alertsJson = prefs.getString("alerts_list", "[]");

        try {
            JSONArray alertsArray = new JSONArray(alertsJson);
            if (alertsArray.length() == 0) {
                emptyStateText.setVisibility(View.VISIBLE);
            } else {
                emptyStateText.setVisibility(View.GONE);
                
                // Mostrar desde el más reciente
                for (int i = alertsArray.length() - 1; i >= 0; i--) {
                    JSONObject alert = alertsArray.getJSONObject(i);
                    String title = alert.optString("title", "Alerta");
                    String body = alert.optString("body", "");
                    long timestamp = alert.optLong("timestamp", System.currentTimeMillis());

                    View alertView = createAlertView(title, body, timestamp);
                    container.addView(alertView);
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
            emptyStateText.setVisibility(View.VISIBLE);
        }
    }

    private View createAlertView(String title, String body, long timestamp) {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        
        LinearLayout.LayoutParams layoutParams = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 
            LinearLayout.LayoutParams.WRAP_CONTENT
        );
        layoutParams.setMargins(0, 0, 0, 32);
        layout.setLayoutParams(layoutParams);
        
        // Background
        layout.setBackgroundResource(R.drawable.pill_bg);
        layout.setPadding(40, 40, 40, 40);

        // Date
        SimpleDateFormat sdf = new SimpleDateFormat("MMM dd, hh:mm a", Locale.getDefault());
        String dateString = sdf.format(new Date(timestamp));

        TextView dateText = new TextView(this);
        dateText.setText(dateString);
        dateText.setTextColor(getResources().getColor(R.color.accent_cyan, null));
        dateText.setTextSize(12);
        layout.addView(dateText);

        // Title
        TextView titleText = new TextView(this);
        titleText.setText(title);
        titleText.setTextColor(getResources().getColor(R.color.text_primary, null));
        titleText.setTextSize(16);
        titleText.setTypeface(null, android.graphics.Typeface.BOLD);
        titleText.setPadding(0, 8, 0, 8);
        layout.addView(titleText);

        // Body
        TextView bodyText = new TextView(this);
        bodyText.setText(body);
        bodyText.setTextColor(getResources().getColor(R.color.text_secondary, null));
        bodyText.setTextSize(14);
        layout.addView(bodyText);

        return layout;
    }
}

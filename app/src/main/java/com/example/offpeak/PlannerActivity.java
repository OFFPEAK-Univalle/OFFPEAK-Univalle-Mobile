package com.example.offpeak;

import android.graphics.Color;
import android.os.Bundle;
import androidx.appcompat.app.AppCompatActivity;
import com.github.mikephil.charting.charts.BarChart;
import com.github.mikephil.charting.components.XAxis;
import com.github.mikephil.charting.data.BarData;
import com.github.mikephil.charting.data.BarDataSet;
import com.github.mikephil.charting.data.BarEntry;
import com.github.mikephil.charting.formatter.IndexAxisValueFormatter;
import java.util.ArrayList;
import java.util.List;

public class PlannerActivity extends AppCompatActivity {

    private BarChart forecastChart;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_planner);

        forecastChart = findViewById(R.id.forecastChart);
        setupChart();
        loadHistoricalData();

        findViewById(R.id.backButton).setOnClickListener(v -> finish());
    }

    private void setupChart() {
        forecastChart.getDescription().setEnabled(false);
        forecastChart.getLegend().setEnabled(false);
        forecastChart.setDrawGridBackground(false);
        forecastChart.setDrawBarShadow(false);
        
        XAxis xAxis = forecastChart.getXAxis();
        xAxis.setPosition(XAxis.XAxisPosition.BOTTOM);
        xAxis.setDrawGridLines(false);
        xAxis.setTextColor(Color.WHITE);
        
        forecastChart.getAxisLeft().setTextColor(Color.WHITE);
        forecastChart.getAxisRight().setEnabled(false);
    }

    private void loadHistoricalData() {
        // Datos simulados de pronóstico (24 horas)
        List<BarEntry> entries = new ArrayList<>();
        String[] hours = new String[24];
        
        for (int i = 0; i < 24; i++) {
            // Generamos una curva de campana simulando horas pico
            float val = (float) (Math.sin(i * 0.5) + 1.2) * 20;
            if (i > 16 && i < 20) val += 30; // Pico tarde
            entries.add(new BarEntry(i, val));
            hours[i] = i + ":00";
        }

        BarDataSet dataSet = new BarDataSet(entries, "Expected Traffic");
        dataSet.setColor(Color.parseColor("#00D1FF")); // Color Cyan de la App
        dataSet.setValueTextColor(Color.TRANSPARENT);

        BarData data = new BarData(dataSet);
        data.setBarWidth(0.8f);

        forecastChart.setData(data);
        forecastChart.getXAxis().setValueFormatter(new IndexAxisValueFormatter(hours));
        forecastChart.invalidate(); // Refrescar
    }
}
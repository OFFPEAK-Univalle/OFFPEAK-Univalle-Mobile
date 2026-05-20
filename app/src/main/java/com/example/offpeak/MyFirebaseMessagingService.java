package com.example.offpeak;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.Build;
import android.content.SharedPreferences;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.core.app.NotificationCompat;

import org.json.JSONArray;
import org.json.JSONObject;

import com.google.firebase.messaging.FirebaseMessagingService;
import com.google.firebase.messaging.RemoteMessage;

public class MyFirebaseMessagingService extends FirebaseMessagingService {

    private static final String TAG = "MyFirebaseMsgService";
    public static final String CHANNEL_ID = "incidentes_criticos_channel";

    /**
     * Llamado cuando se recibe un mensaje.
     */
    @Override
    public void onMessageReceived(@NonNull RemoteMessage remoteMessage) {
        // Log de depuración
        Log.d(TAG, "De: " + remoteMessage.getFrom());

        // Verificar si el mensaje contiene una carga de notificación
        if (remoteMessage.getNotification() != null) {
            String title = remoteMessage.getNotification().getTitle();
            String body = remoteMessage.getNotification().getBody();
            Log.d(TAG, "Cuerpo de notificación: " + body);
            saveNotificationToPreferences(title, body);
            sendNotification(title, body);
        }

        // Si el mensaje tiene datos, también podemos procesarlos aquí
        if (remoteMessage.getData().size() > 0) {
            Log.d(TAG, "Carga de datos: " + remoteMessage.getData());
            // Si el mensaje no trae objeto de notificación pero sí datos,
            // podemos construir una notificación personalizada.
            if (remoteMessage.getNotification() == null) {
                String title = remoteMessage.getData().get("title");
                String body = remoteMessage.getData().get("body");
                String finalTitle = title != null ? title : "Alerta OFFPEAK";
                String finalBody = body != null ? body : "Nuevo incidente detectado";
                saveNotificationToPreferences(finalTitle, finalBody);
                sendNotification(finalTitle, finalBody);
            }
        }
    }

    /**
     * Llamado cuando se genera un nuevo token.
     */
    @Override
    public void onNewToken(@NonNull String token) {
        Log.d(TAG, "Nuevo token de dispositivo: " + token);
        // Aquí se enviaría el token al servidor backend si fuera necesario
    }

    /**
     * Crea y muestra una notificación simple.
     */
    private void sendNotification(String title, String messageBody) {
        Intent intent = new Intent(this, MainActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
        // Agregar extras para que MainActivity pueda capturarlos si está en background
        intent.putExtra("title", title);
        intent.putExtra("body", messageBody);

        PendingIntent pendingIntent = PendingIntent.getActivity(this, 0, intent,
                PendingIntent.FLAG_ONE_SHOT | PendingIntent.FLAG_IMMUTABLE);

        Uri defaultSoundUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION);
        NotificationCompat.Builder notificationBuilder =
                new NotificationCompat.Builder(this, CHANNEL_ID)
                        .setSmallIcon(R.drawable.ic_launcher_foreground) // Usar icono disponible
                        .setContentTitle(title)
                        .setContentText(messageBody)
                        .setAutoCancel(true)
                        .setSound(defaultSoundUri)
                        .setContentIntent(pendingIntent)
                        .setPriority(NotificationCompat.PRIORITY_HIGH);

        NotificationManager notificationManager =
                (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);

        // Crear canal de notificación para Android Oreo y versiones superiores
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(CHANNEL_ID,
                    "Incidentes Críticos",
                    NotificationManager.IMPORTANCE_HIGH);
            channel.setDescription("Notificaciones sobre cierres de vías y emergencias");
            notificationManager.createNotificationChannel(channel);
        }

        notificationManager.notify(0, notificationBuilder.build());
    }

    private void saveNotificationToPreferences(String title, String body) {
        try {
            SharedPreferences prefs = getSharedPreferences("offpeak_alerts", Context.MODE_PRIVATE);
            String existingAlerts = prefs.getString("alerts_list", "[]");
            JSONArray alertsArray = new JSONArray(existingAlerts);
            
            JSONObject newAlert = new JSONObject();
            newAlert.put("title", title);
            newAlert.put("body", body);
            newAlert.put("timestamp", System.currentTimeMillis());
            
            alertsArray.put(newAlert);
            
            prefs.edit().putString("alerts_list", alertsArray.toString()).apply();
        } catch (Exception e) {
            Log.e(TAG, "Error saving notification", e);
        }
    }
}

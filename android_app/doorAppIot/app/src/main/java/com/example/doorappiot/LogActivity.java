package com.example.doorappiot;

// Android UI, Firebase, MQTT ve JSON işlemleri için gerekli kütüphaneler
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.ListView;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabase;
import com.google.firebase.database.ValueEventListener;
import org.eclipse.paho.client.mqttv3.IMqttActionListener;
import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken;
import org.eclipse.paho.client.mqttv3.IMqttToken;
import org.eclipse.paho.client.mqttv3.MqttCallback;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.json.JSONObject;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import android.widget.Toast;

public class LogActivity extends AppCompatActivity {
    // Logları ve fotoğrafları göstermek için UI bileşenleri
    private ListView logListView;
    private ArrayAdapter<String> adapter;
    private ArrayList<String> logList;
    private ArrayList<DataSnapshot> logSnapshots; // Firebase'den gelen ham verileri tutar
    private DatabaseReference logsRef; // Firebase'deki "logs" düğümüne referans
    private Button back, clear;
    private RecyclerView photoRecyclerView;
    private PhotoAdapter photoAdapter;
    private ArrayList<String> photoUrls;
    private MQTTManager mqttManager; // MQTT bağlantısını ve iletişimini yönetir
    private Handler handler; // MQTT abonelik gibi gecikmeli görevleri yönetir
    private static final String MQTT_TOPIC_STATUS = "doorapp/status"; // MQTT durum mesajları için konu

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_log);

        logListView = findViewById(R.id.logListView);
        logList = new ArrayList<>();
        logSnapshots = new ArrayList<>();
        adapter = new ArrayAdapter<>(this, android.R.layout.simple_list_item_1, logList);
        logListView.setAdapter(adapter);

        // Fotoğraf RecyclerView'ini başlat
        photoRecyclerView = findViewById(R.id.photoRecyclerView);
        photoUrls = new ArrayList<>();
        photoAdapter = new PhotoAdapter(this, photoUrls); // RecyclerView adaptörü
        photoRecyclerView.setLayoutManager(new LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false));
        photoRecyclerView.setAdapter(photoAdapter);
        back = findViewById(R.id.back);
        clear = findViewById(R.id.clear);
        logsRef = FirebaseDatabase.getInstance().getReference("logs"); // Firebase "logs" düğümü

        // MQTT bağlantısını başlat
        mqttManager = MQTTManager.getInstance(this); // Singleton MQTT yöneticisi
        mqttManager.connect(new MqttCallback() {
            @Override
            public void connectionLost(Throwable cause) {
                // Bağlantı koparsa burası çalışır, şu an boş
            }

            @Override
            public void messageArrived(String topic, MqttMessage message) {
                // MQTT'den mesaj geldiğinde çalışır
                if (topic.equals(MQTT_TOPIC_STATUS)) {
                    try {
                        // Gelen mesajı JSON olarak işle
                        JSONObject json = new JSONObject(new String(message.getPayload()));
                        String msg = json.getString("message");
                        // UI thread'inde Toast mesajı göster
                        runOnUiThread(() -> Toast.makeText(LogActivity.this, msg, Toast.LENGTH_SHORT).show());
                    } catch (Exception e) {
                        // JSON hatası durumunda sessizce geç
                    }
                }
            }

            @Override
            public void deliveryComplete(IMqttDeliveryToken token) {
                // Mesaj teslim edildiğinde çalışır, şu an boş
            }
        });

        // MQTT abonelik için 2 saniye gecikmeli görev
        handler = new Handler(Looper.getMainLooper());
        handler.postDelayed(() -> mqttManager.subscribe(MQTT_TOPIC_STATUS, 0, new IMqttActionListener() {
            @Override
            public void onSuccess(IMqttToken asyncActionToken) {
                // Abonelik başarılıysa çalışır, şu an boş
            }

            @Override
            public void onFailure(IMqttToken asyncActionToken, Throwable exception) {
                // Abonelik başarısızsa çalışır, şu an boş
            }
        }), 2000); // 2000ms (2 saniye) gecikme


        back.setOnClickListener(v -> {
            startActivity(new Intent(LogActivity.this, MainActivity.class)); // Ana ekrana dön
            finish(); // Bu ekranı kapat
        });


        clear.setOnClickListener(v -> clearLogs()); // Logları temizle

        // ListView'de bir log girişine tıklama olayı
        logListView.setOnItemClickListener((parent, view, position, id) -> {
            DataSnapshot snapshot = logSnapshots.get(position); // Tıklanan logun verisi
            String user = snapshot.child("user").getValue(String.class); // Kullanıcı bilgisi
            String photoUrl = snapshot.child("photo_url").getValue(String.class); // Fotoğraf URL'si

            photoUrls.clear();
            photoAdapter.notifyDataSetChanged();

            // Eğer kullanıcı "Bilinmiyor" ise ve fotoğraf varsa
            if ("Bilinmiyor".equals(user) && photoUrl != null) {
                photoUrls.add(photoUrl);
                photoAdapter.notifyDataSetChanged();
                photoRecyclerView.setVisibility(View.VISIBLE);
            } else {
                photoRecyclerView.setVisibility(View.GONE);
            }
        });

        // Firebase'den log verilerini dinle
        logsRef.addValueEventListener(new ValueEventListener() {
            @Override
            public void onDataChange(DataSnapshot dataSnapshot) {
                // Firebase verileri değiştiğinde çalışır
                logList.clear();
                logSnapshots.clear();
                SimpleDateFormat inputFormat = new SimpleDateFormat("dd/MM/yyyy HH:mm");
                SimpleDateFormat outputFormat = new SimpleDateFormat("dd/MM/yyyy HH:mm");

                // Her bir log girişini işle
                for (DataSnapshot snapshot : dataSnapshot.getChildren()) {
                    String eventType = snapshot.child("event_type").getValue(String.class); // Olay tipi
                    String timestamp = snapshot.child("timestamp").getValue(String.class); // Zaman damgası
                    String user = snapshot.child("user").getValue(String.class); // Kullanıcı
                    String message = snapshot.child("message").getValue(String.class); // Mesaj

                    // Gerekli veriler eksikse bu girişi atla
                    if (eventType == null || timestamp == null || user == null) continue;

                    // Zaman damgasını formatla
                    try {
                        Date date = inputFormat.parse(timestamp); // String'i Date'e çevir
                        timestamp = outputFormat.format(date); // Date'i istenen formata çevir
                    } catch (ParseException e) {
                        timestamp = "Bilinmeyen zaman"; // Hata durumunda varsayılan değer
                    }

                    // Mesaj varsa onu, yoksa olay tipini kullan
                    if (message != null) {
                        logList.add(String.format("%s: %s - %s", message, timestamp, user));
                    } else {
                        logList.add(String.format("%s: %s - %s", eventType, timestamp, user));
                    }
                    logSnapshots.add(snapshot); // Snapshot'ı kaydet
                }
                adapter.notifyDataSetChanged(); // ListView'ı güncelle
            }

            @Override
            public void onCancelled(DatabaseError databaseError) {
                // Firebase verileri alınamazsa hata mesajı ekle
                logList.add("Loglar yüklenemedi: " + databaseError.getMessage());
                adapter.notifyDataSetChanged(); // ListView'ı güncelle
            }
        });
    }
    private void clearLogs() {
        logsRef.removeValue((databaseError, databaseReference) -> {
            if (databaseError == null) {
                runOnUiThread(() -> {
                    logList.clear();
                    photoUrls.clear();
                    adapter.notifyDataSetChanged();
                    photoAdapter.notifyDataSetChanged();
                });
            }
        });
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (handler != null) {
            handler.removeCallbacksAndMessages(null); // Bekleyen tüm görevleri iptal et
        }
    }
}


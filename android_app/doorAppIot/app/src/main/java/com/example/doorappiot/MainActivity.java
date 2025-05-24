package com.example.doorappiot;

// Android UI, MQTT, Base64 ve JSON işlemleri için gerekli kütüphaneler
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;
import org.eclipse.paho.client.mqttv3.IMqttActionListener;
import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken;
import org.eclipse.paho.client.mqttv3.IMqttToken;
import org.eclipse.paho.client.mqttv3.MqttCallback;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.Base64;
import org.json.JSONObject;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends AppCompatActivity {
    // UI bileşenleri
    Button btnOnOff, btnOpen, btnLastSign;
    ImageView imageView;
    private boolean isOpenCamera = true;
    private MQTTManager mqttManager;
    private ExecutorService executorService;
    private Handler mainHandler;
    public static final String MQTT_TOPIC_VIDEO = "doorapp/video";
    public static final String MQTT_TOPIC_COMMAND = "doorapp/command";
    public static final String MQTT_TOPIC_STATUS = "doorapp/status";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        // UI bileşenlerini başlat
        btnOnOff = findViewById(R.id.btnOnOff);
        btnOpen = findViewById(R.id.btnOpen);
        btnLastSign = findViewById(R.id.btnLastSign);
        imageView = findViewById(R.id.imageView);
        imageView.setVisibility(View.VISIBLE);
        executorService = Executors.newSingleThreadExecutor(); // Tek iş parçacıklı thread havuzu
        mainHandler = new Handler(Looper.getMainLooper()); // UI thread'inde çalışır

        // MQTT bağlantısını başlat
        mqttManager = MQTTManager.getInstance(this); // Singleton MQTT yöneticisi
        mqttManager.connect(new MqttCallback() {
            @Override
            public void connectionLost(Throwable cause) {
                mainHandler.post(() -> Toast.makeText(MainActivity.this, "Bağlantı koptu", Toast.LENGTH_SHORT).show());
            }

            // bu kısımda zorlandım yapay zeka desteği aldım
            @Override
            public void messageArrived(String topic, MqttMessage message) {
                // MQTT'den mesaj geldiğinde çalışır
                try {
                    if (topic.equals(MQTT_TOPIC_VIDEO)) {
                        // Video konusu için gelen mesajı işle
                        String base64Image = new String(message.getPayload()); // Mesajı Base64 string olarak al
                        if (base64Image.isEmpty()) return; // Boşsa işlemi atla

                        // Görüntü işlemeyi arka planda yap
                        executorService.execute(() -> {
                            try {
                                // Base64'ü byte dizisine çevir
                                byte[] decodedBytes = Base64.decode(base64Image, Base64.DEFAULT);
                                // Byte dizisinden Bitmap oluştur
                                Bitmap bitmap = BitmapFactory.decodeByteArray(decodedBytes, 0, decodedBytes.length);
                                if (bitmap == null) return; // Bitmap oluşturulamadıysa çık
                                mainHandler.post(() -> {
                                    if (isOpenCamera && imageView != null && !isFinishing()) {
                                        imageView.setImageBitmap(bitmap); // Kameradan gelen görüntüyü göster
                                    }
                                });
                            } catch (IllegalArgumentException e) {
                                // Base64 hatası
                            }
                        });
                    } else if (topic.equals(MQTT_TOPIC_STATUS)) {
                        // Durum mesajı geldiğinde çalışır
                        JSONObject json = new JSONObject(new String(message.getPayload()));
                        String msg = json.getString("message"); // JSON'dan mesajı al
                        mainHandler.post(() -> Toast.makeText(MainActivity.this, msg, Toast.LENGTH_SHORT).show());
                    }
                } catch (Exception e) {
                    // Genel hata
                }
            }

            @Override
            public void deliveryComplete(IMqttDeliveryToken token) {
                // Mesaj teslim edildiğinde çalışır
            }
        });

        // MQTT konularına abone ol (2 saniye gecikmeyle)
        mainHandler.postDelayed(() -> {
            // Video akışına abone ol
            mqttManager.subscribe(MQTT_TOPIC_VIDEO, 0, new IMqttActionListener() {
                @Override
                public void onSuccess(IMqttToken asyncActionToken) {
                    // Abonelik başarılıysa çalışır
                }
                @Override
                public void onFailure(IMqttToken asyncActionToken, Throwable exception) {
                    // Abonelik başarısızsa çalışır, şu an boş
                }
            });

            // Durum mesajlarına abone ol
            mqttManager.subscribe(MQTT_TOPIC_STATUS, 0, new IMqttActionListener() {
                @Override
                public void onSuccess(IMqttToken asyncActionToken) {
                    // Abonelik başarılıysa çalışır, şu an boş
                }
                @Override
                public void onFailure(IMqttToken asyncActionToken, Throwable exception) {
                    // Abonelik başarısızsa çalışır, şu an boş
                }
            });
        }, 2000); // 2000ms (2 saniye) gecikme

        btnOnOff.setOnClickListener(v -> {
            if (!isOpenCamera) {
                // Kamera kapalıysa aç
                imageView.setVisibility(View.VISIBLE);
                btnOnOff.setText("Kamerayı Kapat");
                isOpenCamera = true;
            } else {
                // Kamera açıksa kapat
                imageView.setVisibility(View.GONE);
                btnOnOff.setText("Kamerayı Aç");
                isOpenCamera = false;
            }
        });

        // Kapı açma butonu tıklama olayı
        btnOpen.setOnClickListener(v -> mqttManager.publish(MQTT_TOPIC_COMMAND, new MqttMessage("open_door".getBytes()), new IMqttActionListener() {
            @Override
            public void onSuccess(IMqttToken asyncActionToken) {
                mainHandler.post(() -> Toast.makeText(MainActivity.this, "Kapı açma isteği gönderildi", Toast.LENGTH_SHORT).show());
            }
            @Override
            public void onFailure(IMqttToken asyncActionToken, Throwable exception) {
                mainHandler.post(() -> Toast.makeText(MainActivity.this, "Komut gönderilemedi", Toast.LENGTH_SHORT).show());
            }
        }));

        btnLastSign.setOnClickListener(v -> startActivity(new Intent(MainActivity.this, LogActivity.class)));
    }

    // Ekran kapatıldığında kaynakları temizle
    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (executorService != null) {
            executorService.shutdownNow();
        }
        mainHandler.removeCallbacksAndMessages(null); // Bekleyen tüm görevleri iptal et
    }
}
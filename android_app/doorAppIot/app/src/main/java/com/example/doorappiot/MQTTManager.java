package com.example.doorappiot;

// Android, MQTT ve SSL işlemleri için gerekli kütüphaneler
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;
import org.eclipse.paho.client.mqttv3.IMqttActionListener;
import org.eclipse.paho.client.mqttv3.IMqttToken;
import org.eclipse.paho.client.mqttv3.MqttCallback;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.MqttException;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.eclipse.paho.client.mqttv3.MqttAsyncClient;
import javax.net.ssl.SSLSocketFactory;
import java.util.UUID;

public class MQTTManager {
    // MQTT bağlantı ayarları
    private static final String MQTT_BROKER = "ssl://b1f620803145437196779c3dfbf61189.s1.eu.hivemq.cloud:8883";
    private static final String MQTT_CLIENT_ID = "android_client_" + UUID.randomUUID().toString(); // Benzersiz istemci kimliği
    private static final String MQTT_USER = "hivemq.webclient.1747640301127";
    private static final String MQTT_PASSWORD = "3$az7Ak>&BbWS0iF%8Xq";
    private static final int RECONNECT_INTERVAL = 8000; // Yeniden bağlanma aralığı (ms)

    // Singleton örneği ve diğer değişkenler
    private static MQTTManager instance; // Tekil MQTTManager örneği
    private MqttAsyncClient mqttClient; // MQTT istemcisi
    private Context context;
    private Handler handler; // UI thread'inde görevleri çalıştırmak için
    private boolean isConnecting;
    private MqttCallback currentCallback;

    private MQTTManager(Context context) {
        this.context = context.getApplicationContext();
        this.handler = new Handler(Looper.getMainLooper()); // UI thread'inde çalışacak Handler
        try {
            // MQTT istemcisini başlat
            mqttClient = new MqttAsyncClient(MQTT_BROKER, MQTT_CLIENT_ID, null);
        } catch (MqttException e) {
            showToast("MQTT istemci başlatma hatası");
        }
    }

    // Singleton örneğini döndürür
    public static synchronized MQTTManager getInstance(Context context) {
        if (instance == null) {
            instance = new MQTTManager(context);
        }
        return instance;
    }

    // MQTT sunucusuna bağlanma
    public void connect(MqttCallback callback) {
        // İstemci null, bağlanma devam ediyor veya zaten bağlıysa
        if (mqttClient == null || isConnecting || mqttClient.isConnected()) {
            mqttClient.setCallback(callback);
            currentCallback = callback;
            return;
        }

        isConnecting = true; // Bağlanma sürecini işaretle
        currentCallback = callback; // Geri çağrıyı kaydet
        MqttConnectOptions options = new MqttConnectOptions();
        options.setUserName(MQTT_USER);
        options.setPassword(MQTT_PASSWORD.toCharArray());
        options.setAutomaticReconnect(false);
        options.setCleanSession(false);
        options.setConnectionTimeout(30); // Bağlantı zaman aşımı (saniye)
        options.setKeepAliveInterval(60); // Canlı tutma aralığı (saniye)


        mqttClient.setCallback(callback); // İstemciye geri çağrıyı bağla

        try {
            // MQTT sunucusuna bağlan
            mqttClient.connect(options, null, new IMqttActionListener() {
                @Override
                public void onSuccess(IMqttToken asyncActionToken) {
                    isConnecting = false; // Bağlanma tamamlandı
                    showToast("MQTT bağlandı!");
                }

                @Override
                public void onFailure(IMqttToken asyncActionToken, Throwable exception) {
                    isConnecting = false; // Bağlanma başarısız
                    showToast("MQTT bağlantı hatası");
                    scheduleReconnect();
                }
            });
        } catch (MqttException e) {
            isConnecting = false; // Bağlanma başarısız
            showToast("MQTT başlatma hatası");
            scheduleReconnect();
        }
    }

    // Bağlantı koptuğunda yeniden bağlanma planlar
    private void scheduleReconnect() {
        handler.postDelayed(() -> {
            // İstemci bağlı değilse ve bağlanma denenmiyorsa yeniden bağlan
            if (!mqttClient.isConnected() && !isConnecting) {
                connect(currentCallback);
            }
        }, RECONNECT_INTERVAL); // Belirtilen aralıkta (8 saniye)
    }
    public void subscribe(String topic, int qos, IMqttActionListener listener) {
        // İstemci null veya bağlı değilse hata bildir
        if (mqttClient == null || !mqttClient.isConnected()) {
            if (listener != null) {
                listener.onFailure(null, new MqttException(new Exception("MQTT istemcisi bağlı değil")));
            }
            return;
        }

        try {
            // Konuya abone ol
            mqttClient.subscribe(topic, qos, null, listener);
        } catch (MqttException e) {
            if (listener != null) listener.onFailure(null, e);
        }
    }

    // Belirli bir konuya mesaj yayınlama
    public void publish(String topic, MqttMessage message, IMqttActionListener listener) {
        // İstemci null veya bağlı değilse hata bildir
        if (mqttClient == null || !mqttClient.isConnected()) {
            if (listener != null) {
                listener.onFailure(null, new MqttException(new Exception("MQTT istemcisi bağlı değil")));
            }
            return;
        }

        try {
            // Mesajı belirtilen konuya yayınla
            mqttClient.publish(topic, message, null, listener);
        } catch (MqttException e) {
            if (listener != null) listener.onFailure(null, e);
        }
    }

    // MQTT bağlantısını kes
    public void disconnect() {
        if (mqttClient != null && mqttClient.isConnected()) {
            try {
                mqttClient.disconnect(); // Bağlantıyı kes
            } catch (MqttException e) {
                // Hata durumunda sessizce geç
            }
        }
        handler.removeCallbacksAndMessages(null); // Bekleyen tüm görevleri iptal et
    }

    // UI thread'inde Toast mesajı göster
    private void showToast(String message) {
        new Handler(Looper.getMainLooper()).post(() ->
                Toast.makeText(context, message, Toast.LENGTH_SHORT).show());
    }
}
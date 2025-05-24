# Raspberry Pi üzerinde yüz tanıma ve kapı kontrol sistemi
# Picamera2 ile görüntü yakalar, yüz tanıma yapar ve Firebase/MQTT ile iletişim kurar
from picamera2 import Picamera2
import cv2
import dlib
import face_recognition
from flask import Flask, request, jsonify, Response
import threading
import signal
import sys
import time
import firebase_admin
from firebase_admin import credentials, db
from datetime import datetime, timezone, timedelta
import os
import numpy as np
import logging
import paho.mqtt.client as mqtt
import json
import base64
from dotenv import load_dotenv
import cloudinary
from cloudinary.uploader import upload

# Gereksiz logları kapatır (konsolu temiz tutmak için)
os.environ["LIBCAMERA_LOG_LEVELS"] = "ERROR"
cv2.setLogLevel(0)
log = logging.getLogger('werkzeug')
log.setLevel(logging.ERROR)

# Ortam değişkenlerini .env dosyasından yükler
load_dotenv()

# Türkiye saat dilimini ayarlar (+3 saat)
turkey_tz = timezone(timedelta(hours=3))

# Firebase bağlantısını başlatır
cred = credentials.Certificate("/home/baranpc/Desktop/home/Baran/Desktop/Pi/bina-giris-proje/serviceAccountKey.json")
firebase_admin.initialize_app(cred, {
    'databaseURL': 'https://doorappiot-default-rtdb.firebaseio.com/'
})

# Cloudinary ayarlarını yapar (fotoğraf yükleme için)
cloudinary.config(
    cloud_name=os.getenv('CLOUDINARY_CLOUD_NAME'),
    api_key=os.getenv('CLOUDINARY_API_KEY'),
    api_secret=os.getenv('CLOUDINARY_API_SECRET')
)

# MQTT bağlantı ayarları (HiveMQ Cloud)
MQTT_BROKER = "b1f620803145437196779c3dfbf61189.s1.eu.hivemq.cloud"
MQTT_PORT = 8883
MQTT_USER = "hivemq.webclient.1747640301127"
MQTT_PASSWORD = "3$az7Ak>&BbWS0iF%8Xq"
MQTT_CLIENT_ID = "raspberry_pi_client_" + str(int(time.time()))
MQTT_TOPIC_COMMAND = "doorapp/command"
MQTT_TOPIC_VIDEO = "doorapp/video"
MQTT_TOPIC_STATUS = "doorapp/status"

# MQTT istemcisini oluşturur ve bağlanır
mqtt_client = mqtt.Client(client_id=MQTT_CLIENT_ID, protocol=mqtt.MQTTv311, transport="tcp")
mqtt_client.username_pw_set(MQTT_USER, MQTT_PASSWORD)
mqtt_client.tls_set()

# MQTT bağlantı başarılıysa abone olur
def on_connect(client, userdata, flags, rc):
    if rc == 0:
        print("MQTT broker’ına bağlandı!")
        client.subscribe(MQTT_TOPIC_COMMAND)
    else:
        print(f"MQTT bağlantı hatası, kod: {rc}")

# MQTT üzerinden gelen komutları işler
def on_message(client, userdata, msg):
    global allow_open
    try:
        if msg.topic == MQTT_TOPIC_COMMAND:
            command = msg.payload.decode()
            if command == "open_door":
                allow_open = True
                log_event("door_opened", "Remote", "Kapı MQTT ile açıldı")
            elif command == "clear_photos":
                clear_photos()
    except Exception as e:
        print(f"MQTT mesaj işleme hatası: {e}")

mqtt_client.on_connect = on_connect
mqtt_client.on_message = on_message

# MQTT broker’ına bağlanmayı dener
try:
    mqtt_client.connect(MQTT_BROKER, MQTT_PORT, 60)
    mqtt_client.loop_start()
except Exception as e:
    print(f"MQTT bağlantı hatası: {e}")
    sys.exit(1)

# Flask web uygulamasını başlatır
app = Flask(__name__)

# Sistem için sabit değerler
FRAME_WIDTH = 640  # Kamera çözünürlüğü (genişlik)
FRAME_HEIGHT = 480  # Kamera çözünürlüğü (yükseklik)
FRAME_RATE = 15  # Kamera çerçeve hızı (FPS)
PROCESS_INTERVAL = 10  # Her kaç çerçevede bir işlem yapılacak
SCALE_FACTOR = 0.5  # Çerçeve küçültme oranı
GOZ_ESIK_DEGERI = 5  # Göz mesafesi eşiği
EYE_BLINK_THRESHOLD = 0.7  # Göz kırpma algılama eşiği
BLINK_CONSECUTIVE_FRAMES = 2  # Göz kırpma için gerekli ardışık çerçeve sayısı
LOG_INTERVAL = 5  # Log yazma aralığı (saniye)
NOTIFICATION_INTERVAL = 5  # Bildirim gönderme aralığı (saniye)

# İzinsiz giriş bildirimlerini saklar
unauthorized_notifications = []

# Global değişkenler
known_faces = []  # Bilinen yüz kodlamaları
known_names = []  # Bilinen yüz isimleri
goz_kirpma_sayisi = 0  # Göz kırpma sayısı
allow_open = False  # Uzaktan kapı açma izni
running = True  # Sistem çalışma durumu
shared_frame = None  # Paylaşılan çerçeve
frame_lock = threading.Lock()  # Çerçeve erişimi için kilit
last_log_time = 0  # Son log zamanı
last_notification_time = 0  # Son bildirim zamanı
blink_counter = 0  # Göz kırpma için geçici sayaç

# Bilinen yüzü (Baran) yükler
try:
    baran_image = face_recognition.load_image_file("/home/baranpc/Desktop/home/Baran/Desktop/Pi/bina-giris-proje/baran.jpg")
    baran_image_encoding = face_recognition.face_encodings(baran_image)[0]
    known_faces.append(baran_image_encoding)
    known_names.append("Baran")
except Exception as e:
    print(f"Yüz yükleme hatası: {e}")
    sys.exit(1)

# Yüz algılama ve landmark modellerini yükler
try:
    detector = dlib.get_frontal_face_detector()
    model = dlib.shape_predictor("shape_predictor_68_face_landmarks.dat")
except Exception as e:
    print(f"Model yükleme hatası: {e}")
    sys.exit(1)

# Kamerayı başlatır ve 640x480 çözünürlükle yapılandırır
picam2 = Picamera2()
preview_config = picam2.create_preview_configuration(
    main={"size": (FRAME_WIDTH, FRAME_HEIGHT), "format": "YUYV"},
    controls={"Brightness": -0.1, "Contrast": 0.8, "FrameRate": FRAME_RATE}
)
picam2.configure(preview_config)
picam2.start()

# İki noktanın ortasını hesaplar (göz noktaları için)
def mid(p1, p2):
    return (int((p1[0] + p2[0]) / 2), int((p1[1] + p2[1]) / 2))

# Göz kırpma durumunu kontrol eder
def check_blink(eye_ratio):
    global blink_counter
    if eye_ratio < EYE_BLINK_THRESHOLD:
        blink_counter += 1
        if blink_counter >= BLINK_CONSECUTIVE_FRAMES:
            return True
    else:
        blink_counter = 0
    return False

# Yüz çerçevesini çizer ve isim ekler
def draw_face_frame(frame, face_location, name, color=(0, 0, 255)):
    x, y, w, h = face_location
    cv2.rectangle(frame, (x, y), (w, h), color, 2)
    cv2.rectangle(frame, (x, h), (w, h + 30), color, -1)
    cv2.putText(frame, name, (x, h + 25), cv2.FONT_HERSHEY_PLAIN, 2, (255, 255, 255), 2)
    return frame

# Olayları Firebase’e kaydeder
def log_event(event_type, user, message=None, photo_url=None):
    global last_log_time
    current_time = time.time()
    if current_time - last_log_time >= LOG_INTERVAL:
        try:
            timestamp = datetime.now(turkey_tz).strftime("%d/%m/%Y %H:%M")
            log_data = {"timestamp": timestamp, "event_type": event_type, "user": user}
            if message:
                log_data["message"] = message
            if photo_url:
                log_data["photo_url"] = photo_url
            ref = db.reference('logs')
            ref.push(log_data)
            if user in ["Remote", "Bilinmiyor"]:  # Remote ve bilinmeyen kullanıcılar için ayrı log
                ref = db.reference('remote_and_unknown_logs')
                ref.push(log_data)
            mqtt_client.publish(MQTT_TOPIC_STATUS, json.dumps({
                "message": message or event_type,
                "user": user,
                "photo_url": photo_url if photo_url else None
            }))
            last_log_time = current_time
        except Exception as e:
            print(f"Log kaydı hatası: {e}")

# Bildirim gönderir ve MQTT ile yayınlar
def notify(message):
    global last_notification_time
    current_time = time.time()
    if current_time - last_notification_time >= NOTIFICATION_INTERVAL:
        print(message)
        mqtt_client.publish(MQTT_TOPIC_STATUS, json.dumps({"message": message}))
        last_notification_time = current_time

# İzinsiz giriş fotoğrafını kaydeder ve Cloudinary’ye yükler
def save_unauthorized_photo(frame):
    try:
        timestamp = datetime.now(turkey_tz).strftime("%Y%m%d_%H%M%S")
        temp_photo_path = f"/tmp/unauthorized_{timestamp}.jpg"
        cv2.imwrite(temp_photo_path, frame, [int(cv2.IMWRITE_JPEG_QUALITY), 70])
        response = upload(
            temp_photo_path,
            upload_preset=os.getenv('CLOUDINARY_UPLOAD_PRESET'),
            folder="doorapp_unauthorized",
            public_id=f"unauthorized_{timestamp}"
        )
        os.remove(temp_photo_path)  # Geçici dosyayı sil
        photo_url = response['secure_url']
        notification = {
            "timestamp": datetime.now(turkey_tz).strftime("%d/%m/%Y %H:%M"),
            "message": "İzinsiz giriş denemesi, kapı açılamadı (Bilinmiyor)",
            "photo_url": photo_url
        }
        unauthorized_notifications.append(notification)
        mqtt_client.publish(MQTT_TOPIC_STATUS, json.dumps({
            "message": notification["message"],
            "photo_url": photo_url
        }))
        return photo_url
    except Exception as e:
        print(f"Fotoğraf yükleme hatası: {e}")
        return None

# Bildirimleri temizler
def clear_photos():
    try:
        global unauthorized_notifications
        unauthorized_notifications = []
        mqtt_client.publish(MQTT_TOPIC_STATUS, json.dumps({"message": "Bildirimler temizlendi"}))
    except Exception as e:
        print(f"Bildirim temizleme hatası: {e}")

# Kameradan sürekli çerçeve yakalar
def capture_frames():
    global shared_frame, running
    while running:
        frame = picam2.capture_array()
        frame = cv2.cvtColor(frame, cv2.COLOR_YUV2BGR_YUYV)
        with frame_lock:
            shared_frame = frame.copy()

# Video çerçevelerini MQTT üzerinden yayınlar
def publish_video():
    global running, shared_frame
    while running:
        time.sleep(0.1)  # Her saniye bir çerçeve gönder
        with frame_lock:
            if shared_frame is not None:
                ret, buffer = cv2.imencode('.jpg', shared_frame, [int(cv2.IMWRITE_JPEG_QUALITY), 50])
                if ret:
                    base64_img = base64.b64encode(buffer).decode()
                    mqtt_client.publish(MQTT_TOPIC_VIDEO, base64_img)

# Yüz tanıma ve göz kırpma algılama döngüsü
def face_recognition_loop():
    global goz_kirpma_sayisi, allow_open, running, shared_frame
    last_blink_time = 0
    blink_interval = 0.5
    frame_count = 0
    while running:
        with frame_lock:
            if shared_frame is None:
                continue
            frame = shared_frame.copy()
        if frame is None or frame.size == 0:
            continue
        frame_count += 1
        if frame_count % PROCESS_INTERVAL != 0:  # Her 10. çerçevede işlem yap
            continue
        try:
            # Çerçeveyi küçült (işlem yükünü azaltmak için)
            small_frame = cv2.resize(frame, (0, 0), fx=SCALE_FACTOR, fy=SCALE_FACTOR)
            gray = cv2.cvtColor(small_frame, cv2.COLOR_BGR2GRAY)
            faces = detector(gray, 1)
            face_locations = []
            draw_locations = []
            kucuk_goz_esik_degeri = GOZ_ESIK_DEGERI * SCALE_FACTOR
            for face in faces:
                # Orijinal boyuta ölçekle
                left = int(face.left() / SCALE_FACTOR)
                top = int(face.top() / SCALE_FACTOR)
                right = int(face.right() / SCALE_FACTOR)
                bottom = int(face.bottom() / SCALE_FACTOR)
                face_locations.append((top, right, bottom, left))  # face_recognition formatı
                draw_locations.append((left, top, right, bottom))  # Çizim formatı
                points = model(gray, face)  # Yüz landmarklarını bul
                point_list = [(p.x, p.y) for p in points.parts()]
                # Göz noktalarını hesapla
                sag_goz_ust = mid(point_list[37], point_list[38])
                sag_goz_alt = mid(point_list[41], point_list[40])
                sol_goz_ust = mid(point_list[43], point_list[44])
                sol_goz_alt = mid(point_list[47], point_list[46])
                sag_goz_mesafe = sag_goz_alt[1] - sag_goz_ust[1]
                sol_goz_mesafe = sol_goz_alt[1] - sol_goz_ust[1]
                if (sol_goz_mesafe < kucuk_goz_esik_degeri and sag_goz_mesafe < kucuk_goz_esik_degeri and
                    (time.time() - last_blink_time) > blink_interval):
                    goz_kirpma_sayisi += 1
                    print(f"Göz kırpma {goz_kirpma_sayisi}")
                    last_blink_time = time.time()
            # Yüz tanıma işlemini gerçekleştir
            if face_locations:
                frame_rgb = cv2.cvtColor(frame, cv2.COLOR_BGR2RGB)
                faces_encodings = face_recognition.face_encodings(frame_rgb, face_locations)
                for i, face_encoding in enumerate(faces_encodings):
                    results = face_recognition.compare_faces(known_faces, face_encoding, tolerance=0.6)
                    name = "Bilinmiyor"
                    if results[0]:
                        name = "Baran"
                    frame = draw_face_frame(frame, draw_locations[i], name)  # Yüzü çerçeveye çiz
                    if goz_kirpma_sayisi == 3:  # 3 göz kırpmada kapı kontrolü
                        if name == "Baran":
                            notify("Baran kapıyı açtı")
                            log_event("door_opened", name, "Kapı açıldı")
                        elif allow_open:
                            notify("Uzaktan kapı açıldı (Remote)")
                            log_event("door_opened", "Remote", "Kapı uzaktan açıldı")
                            allow_open = False
                        else:
                            notify("İzinsiz giriş denemesi, kapı açılamadı (Bilinmiyor)")
                            photo_url = save_unauthorized_photo(frame)
                            log_event("unauthorized_attempt", name, "Kapı açılamadı", photo_url=photo_url)
                        goz_kirpma_sayisi = 0
            cv2.imshow("frame", frame)  # Çerçeveyi ekranda göster
            if cv2.waitKey(1) & 0xFF == ord("q"):  # 'q' tuşu ile çıkış
                break
        except Exception as e:
            print(f"İşlem hatası: {e}")
            continue

# Flask ile video akışını sağlar
@app.route('/video_feed')
def video_feed():
    def generate_frames():
        global running, shared_frame
        while running:
            with frame_lock:
                if shared_frame is None:
                    continue
                frame = shared_frame.copy()
            if frame is None or frame.size == 0:
                continue
            ret, buffer = cv2.imencode('.jpg', frame, [int(cv2.IMWRITE_JPEG_QUALITY), 70])
            if not ret:
                continue
            frame = buffer.tobytes()
            yield (b'--frame\r\n' + b'Content-Type: image/jpeg\r\n\r\n' + frame + b'\r\n')
    return Response(generate_frames(), mimetype='multipart/x-mixed-replace; boundary=frame')

# Logları ve fotoğrafları temizler
@app.route('/clear_logs', methods=['POST'])
def clear_logs():
    try:
        db.reference('logs').delete()
        db.reference('remote_and_unknown_logs').delete()
        clear_photos()
        return jsonify({"message": "Loglar ve fotoğraflar başarıyla temizlendi"})
    except Exception as e:
        print(f"Log ve fotoğraf temizleme hatası: {e}")
        return jsonify({"error": str(e)}), 500

# Uzaktan kapı açma isteğini işler
@app.route('/open_door', methods=['POST'])
def open_door():
    global allow_open
    allow_open = True
    log_event("door_opened", "Remote", "Kapı uzaktan açıldı")
    return jsonify({"message": "Kapı açma sinyali gönderildi, göz kırpması bekleniyor"})

# Sistemi güvenli şekilde kapatır
def signal_handler(sig, frame):
    global running
    running = False
    try:
        picam2.stop()
        picam2.close()
        cv2.destroyAllWindows()
        mqtt_client.loop_stop()
        mqtt_client.disconnect()
        firebase_admin.delete_app(firebase_admin.get_app())
    except Exception as e:
        print(f"Kapanma hatası: {e}")
    sys.exit(0)

# Ana programı başlatır
if __name__ == '__main__':
    signal.signal(signal.SIGINT, signal_handler)  # Ctrl+C için sinyal yakalayıcı
    threading.Thread(target=capture_frames, daemon=True).start()  # Çerçeve yakalama iş parçacığı
    threading.Thread(target=face_recognition_loop, daemon=True).start()  # Yüz tanıma iş parçacığı
    threading.Thread(target=publish_video, daemon=True).start()  # Video yayınlama iş parçacığı
    app.run(host="0.0.0.0", port=5000, use_reloader=False)  # Flask uygulamasını başlat
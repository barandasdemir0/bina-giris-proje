# Göz Kırpma ile Doğrulanan Yüz Tanıma Tabanlı IoT Kapı Güvenlik Sistemi

Bu depo, Raspberry Pi 5 ve Pi Camera V3 kullanılarak geliştirilen bir IoT kapı güvenlik sisteminin kaynak kodlarını içerir. Sistem, yüz tanıma ve göz kırpma ile canlılık doğrulamasını birleştirerek kimlik kontrolü yapar. Ana özellikler şunlardır:
- Yüz tanıma: OpenCV ve dlib kütüphaneleri ile gerçekleştirilir.
- Canlılık doğrulama: Göz kırpma tespiti (EAR yöntemi) ile sahte girişimleri önler.
- IoT entegrasyonu: MQTT protokolü ile Android mobil uygulama üzerinden uzaktan kontrol.
- Bulut desteği: Firebase Realtime Database ile olay kaydı ve Cloudinary ile tanınmayan yüz arşivleme.

## İçerik
- **Python dosyaları**: Raspberry Pi üzerinde çalışan ana kodlar (yüz tanıma, göz kırpma tespiti, MQTT iletişimi).
- **Android dosyaları**: Java ile Android Studio'da geliştirilen mobil uygulama kodları (MQTT istemcisi, kullanıcı arayüzü).

## Kurulum ve Kullanım
1. Donanım: Raspberry Pi 5, Pi Camera V3 ve kapı kilidi mekanizması.
2. Yazılım: Python 3, OpenCV, dlib, face_recognition, paho-mqtt, pyrebase kütüphaneleri.
3. Android: Android Studio ile uygulama derleme ve MQTT yapılandırması.
4. Detaylar için dökümantasyonu kontrol edin.

## Geliştirici
- **Adı**: Baran Daşdemir
- **Üniversite**: Sivas Cumhuriyet Üniversitesi, Şarkışla Uygulamalı Bilimler Yüksekokulu
- **Bölüm**: Bilişim Sistemleri ve Teknolojileri
- **İletişim**: barandasdemir.bd@gmail.com



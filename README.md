# Acad

Uğur Altun Mimarlık & Mühendislik için DWG / DXF görüntüleyici (Android).

- Katmanları aç/kapat; mesafe, alan, açı ve yarıçap ölç.
- Yakalama: uç, orta, merkez, kesişim, dik, en yakın nokta; büyüteçle hassas seçim.
- İşaretleme: revizyon bulutu, ok, not, kalem, fotoğraf (kamera ya da galeri). İşaretler dosya başına cihazda saklanır.
- PDF rapor: çizim + numaralı not ve fotoğraf sayfaları; İndirilenler/Acad klasörüne kaydedilir ve paylaşılır.
- Pafta (layout) görünümü: görünüm pencereleriyle birlikte.
- Dosyalar yalnızca cihazda açılır, hiçbir sunucuya gönderilmez.
- Dosya yöneticisinde ya da WhatsApp'ta bir DWG/DXF'e dokunup "Acad ile aç" denebilir.

## APK

Her `main` güncellemesinde GitHub Actions APK'yı derler ve **Releases** sayfasına ekler.

## Lisans notu

DWG okuma için [libredwg-web](https://github.com/mlightcad/libredwg-web) (GPL-3.0) kullanılır;
`app/src/main/assets/www/dwg/` altındaki dosyalar bu projeye aittir (eval kullanmayacak şekilde küçük bir yama yapıldı).

# OpenGlyph

Nothing Phone (3) (`metroid`) için açık kaynak Glyph Matrix servisi. Nothing'in kapalı
`com.nothing.thirdparty` uygulaması yerine doğrudan çekirdekteki `matrix-leds` sürücüsünün
sysfs düğümlerine yazar. Hedef: crDroid / LineageOS tabanlı ROM'lar.

## Donanım (Nothing'in GPL çekirdek kaynağından)

Kaynak: `NothingOSS/android_kernel_msm-6.6_nothing_sm8735`, `drivers/leds/matrix-leds/`.

- 25×25 ızgara, ama yalnızca **489 LED** var. Satır uzunlukları:
  `7 11 15 17 19 21 21 23 23 25 25 25 25 25 25 25 23 23 21 21 19 17 15 11 7`,
  her satır ortalanmış, sıralama satır-satır (sol üstten).
- Donanım parlaklığı **0–2047**.
- Düğümler (`.../spi10.0/leds/matrix-leds/`):
  - `frame_brightness`: boşlukla ayrılmış 489 değer = tek kare. Tek değer verilirse hepsine uygulanır.
    ⚠️ Sürücü 489'dan fazla değeri yığındaki diziye sınır kontrolü yapmadan yazıyor. Fazlasını
    asla göndermeyin, `SysfsMatrixSink` bunu engelliyor.
  - `led_power_on`: 1/0. `frame_brightness` gücü kendisi açmıyor.
  - `all_brightness`: `0` her şeyi söndürür ve gücü keser.
- `/dev/matrix_leds` (mmap + ioctl akış modu, 60 fps) de var. Animasyonlar için 2. aşamada
  kullanılabilir. Bu düğümün `write()` yolu yalnızca 489 **bayt** kopyalıyor (yarım kare),
  o yüzden kullanmıyoruz.

## Yapı

```
src/org/openglyph/core/      Android'siz çekirdek: düzen, kare, renderer, sysfs yazıcı
src/org/openglyph/effects/   Bildirim darbesi, şarj seviyesi, gelen arama
src/org/openglyph/*.java     Android servisi, bildirim dinleyicisi, AIDL API
aidl/                        Uygulamalar için IOpenGlyph arayüzü
sepolicy/                    Ek SELinux kuralları
tools/matrix-test.sh         Servisten önce sürücüyü ve LED düzenini cihazda doğrulama
tests/                       Düz JDK ile çalışan testler (cihaz gerekmez)
```

Öncelikler: en yüksek öncelikli aktif efekt matrisi alır.
Arama 90 > bildirim 50 > şarj 30 > uygulamalar (en fazla 80).

## Testler

```bash
sh tests/run.sh
```

## ROM ağacına ekleme

1. Bu klasörü `packages/apps/OpenGlyph` olarak koyun (ya da local manifest'e repo olarak ekleyin).
2. `device/nothing/metroid/device.mk`:
   ```make
   PRODUCT_PACKAGES += OpenGlyph
   ```
   Ağaçtaki yorum satırına alınmış `NtThirdParty`/`GlyphNotification` bloğu kapalı kalsın.
3. `sepolicy/openglyph.te` dosyasını `device/nothing/metroid/sepolicy/private/` içine kopyalayın.
   Ağaçtaki mevcut `glyph.te` ve `genfs_contexts` zaten `system_app`'e yazma izni veriyor.
4. Derleyip flash'ladıktan sonra:
   ```bash
   adb logcat -s OpenGlyph
   adb logcat | grep 'avc.*matrix'
   adb shell settings put secure open_glyph_brightness 128
   ```

## İlk cihaz testi (servisten önce)

userdebug derlemede `adb root` ile çalıştırın:

```bash
tools/matrix-test.sh walk
```

Tek LED sol üstten başlayıp satır satır sağa ilerlemeli. Farklı bir sıra görürseniz
`MatrixLayout` güncellenmeli. Ardından `rows`, `cross`, `all` ve `off` ile kontrol edin.

## Yol haritası

- [x] LED düzeni, sysfs yazıcı, renderer, öncelik sistemi (host testleri geçiyor)
- [x] Bildirim, şarj ve gelen arama efektleri, uygulamalar için AIDL API
- [x] LED sırası telefonda doğrulandı (stok + Magisk root, `tools/matrix-test.sh`)
- [x] Renderer + efektler telefonda gerçek sürücüyle doğrulandı (`tools/device-demo.sh`)
- [ ] Servis olarak cihazda doğrulama (SELinux, açılışta başlama, güç yönetimi)
- [ ] Ayarlar ekranı (crDroid Ayarlar → Glyph)
- [ ] Glyph Button (arkadaki dokunmatik tuş): input olayını bulmak gerekiyor
- [ ] Saat, pil gibi "toy"lar ve flip-to-glyph
- [ ] Ortam ışığına göre otomatik parlaklık (`ro.vendor.glyph.array.auto.*` değerleri)
- [ ] Akıcı animasyonlar için `/dev/matrix_leds` akış modu

## Nothing SDK uyumluluğu hakkında

Nothing'in Glyph Matrix SDK'sı (`glyph-matrix-sdk-2.0.aar`) kapalı kaynak ve lisansı tersine
mühendisliği yasaklıyor. Bu yüzden SDK'nın servise konuştuğu iç protokol burada **taklit
edilmiyor**. SDK ile yazılmış üçüncü taraf "toy"lar şimdilik çalışmaz. Kendi uygulamalar
`IOpenGlyph` API'sini kullanır.

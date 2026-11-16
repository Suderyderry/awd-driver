# AWD Driver (Arjawinangun Delivery)

Aplikasi Android (Kotlin + Jetpack Compose) untuk driver, terhubung ke Supabase yang sama dengan
panel **AWD Admin** (`https://suderyderry.github.io/awd-admin/`), aplikasi **AWD Pelanggan**, dan **AWD Toko**.

> Status: kode belum pernah dikompilasi atau dijalankan di perangkat. Build pertama mungkin menampilkan
> galat kecil (versi library, import). Kirim log galatnya untuk diperbaiki.

## Menu bawah
Beranda · Aktivitas · Chat · Profil. Teks "by. Wong Arjawinangun 2026" ada di layar masuk, Beranda,
Aktivitas, Chat, dan Profil.

## Tampilan dari panel admin
Header (template, logo, gambar banner, warna, tinggi, posisi logo, jenis huruf, slogan, kolom pencarian)
membaca `pengaturan.tampilan_driver`, yaitu yang diatur di panel: Pengaturan tampilan > Aplikasi Driver.
Simpan di panel, aplikasi mengikuti (diperbarui tiap ~60 detik saat terbuka). Pengaturan panel lain
(layanan, akses menu) bisa ditambahkan dengan cara yang sama di tahap berikutnya.

## Lonceng dan nada dering
- Ikon lonceng di header Beranda menampilkan jumlah order baru.
- Saat driver **Online**, layanan latar belakang memeriksa order tiap 8 detik dan membunyikan nada dering
  HP (notifikasi berdering berulang hingga dibuka, maks. 30 detik) walau aplikasi ditutup atau layar mati.
- Butuh izin Lokasi dan Notifikasi saat pertama kali online. Bila notifikasi ditolak, nada dering hanya
  diputar sekali.
- Nada mengikuti pengaturan HP (mode senyap/jangan ganggu tetap berlaku).
- Catatan: HP merek tertentu (Xiaomi, Oppo, Vivo, dll.) sering mematikan aplikasi latar belakang. Atur
  baterai aplikasi ke "Tanpa batasan". Untuk andal 100% perlu push notification (FCM), tahap berikutnya.

## Peta titik ambil dan titik antar
Tiap kartu order punya tombol "Lihat peta" (di Beranda dan Aktivitas). Peta (OpenStreetMap, tanpa API key) menampilkan:
- **A** hijau = titik ambil (toko, dari `alamat_ambil`),
- **B** merah = titik antar (pelanggan, dari `alamat_antar`),
- titik biru = posisi driver, jarak garis lurus, dan tombol **Navigasi** ke Google Maps (mode motor) untuk rute jalan.
Tujuan berikutnya ditandai otomatis (A sebelum barang diambil, B saat status Diantar). Driver juga bisa
menerima order atau mengubah status langsung dari layar peta.

Sumber koordinat:
1. Kolom `pesanan.ambil_lat/ambil_lng/antar_lat/antar_lng` (titik tepat; diisi aplikasi pelanggan/merchant
   lewat pin lokasi, atau admin). Ditandai "Titik tepat dari data order".
2. Bila kosong, aplikasi mencari koordinat dari teks alamat lewat OpenStreetMap Nominatim dan menandainya
   "Titik perkiraan" (hasil disimpan di HP agar tidak dicari ulang). Alamat desa biasanya ketemu di tingkat
   desa/kecamatan, belum tentu persis rumahnya. Karena itu aplikasi pelanggan dan merchant sebaiknya
   menyimpan pin lokasi.
Catatan: server peta/pencarian OpenStreetMap gratis untuk pemakaian ringan. Bila driver sudah banyak,
pakai penyedia berbayar (mis. Google Maps SDK atau MapTiler).

## Sinkronisasi dengan panel admin dan database AWD
Aplikasi tidak lagi memakai skema relasional lama (`driver`, `profiles`, `pesanan` dengan kolom terpisah). Semua data
mengikuti database panel AWD (tabel `pengguna`, `pesanan`, `pengaturan` berisi JSON). Penghubungnya adalah view dan fungsi
di `supabase/2_pasang_database.sql` (BAGIAN F), sehingga kode aplikasi hanya berubah di `Sb.kt` (nama view):

| Aplikasi membaca | Sumber di database AWD |
|---|---|
| `drv_driver`, `drv_profil` | `pengguna` bertipe `driver` milik akun yang login (kendaraan, plat, NIK, status, online, rating) |
| `drv_pesanan` | `pesanan`: tawaran (toko sudah menerima, belum ada driver) dan pesanan milik driver; komisi = ongkir x komisi di Pengaturan Sistem |
| `drv_pengaturan` | `pengaturan`: `tampilan_driver`, `layanan`, `tarif`, `ketentuan_driver`, sebagian `sistem`, dan banner target Driver/Semua |
| `chat_driver` | tabel baru; admin membalas di panel: Notifikasi & Pesan > Chat dengan driver |

Alur pesanan: Pelanggan memesan (Baru) > toko menerima (Diproses) > muncul sebagai tawaran bagi driver Online > driver terima
(nama dan id driver tersimpan di pesanan) > Diantar > Selesai. Lokasi driver disimpan di `pengguna` (kolom GPS) dan dipakai
peta pelacakan di aplikasi Pelanggan dan Toko, serta halaman Data Driver di panel.

Diatur dari panel: Tampilan Aplikasi Driver, Tarif pengantaran (Peta & Operasional), Ketentuan driver dan komisi (Pengaturan
Sistem), banner (Manajemen Konten > Tampil di aplikasi = Driver/Semua), verifikasi driver (Data Driver: ubah status Menunggu
verifikasi menjadi Aktif), foto wajah dan status online (Data Driver > Data dari aplikasi AWD Driver).

## Biodata dan foto wajib
Setelah daftar/masuk, driver wajib mengisi nama, NIK (16 digit), HP, alamat, kendaraan, plat, dan foto wajah. Foto disimpan
di bucket privat `foto-driver` (hanya driver bersangkutan dan admin yang bisa melihat). Driver baru berstatus
"Menunggu verifikasi" sampai admin mengaktifkannya di panel. Bila "Pendaftaran driver baru" dimatikan di panel, database menolak driver baru.

## Login nomor HP dan ikon
Masuk dan daftar memakai **nomor HP + kata sandi** (minimal 6 karakter). Akun Supabase dibuat dari nomor HP
(`08xxxxxxxxxx@driver.awd.app`), jadi tidak perlu email asli. Nomor HP otomatis terisi di form biodata.
Ikon aplikasi (merah) ada di `app/src/main/res/mipmap-*` (adaptive dan legacy); untuk mengganti, ganti file-file `ic_launcher*.png`.

## Langkah pasang
1. Supabase > SQL Editor: jalankan `supabase/1_bersihkan_database.sql` (hanya jika mengulang) lalu `supabase/2_pasang_database.sql`.
2. Authentication > Providers > Email: Allow new users to sign up = ON, Confirm email = OFF (atau driver konfirmasi lewat email).
3. Unggah isi folder ini ke GitHub (termasuk `.github`), lalu Actions > Build APK, unduh artifact `awd-driver-debug`.
4. Daftar akun driver di aplikasi (nomor HP dan kata sandi), isi biodata. Di panel: Data Driver > ubah status menjadi Aktif.
5. Di aplikasi aktifkan Online. Pesanan yang sudah diterima toko muncul sebagai tawaran.

## Keamanan
- Hanya kunci publishable di aplikasi. Jangan pernah memakai service_role / secret key.
- View driver hanya menampilkan data akun yang sedang login; perubahan status/online/lokasi lewat fungsi database yang memeriksa pemilik dan alur status.

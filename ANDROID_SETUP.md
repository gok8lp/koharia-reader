# Android kurulumu ve sunucu bağlantısı

<!-- Flugel: kişisel derleme, imzalama ve onay gerektiren sunucu kurulumu. -->

Flugel çeşidi ilk açılışta Komga ve Kavita profillerini hazırlar. Komga genel adresi
`https://manga.gokalpgoksu.com`, iç adresi `http://192.168.0.15:8296`; Kavita genel adresi
`https://ln.gokalpgoksu.com`, iç adresi `http://192.168.0.15:8295` olur. Kitaplar sekmesi Kavita'yı kullanır.
İlk kurulumda kendi hesap bilgilerinizi girin; parolalar, API anahtarları ve oturum tokenları kaynak koda veya APK'ya eklenmez.

İç adres ancak kimlik bilgisi göndermeyen sağlayıcı doğrulaması başarılıysa kullanılır. Doğrulanamayan yerel adreslerde
genel HTTPS adresi kullanılır. İsteğe bağlı indirme/yükleme menüleri varsayılan olarak gizlidir;
çalışan HTTPS hizmetiniz varsa `-PflugelDownloadUrl=https://...` veya `-PflugelUploadUrl=https://...` ile açılır.

## Derleme ve sabit imza

Gradle 9.8 için çalıştırma ortamı `.github/.java-version` dosyasındaki Java 21'dir; uygulamanın JVM hedefi Java 17 kalır.
GitHub Actions'taki **Flugel signed APK** iş akışı biçim denetimini, Kotlin derlemesini, birim testlerini ve
`:app:assembleFlugelRelease` görevini çalıştırır. Her yeni iş akışı çalışmasında `versionCode = 100000 + github.run_number`
olur. Yayımlanan sürüm numarası yeniden kullanılmaz; aynı çalışmayı yeniden denemek yerine yeni bir manuel çalışma başlatın.

GitHub deposunun Actions Secrets bölümüne şu değerler eklenir:

| Secret | İçerik |
| --- | --- |
| `FLUGEL_KEYSTORE_BASE64` | Sabit kişisel JKS anahtar dosyasının base64 içeriği |
| `FLUGEL_KEYSTORE_PASSWORD` | Anahtar deposu parolası |
| `FLUGEL_KEY_ALIAS` | İmza anahtarının alias değeri |
| `FLUGEL_KEY_PASSWORD` | İmza anahtarı parolası |
| `TS_AUTHKEY` | Dağıtım işçisi için ephemeral/reusable Tailscale auth key; gerekli tailnet erişimi tanımlanmalı |
| `FLUGEL_DEPLOY_SSH_KEY` | Yalnız yayınlama için oluşturulan Ed25519 özel anahtarı |
| `FLUGEL_DEPLOY_KNOWN_HOSTS` | Güvenilir sunucudan doğrulanmış `192.168.0.15` SSH host-key kaydı |

İlk dört Secret derleme için, diğerleri yalnız sunucuya dağıtım için gereklidir. Anahtarlar repoya eklenmez.
İmza anahtarının şifreli bir yedeğini güvenli yerde saklayın; kaybedilirse aynı uygulamaya güncelleme yüklenemez.
SSH host anahtarını dağıtım sırasında `ssh-keyscan` ile körlemesine kabul etmeyin; sunucu konsolu veya önceden
doğrulanmış SSH bağlantısıyla edinip parmak izini doğrulayın.

`app.koharia.flugel` kişisel paketi sabit anahtarla imzalanır. Önceki farklı paket/imza ile kurulmuş debug APK'nın
üzerine güncelleme kurulamaması normaldir; mevcut uygulamanın yedeğini alıp kişisel APK'yı ayrıca kurun.

## Sunucuda bir kez yapılacak kurulum

**Sunucu sahibinin onayı alınmadan bu adımlar çalıştırılmaz.** Hazırlanan
[`tools/flugel/setup-server.sh`](tools/flugel/setup-server.sh) şu değişiklikleri yapar:

1. `10444` portunda mevcut bir Tailscale Serve yapılandırması varsa durur.
2. `/home/flugel/flugel-reader-updates` yayın dizinini oluşturur.
3. Kullanıcının özel dizinine yalnız yayın kabul eden `release.py` komutunu kurar.
4. Ayrı dağıtım public key'ini `restrict,command="... publish ... --ssh"` kısıtlarıyla
   `/home/flugel/.ssh/authorized_keys` dosyasına ekler; mevcut diğer kayıtları korur.
5. `tailscale serve --bg --https=10444 /home/flugel/flugel-reader-updates` çalıştırır.
   Mevcut `10443` Komga yayınına ve Cloudflare tüneline dokunmaz; `serve reset` kullanmaz.

Onaydan sonra `tools/flugel/` dosyaları ve yalnız dağıtım public key'i sunucuya kopyalanıp `flugel` kullanıcısıyla çalıştırılır:

```bash
bash tools/flugel/setup-server.sh /path/to/flugel-reader-deploy.pub
```

Bu HTTPS adresi yalnız tailnet içinden erişilir:
`https://flugelserver.tail42f1f4.ts.net:10444/latest.json`. Telefonda güncelleme kontrolü/indirmesi için Tailscale açık olmalıdır.
Hesap girişleri Cloudflare üzerindeki mevcut genel Komga/Kavita adreslerini kullanabilir.

## Yayınlama ve güncelleme

İlk olarak Actions iş akışını `deploy_server=false` ile çalıştırın. İş akışı APK imzasını doğrular ve tam derlenen
commit'e bağlı GitHub prerelease olarak APK, SHA-256 dosyası ve `latest.json` yayımlar.
Sunucu kurulumu onaylanıp tamamlandıktan sonra sonraki çalışmalarda `deploy_server=true` seçilir.
Dağıtım işçisi Tailscale'e katılır, alt ağ rotalarını kabul eder ve `flugel@192.168.0.15` adresine
kısıtlı SSH anahtarıyla bağlanır. SSH yalnız `publish` komutuna izin verir; SCP, shell ve port yönlendirme kapalıdır.

Sunucu gelen arşivin üyelerini, HTTPS indirme adresini, sürüm numarasını, boyutu ve SHA-256 değerini doğrular.
APK önce değiştirilemez `releases/<versionCode>/Flugel-Manga.apk` yoluna yerleştirilir; `latest.json` son adımda atomik
olarak değiştirilir. Eksik veya bozuk aktarım mevcut sürümü değiştirmez. Aynı sürüm koduyla farklı APK ve eski sürüme
dönüş reddedilir. HTTPS üzerinden yayımlanan dosya, iş akışının ürettiği dosyayla ayrıca karşılaştırılır.

Uygulama açılışta daha yüksek `versionCode` arar; indirme için kullanıcıya teklif gösterir. APK kurulumuna geçmeden
önce SHA-256 ve boyut doğrulanır. Android'in paket kurucusu güncellemenin mevcut uygulamayla aynı imzayı taşımasını
zorunlu kılar. Sessiz veya izinsiz APK kurulumu yapılmaz.

Araçların yerel testi (sunucuya bağlanmaz):

```bash
PYTHONDONTWRITEBYTECODE=1 python3 -m unittest discover -s tools/flugel -p 'test_*.py' -v
bash -n tools/flugel/setup-server.sh
```

Fiziksel cihazda ilk giriş, Wi-Fi/mobil/Tailscale geçişleri, okuma/ilerleme kaydı, Cloudflare SSE yeniden bağlantısı ve
Android'in APK kurulum izni ayrıca doğrulanmalıdır. Test sırasında mevcut uygulama/verileri otomatik silinmez.

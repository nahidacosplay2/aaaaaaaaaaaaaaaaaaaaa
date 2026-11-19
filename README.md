# Zapret Aris — mobile interface prototype

Тёмный мобильный редизайн панели сетевых профилей в палитре Arisu: холодный синий, циановые сигналы, компактная навигация и отдельный выбор профиля и транспорта.

## Скачать исходный APK на телефон

APK уже доступен в корне GitHub-репозитория: [скачать ZapretAndroid v1.10.4-r3](https://raw.githubusercontent.com/nahidacosplay2/aaaaaaaaaaaaaaaaaaaaa/main/ZapretAndroid-v1.10.4-r3.apk). Это исходный загруженный APK, **не сборка Zapret Aris**. SHA-256: `ceb4e304fd73cb84bacdeeb3c4f8a0427e6c265c9d53618ba19f65282231ee64`.

## Запуск UI-превью

```bash
python3 -m http.server 4173 --bind 0.0.0.0
```

Откройте `http://localhost:4173` локально или используйте live preview. Работает без сборки и внешних библиотек.

## Что находится в репозитории

- `ZapretAndroid-v1.10.4-r3.apk` — Android-пакет.
- `ZapretAndroid-v1.10.4-r3.zip` — содержимое APK (manifest, DEX, нативные библиотеки и assets), а не исходный Android/Gradle-проект.
- `index.html`, `styles.css`, `app.js` — интерактивный мобильный UI-прототип.
- `assets/arisu-reference.png` — приложенный арт, использованный в оформлении.
- `downloads/README.md` и `SHA256SUMS.txt` — страница загрузки и контрольные суммы.
- `docs/ZAPRET-ARIS-ROADMAP.md` — сравнение текущих файлов и план интеграции Xray/диагностики.

Исходные APK и ZIP оставлены без изменений. Прототип позволяет выбирать профили (Авто, Flowseal General, QUIC/UDP, Custom), транспорт (Android VPN/TUN, SOCKS5, WebSocket), DNS и приложения; SOCKS5 можно направить на уже запущенный локальный вход Xray, но само ядро здесь не запускается. Конфигурация хранится только в браузере. Нативная VPN-служба и проверка реального трафика в этот интерфейс не подключены — для пересборки самого APK нужен исходный Android-проект.

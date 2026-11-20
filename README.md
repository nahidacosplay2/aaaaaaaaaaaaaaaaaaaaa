# Zapret Aris — Android project and mobile UI preview

В репозитории находятся исходники Android-приложения Zapret Aris и отдельный браузерный прототип его интерфейса. **Публичного релиза APK пока нет.** Чужие/референсные APK и ZIP удалены: они не являются сборкой Aris и не нужны для сборки исходников.

## Что есть в проекте

- `android-app/` — Java/XML Android-приложение. В исходниках предусмотрены два VPN-маршрута: локальный Zapret через ByeDPI/HEV и upstream SOCKS5 через HEV; прокси Telegram — отдельная служба.
- `index.html`, `styles.css`, `app.js` — автономный интерактивный UI-прототип. Он сохраняет демонстрационные параметры только в `localStorage` браузера и **не запускает Android VPN, Xray, SOCKS5 или WebSocket-соединение**.
- `assets/arisu-reference.png`, `assets/arisu-mark.svg` — графика прототипа.
- `docs/ZAPRET-ARIS-ROADMAP.md` — текущий статус, ограничения и план проверки.

Обход зависит от оператора и сети. В SOCKS5-режиме нужен доступный SOCKS5-сервер; VLESS/VMess-ссылки сами по себе не поддерживаются. TCP-проба не подтверждает работу UDP, DNS или системного VPN-туннеля. См. [Android README](android-app/README.md).

## Открыть UI-прототип

```bash
python3 -m http.server 4173 --bind 0.0.0.0
```

Затем откройте `http://localhost:4173` в браузере на той же машине. Для Arena live preview используется порт, который предоставит среда.

## Собрать Android-приложение

Требуются JDK 17, Android SDK 35 и NDK `28.2.13676358`. См. инструкции в [android-app/README.md](android-app/README.md). Из каталога `android-app/`:

```bash
./gradlew assembleRelease
```

Workflow [.github/workflows/android-apk.yml](.github/workflows/android-apk.yml) собирает release-вариант для проверки в CI и прикладывает APK с SHA-256 к запуску GitHub Actions на 14 дней; артефакты доступны на странице [Actions](https://github.com/nahidacosplay2/aaaaaaaaaaaaaaaaaaaaa/actions/workflows/android-apk.yml). Без release-keystore APK подписан временным CI debug-ключом: это тестовая сборка, не стабильный релиз, не для Google Play и не для обновления между разными запусками CI. Сборка сама по себе также не заменяет проверку на реальном устройстве. Не устанавливайте APK из постороннего источника, выдавая его за Aris; готовый релиз появится только после device-тестов и подписи постоянным ключом.

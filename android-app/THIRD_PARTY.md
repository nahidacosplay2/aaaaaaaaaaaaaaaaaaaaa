# Сторонние компоненты

| Компонент | Откуда | Лицензия | Где используется |
| :--- | :--- | :--- | :--- |
| ByeDPI 17.3 (`ciadpi`) | [hufrea/byedpi](https://github.com/hufrea/byedpi) | MIT (`native/third_party/byedpi/LICENSE`) | движок обхода |
| hev-socks5-tunnel 2.19.3 | [heiher/hev-socks5-tunnel](https://github.com/heiher/hev-socks5-tunnel) | MIT (`native/third_party/hev-socks5-tunnel/LICENSE`) | VPN |
| HEV core | вместе с hev-socks5-tunnel | MIT (`native/third_party/hev-socks5-tunnel/src/core/LICENSE`) | VPN |
| lwIP, libyaml, hev-task-system | вместе с hev-socks5-tunnel | лицензии и notices в `native/third_party/hev-socks5-tunnel/third-part/*/LICENSE` | VPN |
| Стратегии, списки, фейки, IPSet, hosts | github.com/Flowseal/zapret-discord-youtube | MIT | обход |
| tg-ws-proxy (логика перенесена на Java) | github.com/Flowseal/tg-ws-proxy | MIT, см. `app/src/main/java/org/zapretcap/tgproxy/LICENSE-tg-ws-proxy.txt` | прокси Telegram |
| Список целей проверки фризов 16-20 КБ (`assets/freeze/suite.v2.json`) | github.com/hyperion-cs/dpi-checkers | Apache-2.0 | Подобрать |
| OkHttp 4.12.0, Okio, Kotlin stdlib | github.com/square/okhttp | Apache-2.0 | прокси Telegram |

## Что важно при публикации APK

Android-сборка использует ByeDPI и HEV, перечисленные выше. Режим root/`nfqws2` и его GPL/netfilter-зависимости удалены из этого checkout; они не входят ни в исходный Android target, ни в APK.

Эта таблица не заменяет консультацию юриста.

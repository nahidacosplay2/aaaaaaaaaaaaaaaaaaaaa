(() => {
  'use strict';

  const STORAGE_KEY = 'arisu-link-settings-v1';
  const PROFILE_NAMES = {
    auto: 'Авто · баланс',
    general: 'Flowseal · General',
    quic: 'QUIC · UDP',
    custom: 'Точная настройка'
  };
  const PROFILE_SHORT_NAMES = {
    auto: 'Авто',
    general: 'General',
    quic: 'QUIC / UDP',
    custom: 'Custom'
  };
  const TRANSPORT_NAMES = {
    tun: 'Android VPN · TUN',
    socks: 'SOCKS5 · Xray / local',
    ws: 'WebSocket · relay'
  };
  const DNS_NAMES = {
    system: 'Системный',
    dot: 'DNS over TLS',
    custom: 'Пользовательский'
  };
  const defaults = {
    profile: 'auto',
    transport: 'tun',
    dns: 'system',
    customDns: '',
    socksHost: '127.0.0.1',
    socksPort: '2080',
    wsEndpoint: '',
    apps: { youtube: true, discord: true, telegram: false, steam: false },
    events: []
  };

  function loadSettings() {
    const freshDefaults = () => JSON.parse(JSON.stringify(defaults));
    try {
      const saved = JSON.parse(localStorage.getItem(STORAGE_KEY) || 'null');
      if (!saved || typeof saved !== 'object' || Array.isArray(saved)) return freshDefaults();
      const savedApps = saved.apps && typeof saved.apps === 'object' && !Array.isArray(saved.apps)
        ? saved.apps
        : {};
      const savedEvents = Array.isArray(saved.events)
        ? saved.events
          .filter((event) => event && typeof event === 'object' && !Array.isArray(event))
          .slice(0, 30)
          .map((event) => ({
            time: String(event.time ?? '').slice(0, 32),
            title: String(event.title ?? '').slice(0, 160),
            kind: String(event.kind ?? '').slice(0, 48)
          }))
        : [];
      const loaded = {
        ...freshDefaults(),
        ...saved,
        customDns: String(saved.customDns ?? defaults.customDns).slice(0, 253),
        socksHost: String(saved.socksHost ?? defaults.socksHost).slice(0, 253),
        socksPort: String(saved.socksPort ?? defaults.socksPort).slice(0, 5),
        wsEndpoint: String(saved.wsEndpoint ?? defaults.wsEndpoint).slice(0, 2048),
        apps: Object.fromEntries(Object.keys(defaults.apps).map((name) => [
          name,
          typeof savedApps[name] === 'boolean' ? savedApps[name] : defaults.apps[name]
        ])),
        events: savedEvents
      };
      if (!Object.prototype.hasOwnProperty.call(PROFILE_NAMES, loaded.profile)) loaded.profile = defaults.profile;
      if (!Object.prototype.hasOwnProperty.call(TRANSPORT_NAMES, loaded.transport)) loaded.transport = defaults.transport;
      if (!Object.prototype.hasOwnProperty.call(DNS_NAMES, loaded.dns)) loaded.dns = defaults.dns;
      return loaded;
    } catch (_error) {
      return freshDefaults();
    }
  }

  let settings = loadSettings();
  let toastTimer = 0;
  const toastEl = document.getElementById('toast');
  const eventList = document.getElementById('event-list');

  function persist() {
    try {
      localStorage.setItem(STORAGE_KEY, JSON.stringify(settings));
    } catch (_error) {
      showToast('Браузер не разрешил сохранить настройки локально.');
    }
  }

  function showToast(message) {
    if (!toastEl) return;
    toastEl.textContent = message;
    toastEl.classList.add('is-visible');
    window.clearTimeout(toastTimer);
    toastTimer = window.setTimeout(() => toastEl.classList.remove('is-visible'), 3200);
  }

  function addEvent(title, kind = 'LOCAL UI') {
    const event = {
      time: new Intl.DateTimeFormat('ru-RU', { hour: '2-digit', minute: '2-digit' }).format(new Date()),
      title,
      kind
    };
    settings.events.unshift(event);
    settings.events = settings.events.slice(0, 30);
    persist();
    renderEvents();
  }

  function renderEvents() {
    if (!eventList) return;
    if (!settings.events.length) {
      eventList.innerHTML = '<li class="event-empty">Здесь пока нет действий. Выбери профиль или сохрани маршрут.</li>';
      return;
    }
    eventList.innerHTML = settings.events.map((event) => {
      const safeTime = escapeHTML(event.time || '—');
      const safeTitle = escapeHTML(event.title || 'Действие интерфейса');
      const safeKind = escapeHTML(event.kind || 'LOCAL UI');
      return `<li class="event-row"><time class="event-time">${safeTime}</time><span class="event-mark" aria-hidden="true"></span><span class="event-title">${safeTitle}</span><span class="event-kind">${safeKind}</span></li>`;
    }).join('');
  }

  function escapeHTML(value) {
    return String(value).replace(/[&<>"']/g, (char) => ({
      '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;'
    })[char]);
  }

  function syncUI() {
    document.querySelectorAll('[data-profile]').forEach((button) => {
      const selected = button.dataset.profile === settings.profile;
      button.classList.toggle('is-selected', selected);
      button.setAttribute('aria-pressed', String(selected));
    });

    document.querySelectorAll('[data-transport]').forEach((button) => {
      const selected = button.dataset.transport === settings.transport;
      button.classList.toggle('is-selected', selected);
      button.setAttribute('aria-pressed', String(selected));
    });

    document.querySelectorAll('[data-transport-detail]').forEach((panel) => {
      panel.hidden = panel.dataset.transportDetail !== settings.transport;
    });

    document.querySelectorAll('[data-app]').forEach((button) => {
      const selected = Boolean(settings.apps[button.dataset.app]);
      button.classList.toggle('is-on', selected);
      button.setAttribute('aria-pressed', String(selected));
    });

    document.querySelectorAll('[data-profile-name]').forEach((element) => {
      element.textContent = PROFILE_NAMES[settings.profile] || PROFILE_NAMES.auto;
    });
    document.querySelectorAll('[data-profile-short]').forEach((element) => {
      element.textContent = PROFILE_SHORT_NAMES[settings.profile] || PROFILE_SHORT_NAMES.auto;
    });
    document.querySelectorAll('[data-transport-name]').forEach((element) => {
      element.textContent = TRANSPORT_NAMES[settings.transport] || TRANSPORT_NAMES.tun;
    });
    document.querySelectorAll('[data-dns-name]').forEach((element) => {
      element.textContent = DNS_NAMES[settings.dns] || DNS_NAMES.system;
    });

    const dnsSelect = document.getElementById('dns-mode');
    if (dnsSelect && document.activeElement !== dnsSelect) dnsSelect.value = settings.dns;
    const customDns = document.getElementById('custom-dns');
    if (customDns && document.activeElement !== customDns) customDns.value = settings.customDns;
    const socksHost = document.getElementById('socks-host');
    if (socksHost && document.activeElement !== socksHost) socksHost.value = settings.socksHost;
    const socksPort = document.getElementById('socks-port');
    if (socksPort && document.activeElement !== socksPort) socksPort.value = settings.socksPort;
    const wsEndpoint = document.getElementById('ws-endpoint');
    if (wsEndpoint && document.activeElement !== wsEndpoint) wsEndpoint.value = settings.wsEndpoint;

    const customDnsRow = document.getElementById('dns-custom-row');
    if (customDnsRow) customDnsRow.hidden = settings.dns !== 'custom';
    renderEvents();
  }

  function navigate(viewName) {
    const validViews = ['home', 'profiles', 'route', 'activity'];
    if (!validViews.includes(viewName)) return;
    document.querySelectorAll('[data-view]').forEach((view) => {
      const selected = view.dataset.view === viewName;
      view.hidden = !selected;
      view.classList.toggle('is-active', selected);
    });
    document.querySelectorAll('.nav-item[data-navigate]').forEach((button) => {
      const selected = button.dataset.navigate === viewName;
      button.classList.toggle('is-active', selected);
      if (selected) button.setAttribute('aria-current', 'page');
      else button.removeAttribute('aria-current');
    });
    if (window.location.hash !== `#${viewName}`) history.replaceState(null, '', `#${viewName}`);
    window.scrollTo({ top: 0, behavior: 'smooth' });
  }

  function readFields() {
    settings.dns = document.getElementById('dns-mode')?.value || settings.dns;
    settings.customDns = document.getElementById('custom-dns')?.value.trim() || '';
    settings.socksHost = document.getElementById('socks-host')?.value.trim() || '';
    settings.socksPort = document.getElementById('socks-port')?.value.trim() || '';
    settings.wsEndpoint = document.getElementById('ws-endpoint')?.value.trim() || '';
  }

  function validateRoute() {
    readFields();
    if (settings.transport === 'socks') {
      if (!settings.socksHost) {
        document.getElementById('socks-host')?.focus();
        showToast('Укажи хост SOCKS5, чтобы сохранить маршрут.');
        return false;
      }
      const port = Number(settings.socksPort);
      if (!Number.isInteger(port) || port < 1 || port > 65535) {
        document.getElementById('socks-port')?.focus();
        showToast('Порт должен быть числом от 1 до 65535.');
        return false;
      }
    }
    if (settings.transport === 'ws') {
      if (!settings.wsEndpoint) {
        document.getElementById('ws-endpoint')?.focus();
        showToast('Добавь адрес своего WebSocket-шлюза.');
        return false;
      }
      try {
        const endpoint = new URL(settings.wsEndpoint);
        if (endpoint.protocol !== 'wss:') throw new Error('Secure WebSocket is required');
      } catch (_error) {
        document.getElementById('ws-endpoint')?.focus();
        showToast('Проверь адрес шлюза: нужен корректный wss:// endpoint.');
        return false;
      }
    }
    if (settings.dns === 'custom' && !settings.customDns) {
      document.getElementById('custom-dns')?.focus();
      showToast('Добавь адрес пользовательского DNS.');
      return false;
    }
    return true;
  }

  function saveRoute() {
    if (!validateRoute()) return;
    persist();
    const transport = TRANSPORT_NAMES[settings.transport] || TRANSPORT_NAMES.tun;
    addEvent(`Сохранён маршрут: ${transport}`, 'CONFIG');
    syncUI();
    showToast('Настройки маршрута сохранены локально. Сетевое подключение не запускается.');
  }

  document.querySelectorAll('[data-navigate]').forEach((button) => {
    button.addEventListener('click', (event) => {
      event.preventDefault();
      navigate(button.dataset.navigate);
    });
  });

  document.querySelectorAll('[data-profile]').forEach((button) => {
    button.addEventListener('click', () => {
      const nextProfile = button.dataset.profile;
      if (!PROFILE_NAMES[nextProfile] || nextProfile === settings.profile) return;
      settings.profile = nextProfile;
      persist();
      syncUI();
      addEvent(`Выбран профиль: ${PROFILE_NAMES[nextProfile]}`, 'PROFILE');
      showToast(`Профиль «${PROFILE_NAMES[nextProfile]}» сохранён.`);
    });
  });

  document.querySelectorAll('[data-transport]').forEach((button) => {
    button.addEventListener('click', () => {
      const nextTransport = button.dataset.transport;
      if (!TRANSPORT_NAMES[nextTransport] || nextTransport === settings.transport) return;
      settings.transport = nextTransport;
      persist();
      syncUI();
      addEvent(`Выбран транспорт: ${TRANSPORT_NAMES[nextTransport]}`, 'ROUTE');
      showToast(`Выбран режим «${TRANSPORT_NAMES[nextTransport]}».`);
    });
  });

  document.querySelectorAll('[data-app]').forEach((button) => {
    button.addEventListener('click', () => {
      const appName = button.dataset.app;
      if (!(appName in settings.apps)) return;
      settings.apps[appName] = !settings.apps[appName];
      persist();
      syncUI();
    });
  });

  document.getElementById('dns-mode')?.addEventListener('change', () => {
    readFields();
    persist();
    syncUI();
  });

  ['custom-dns', 'socks-host', 'socks-port', 'ws-endpoint'].forEach((id) => {
    document.getElementById(id)?.addEventListener('input', () => {
      readFields();
      persist();
    });
  });

  document.getElementById('save-route')?.addEventListener('click', saveRoute);
  document.getElementById('clear-log')?.addEventListener('click', () => {
    settings.events = [];
    persist();
    renderEvents();
    showToast('Журнал интерфейса очищен.');
  });

  const initialView = window.location.hash.replace('#', '');
  navigate(['home', 'profiles', 'route', 'activity'].includes(initialView) ? initialView : 'home');
  syncUI();
})();

# Map Page

`/map` is an iframe embed of whichever live map plugin you run. Warden does not ship a map; it just gives you a page that frames yours inside the same chrome as the landing site.

---

## Supported plugins

Any web-served map works; Warden has been tested with:

- [Dynmap](https://www.dynmap.us/)
- [Pl3xMap](https://github.com/pl3xgaming/Pl3xMap)
- [BlueMap](https://bluemap.bluecolored.de/)
- [squaremap](https://github.com/jpenilla/squaremap)
- Anything else that serves a navigable web UI at a known URL

---

## Configuration

On `/dash/config` -> **Landing** tab -> **Map** section:

- **Map provider**: free-text label shown in the nav and the page header. E.g. "Dynmap"
- **Map URL**: the URL of your map's web UI, e.g. `http://mc.example.com:8123/`. Used as the iframe `src`.
- **Nav link label**: what the top-of-page nav shows for the map link

If the Map URL is blank, the `/map` route is disabled and the nav link is hidden.

---

## Cross-origin and HTTPS

Most map plugins serve plain HTTP from a non-standard port. If your landing page is served over HTTPS, browsers will block the mixed-content iframe. Options:

- Front your map plugin with the same reverse proxy that fronts Warden, so both are HTTPS on the same hostname
- Configure the map plugin to terminate TLS directly (Dynmap and BlueMap both support this)
- Open the map in a new tab instead of iframing it (edit `plugins/Warden/www/map.html` to replace the iframe with a link)

---

## Customising the template

`plugins/Warden/www/map.html` is unpacked on first run and never overwritten. Edit it freely to add your own intro text, navigation, or a fallback message when the map URL is unreachable.

Placeholders available: `{{server_name}}`, `{{map_provider}}`, `{{map_url}}`, `{{map_nav_label}}`. The Template fields reference panel on the Landing tab lists the full set.

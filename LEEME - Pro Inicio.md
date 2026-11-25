# Pro Inicio — guía del proyecto

Pantalla de inicio para la pantalla Android del coche (Junsun V9 del Nissan Qashqai J11), que también funciona en el móvil (Xiaomi Redmi 13 Pro 5G).

- **Repositorio:** https://github.com/franciscoserranoadt/pro-inicio
- **Descarga (siempre la última):** https://github.com/franciscoserranoadt/pro-inicio/releases/tag/ultima → `ProInicio.apk`
- **Versión:** se numera sola (1.0.1, 1.0.2…), igual que Pro Radio.

---

## 1. Qué hace la app

| Zona | Qué hay |
|---|---|
| **Arriba** | Hora grande, fecha, tiempo (icono, temperatura, ciudad, máxima/mínima). Botón ▦ (todas las apps) y ⚙ (ajustes). |
| **4 botones grandes** | Pro Maps (azul), Pro Radio (naranja), Pro OBD (verde), Pro Garaje (morado). Si una app no está instalada sale apagada: «Descargar» abre su página de GitHub; «Próximamente» si aún no existe. |
| **Lo que suena** | Carátula/logo, emisora y canción de Pro Radio, botones ⏮ ⏯ ⏭. Tocar el texto abre Pro Radio. Con Pro Radio cerrada, ▶ pone la última emisora. Si suena otra app de música y Pro Radio no, muestra esa. |
| **Cajón de apps (▦)** | Todas las apps instaladas con su icono y un buscador. |
| **Ajustes (⚙)** | Elegir como pantalla de inicio, permiso «Ver lo que suena», abrir al encender el coche, permiso de ubicación, buscar actualización y versión. |

En horizontal (Junsun): botones a la izquierda y reproductor a la derecha. En vertical (móvil): reproductor arriba y botones debajo.
El botón «atrás» no sale de la app (es la pantalla de inicio): solo cierra paneles.

### Cómo funciona por dentro
- **Lo que suena:** igual que el botón de música de Pro Maps. Android solo deja ver qué suena en otra app si se activa el permiso de «acceso a notificaciones» (el servicio `MediaListener` está vacío: no lee ninguna notificación). **No hace falta tocar Pro Radio.**
- **Tiempo:** Open-Meteo (gratis) con la ubicación; nombre del sitio con BigDataCloud. Se actualiza cada 15 minutos.
- **Paquetes de la gama Pro** (para saber si están instaladas y abrirlas):
  - Pro Maps → `com.fserrano.promaps`
  - Pro Radio → `es.proradio.app`
  - Pro OBD → `es.proobd.app` *(cuando se haga, tiene que usar este nombre)*
  - Pro Garaje → `es.progaraje.app` *(ídem)*

---

## 2. Actualizaciones automáticas (como Pro Maps y Pro Radio)
- Al abrir (y como mucho cada 6 horas) lee `releases/download/ultima/version.txt`. Si hay versión nueva sale **«Hay una versión nueva»** → Actualizar → Instalar.
- La primera vez pide **«Permitir de esta fuente»**.
- Botón manual: ⚙ Ajustes → 🔄 Buscar.

## 3. Cómo se publica una versión nueva
1. Cambiar el código y escribir las novedades en `CAMBIOS.txt`.
2. Commit y push a `main`.
3. GitHub Actions (`.github/workflows/build.yml`) compila con Gradle 8.9 + JDK 17, firma y publica la release **`ultima`** con `ProInicio.apk` y `version.txt`.
4. Versión automática: `versionCode` = número de ejecución, `versionName` = `1.0.<número>`.
5. Si GitHub falla al publicar, reintenta 5 veces; si aun así falla, otro push.

**Firma:** `app/pro-inicio.jks`, alias `proinicio`, contraseña `proinicio2026` (en `app/build.gradle`). **No cambiarla nunca** o las actualizaciones dejarán de instalarse encima. Guarda una copia del archivo `pro-inicio.jks` en tu Google Drive por si acaso.

## 4. Estructura del código
| Archivo | Qué contiene |
|---|---|
| `app/src/main/assets/index.html` | Estructura de la pantalla |
| `app/src/main/assets/style.css` | Diseño (horizontal Junsun y vertical móvil) |
| `app/src/main/assets/app.js` | Hora, tiempo, botones, reproductor, cajón de apps, ajustes, aviso de actualización |
| `MainActivity.java` | WebView a pantalla completa + puente `Android.*` (abrir apps, lista de apps, lo que suena, ubicación, peticiones web, actualizar) |
| `MediaListener.java` | Servicio vacío para el permiso «ver lo que suena» |
| `Updater.java` | Comprueba e instala versiones nuevas |
| `BootReceiver.java` | Abre Pro Inicio al encender el coche (si está activado) |
| `AndroidManifest.xml` | Permisos y registro como «pantalla de inicio» |
| `app/build.gradle` | minSdk 24, targetSdk 34, firma fija |

Java puro, sin librerías externas.

## 5. Historial
- **1.0.1** Primera versión.

## 6. Ideas pendientes (no pedidas aún)
Fondo de pantalla a elegir, modo noche, velocidad GPS en grande, accesos directos extra (teléfono, Bluetooth), widget de Pro OBD cuando exista.

# Pro Inicio — guía del proyecto

Pantalla de inicio para la pantalla Android del coche (Junsun V9 del Nissan Qashqai J11), que también funciona en el móvil (Xiaomi Redmi 13 Pro 5G).

- **Repositorio:** https://github.com/franciscoserranoadt/pro-inicio
- **Descarga (siempre la última):** https://github.com/franciscoserranoadt/pro-inicio/releases/tag/ultima → `ProInicio.apk`
- **Versión:** se numera sola (1.0.1, 1.0.2…), igual que Pro Radio.

---

## 1. Qué hace la app

Diseño tipo «pantalla de coche» (pedido el 27-nov a partir de una imagen estilo CarPlay), con las apps Pro. **Todo es configurable** (pedido el 27-nov), tiene **22 temas de colores** y **fondos de pantalla** (28-nov).

| Zona | Qué hay | Se puede cambiar |
|---|---|---|
| **Barra de arriba** | Hora, fecha, GPS, conexión (wifi / 4G / sin red), tiempo | Mostrar u ocultar cada cosa. Mantener pulsada abre Personalizar. |
| **Botones** (arriba, en la barra de estado; o a la izquierda) | De serie: Inicio · Navegar · Pro Radio · Teléfono · Todas las apps · Personalizar · Pro OBD · Mensajes | Cada botón (hasta 8): cualquier app, app Pro o botón especial; mover, quitar, ocultar la barra. En vertical va abajo. |
| **Mapa** | El navegador de verdad (Pro Maps encajada, u otro en ventana libre) o el mapa propio: mapa en directo (MapLibre + OpenFreeMap, mismos estilos que Pro Maps), edificios 3D, flecha con tu posición, tarjeta de la calle, velocidad, + − centrar | Navegador que se abre al tocarlo (Pro Maps de serie, o cualquier app: Waze, Google Maps…), estilo (Auto/Oscuro/Claro/Suave), 3D, cada tarjeta y botón, o quitar el mapa entero. |
| **2 tarjetas** | De serie: Música (Pro Radio) arriba y Apps abajo | Cada una puede ser Música, Apps, **Widget**, Tiempo (ahora + 5 horas), Reloj, Velocidad o Nada; a la derecha o a la izquierda. |
| **Cuadro de apps** | Pro Maps, Pro Radio, Pro OBD, Pro Garaje, favoritas, Más apps, Añadir | Añadir, cambiar, mover y quitar cualquier botón. |
| **Fondo** | Sin fondo (de serie) | 8 dibujos propios o una foto de la galería; oscurecer; tarjetas transparentes. |

**Botones especiales:** Inicio, Navegar (abre el navegador elegido), Todas las apps, Añadir, Personalizar, Teléfono, Mensajes, Vacío.
**Mantener pulsado** cualquier botón, tarjeta, widget o el mapa abre su menú (cambiar, mover, quitar, personalizar).
**Temas:** Medianoche (de serie), Carbono, Grafito, Océano, Hielo, Turquesa, Esmeralda, Bosque, Oro, Cobre, Atardecer, Lava, Rubí, Nissan, Rosa, Violeta, Neón, Retro 80, y claros: Ártico, Arena, Menta, Lavanda. Están en `THEMES` (app.js); cada tema genera las variables CSS.
La configuración se guarda en el aparato (`localStorage`, clave `pi.cfg`); «Restablecer» vuelve al diseño original manteniendo el tema.

### El navegador de verdad en la tarjeta del mapa (27-nov, v1.0.6)
Francisco quiere manejar el navegador (buscar destino, gasolineras…) desde la propia tarjeta. Android no deja meter la pantalla de otra app dentro de una app normal, así que se hizo de dos formas (eligió «probar las dos»):
- **Pro Maps (de serie):** Pro Maps 3.11 tiene un «modo tarjeta». Pro Inicio le manda por un aviso (`com.fserrano.promaps.CARD`, receptor `CardReceiver`) la posición y el tamaño de la tarjeta en píxeles; Pro Maps saca su pantalla (el mismo WebView, con todo funcionando) a una ventana flotante encima de la tarjeta. Al salir de Pro Inicio, abrir un panel o abrir Pro Maps a pantalla completa, la ventana se quita y Pro Maps recupera su pantalla. Pro Maps contesta con `es.proinicio.app.CARD_STATUS` (ok / noperm / error). Necesita el permiso **«Mostrar sobre otras apps»** de Pro Maps (Pro Inicio muestra el botón «Dar permiso»).
- **Resultado (27-nov): se queda Pro Maps.** Pro Maps en la tarjeta funciona en el móvil y en la Junsun. Waze y Google Maps en ventana libre **no funcionan** en ninguno de los dos (la Junsun es Android 9 y esconde el ajuste; el Xiaomi con HyperOS no tiene «Habilitar ventanas de formato libre»). Francisco decidió dejarlo así; el código de la ventana libre se queda por si algún día cambia de pantalla.
- **Otro navegador (Waze, Google Maps…):** se abre en una **ventana libre** (freeform) con la posición y el tamaño de la tarjeta (`ActivityOptions.setLaunchBounds`). Solo funciona si la pantalla tiene activado «Habilitar ventanas de formato libre» en Opciones de desarrollador (Pro Inicio explica los pasos). **Es una prueba:** puede que la Junsun no lo permita.
- Opción «Mapa de Pro Inicio»: el mapa propio de antes (MapLibre) que abre el navegador al tocarlo.

### Tarjeta Widget, música de otras apps y botón Teléfono (27-nov, v1.0.15)
- **Widget:** cualquier tarjeta puede ser «Widget». Android dibuja el widget de verdad (`AppWidgetHost`, id 0x5049) en un `WidgetFrame` nativo colocado encima del hueco de la tarjeta (`showWidget`, con las coordenadas que manda `syncWidgets()` cada 700 ms y al abrir o cerrar paneles: con un panel, menú o aviso de rutina abierto el widget se esconde). Elegir: `widgetList` → lista agrupada por app con su muestra; `widgetAdd` → la primera vez Android pregunta «Permitir siempre / Crear»; si el widget tiene ajustes propios, se abren. Mantener pulsado el widget → menú (cambiar, quitar, cambiar la tarjeta). El id de cada tarjeta se guarda en el aparato (`wg_top` / `wg_bottom` y `cfg.widgets`); **no se sincroniza** con el Código Pro.
- **Música de otras apps:** cada 3 s mira si suena algo que no es Pro Radio (`AudioManager.isMusicActive`). Si sí, la tarjeta Música pone «Música de otra app» y ⏮ ⏯ ⏭ mandan teclas multimedia (`dispatchMediaKeyEvent`) a esa app (Deezer, YouTube…). Tocar la parte de arriba vuelve a Pro Radio (`playRadio`). No puede mostrar el título de otras apps (haría falta el permiso prohibido).
- **Teléfono:** antes pedía un marcador genérico (`ACTION_DIAL`) y en la Junsun no pasaba nada. Ahora prueba: 1) la app elegida (`phonePkg`); 2) la app cuyo nombre sea «Teléfono», «Bluetooth», «Phone»… (sin contar Android Auto). Si no hay ninguna, pide elegirla una vez. Se cambia en Personalizar → Sistema → «App del teléfono» o manteniendo pulsado el botón. **1.0.17:** se quitó el marcador genérico (`ACTION_DIAL`) como último recurso, porque en la Junsun abría los ajustes de Android Auto. En la Junsun las llamadas van por el Bluetooth del móvil, con la app de teléfono Bluetooth de la propia pantalla.

### Mensajes rápidos y rutinas al arrancar (27-nov, v1.0.18)
- **Mensajes rápidos (WhatsApp):** botón especial «💬 Mensajes» (se añadió solo una vez a la barra; `cfg.msgBtn`). Panel con contactos («Para») y mensajes; al tocar un mensaje se abre WhatsApp con `https://api.whatsapp.com/send?phone=…&text=…` (paquete `com.whatsapp` o `com.whatsapp.w4b`) y queda tocar Enviar. Los números españoles de 9 cifras llevan el 34 delante solos. `{ubicacion}` en un mensaje se cambia por un enlace de Google Maps con la posición. Contactos y textos en `cfg.quick`, **sincronizados con el Código Pro** (se escriben en el móvil). En la Junsun WhatsApp está como **dispositivo vinculado** (hecho el 27-nov).
- **Rutinas al arrancar:** Personalizar → Rutinas al arrancar. Cada rutina: nombre, días, horario (o todo el día; si «desde» es mayor que «hasta», cruza la medianoche), una acción de música (Pro Radio última emisora · una memoria de Pro Radio por su `mediaId` · seguir con la última música de otra app) y una de abrir (una app · ir a un sitio). Los sitios se buscan con **Photon** (gratis, OpenStreetMap) o «donde estoy ahora», y se abren en el navegador elegido con `geo:lat,lon?q=lat,lon(nombre)`. Pro Maps ya lo acepta, así que no hizo falta tocar Pro Maps; puede que haya que tocar «Ir». Se hace la primera rutina que coincida, con un aviso de 5 s (Cancelar / Ya) y como mucho una vez cada 10 min. Rutinas en `cfg.routines`, **de cada pantalla** (no se sincronizan).
- **Cuándo cuenta como arranque:** `BootReceiver` abre Pro Inicio con `pi_boot`; o Pro Inicio se crea en los 3 primeros minutos tras encender; o la pantalla se enciende después de **10 min o más apagada** (la Junsun a veces se duerme en vez de apagarse). `startEvent()` evita avisar dos veces en 2 min.

### Fondos de pantalla (28-nov, v1.0.19)
Personalizar → **Fondo de pantalla**. Dibujos propios en `assets/fondos/*.svg` (Carretera de noche, Ciudad de noche, Amanecer, Aurora, Ondas, Rojo y negro, Fibra de carbono, Todocamino blanco) y **«Tu foto»**: `pickBackground` abre la galería (`ACTION_GET_CONTENT`), la foto se reduce a 1920 px, se gira según EXIF y se guarda en la carpeta privada (`files/fondo.jpg`); la página la carga con `bgPath`. Opciones: **Oscurecer el fondo** (Nada / Poco / Medio / Mucho → `cfg.bgDim`) y **Tarjetas transparentes** (`cfg.glass`: tarjetas y barra con el color del tema semitransparente y desenfoque). El fondo y la foto **no se sincronizan** con el Código Pro (cada pantalla el suyo).
- **Decisión 28-nov:** Francisco pidió un fondo con el logo de Nissan o un Qashqai J11 blanco. No se dibujan logos ni modelos reales de marcas; en su lugar, «Tu foto» para poner una foto de su propio Qashqai, más el tema de colores «Nissan» (rojo y negro) y un todocamino blanco genérico.
- Con el mapa grande, el fondo se ve sobre todo en la barra de arriba, entre tarjetas y a través de las tarjetas transparentes.

### Código Pro: móvil ⇄ coche (27-nov)
Personalizar → **Móvil y coche** → Código Pro. Un solo código para todas las apps Pro (ver `codigo-pro/LEEME - Código Pro.md` en el proyecto). Pro Inicio sincroniza el **tema, los botones de apps (barra y cuadro), el navegador elegido y los mensajes rápidos** (canal `inicio`, gana el último cambio). La colocación del mapa y las tarjetas, los widgets, las rutinas y el fondo quedan distintos en cada pantalla (decisión de Francisco). Archivos: `ProSync.java`, `SyncReceiver.java`, `assets/prosync.js` (iguales en las 5 apps).
- **27-nov: salió «Demasiados envíos hoy»** (HTTP 429 de ntfy.sh: límite del plan gratis por conexión, unos 250 mensajes al día, y de envíos seguidos). Pasó tras un día de muchas pruebas con las 5 apps; la app espera 1 h y reintenta, y no se pierde nada. **Si sigue saliendo con uso normal:** arreglo rápido en `prosync.js` de las 5 apps (no reenviar al abrir si no ha cambiado nada y preguntar menos), o cambiar ntfy.sh por un servidor gratis propio (por ejemplo, Cloudflare).

El botón «atrás» no sale de la app (es la pantalla de inicio): solo cierra paneles.

### Cómo funciona por dentro
- **Lo que suena:** Pro Inicio se conecta directamente al reproductor de Pro Radio (`es.proradio.app.PlaybackService`, MediaSession de Media3), igual que los mandos del volante. No necesita ningún permiso especial ni hay que tocar Pro Radio. Las memorias de Pro Radio son su lista de reproducción (`radioStations`).
- **Tiempo:** Open-Meteo (gratis) con la ubicación; nombre del sitio con BigDataCloud. Se actualiza cada 15 minutos.
- **GPS:** cada segundo mientras Pro Inicio está delante (para el mapa y la velocidad); se para al abrir otra app.
- **Mapa:** necesita internet (MapLibre desde unpkg/jsdelivr y teselas de OpenFreeMap). Sin internet sale «Sin mapa» y lo vuelve a intentar al volver la conexión.
- **Configuración:** botones, tarjetas, tema, fondo, navegador, mensajes y rutinas en `localStorage` (`pi.cfg`), con el icono de cada app elegida.
- **Paquetes de la gama Pro** (para saber si están instaladas y abrirlas):
  - Pro Maps → `com.fserrano.promaps`
  - Pro Radio → `es.proradio.app`
  - Pro OBD → `es.proobd.app` (repo franciscoserranoadt/pro-obd)
  - Pro Garaje → `es.progaraje.app` (repo franciscoserranoadt/pro-garaje)

---

## 2. Actualizaciones automáticas (como Pro Maps y Pro Radio)
- Al abrir (y como mucho cada 6 horas) lee `releases/download/ultima/version.txt`. Si hay versión nueva sale **«Hay una versión nueva»** → Actualizar → Instalar.
- La primera vez pide **«Permitir de esta fuente»**.
- Botón manual: ⚙ Ajustes → 🔄 Buscar.

## 3. Cómo se publica una versión nueva
1. Cambiar el código y escribir las novedades en `CAMBIOS.txt`.
2. Commit y push a `main`. Para cambios que solo tocan la guía, poner `[skip ci]` en el mensaje para no compilar.
3. GitHub Actions (`.github/workflows/build.yml`) compila con Gradle 8.9 + JDK 17, firma y publica la release **`ultima`** con `ProInicio.apk` y `version.txt`.
4. Versión automática: `versionCode` = número de ejecución, `versionName` = `1.0.<número>` (por eso a veces se salta números).
5. Si GitHub falla al publicar, reintenta 5 veces; si aun así falla, otro push.

**Firma:** `app/pro-inicio.jks`, alias `proinicio`, contraseña `proinicio2026` (en `app/build.gradle`). **No cambiarla nunca** o las actualizaciones dejarán de instalarse encima. Guarda una copia del archivo `pro-inicio.jks` en tu Google Drive por si acaso.

## 4. Estructura del código
| Archivo | Qué contiene |
|---|---|
| `app/src/main/assets/index.html` | Estructura de la pantalla y paneles (Mensajes, Rutina, ¿A dónde?, formulario, aviso de rutina) |
| `app/src/main/assets/style.css` | Diseño (horizontal Junsun y vertical móvil), fondo y tarjetas transparentes |
| `app/src/main/assets/app.js` | Hora, tiempo, botones, reproductor, cajón de apps, widgets, mensajes, rutinas, temas, fondos, ajustes, aviso de actualización |
| `app/src/main/assets/fondos/*.svg` | Los 8 fondos dibujados |
| `MainActivity.java` | WebView a pantalla completa (dentro de un `FrameLayout` donde van los widgets) + puente `Android.*` (abrir apps, lista de apps, widgets, lo que suena, memorias de Pro Radio, teclas multimedia, teléfono, WhatsApp, navegar a un sitio, foto de fondo, ubicación, peticiones web, actualizar) + detección de arranque |
| `Updater.java` | Comprueba e instala versiones nuevas |
| `BootReceiver.java` | Abre Pro Inicio al encender el coche (si está activado), con la marca `pi_boot` para las rutinas |
| `AndroidManifest.xml` | Permisos y registro como «pantalla de inicio» |
| `app/build.gradle` | minSdk 24, targetSdk 34, firma fija |

Java, con una sola librería: `androidx.media3:media3-session` (para hablar con Pro Radio).

## 5. Historial
- **1.0.19** Fondos de pantalla (8 dibujos propios + tu foto), oscurecer el fondo y tarjetas transparentes. Publicada el 28-nov.
- **1.0.18** Mensajes rápidos por WhatsApp y rutinas al arrancar. Publicada el 27-nov; falta probarla en la Junsun.
- **1.0.17** Botón Teléfono: ya no abre los ajustes de Android Auto (sin marcador genérico; Android Auto excluido de la búsqueda). Si no encuentra la app de llamadas Bluetooth, pregunta cuál es.
- **1.0.15** Tarjeta Widget, botones universales de música (Deezer, YouTube…) y botón Teléfono arreglado. Publicada el 27-nov; falta probarla en la Junsun.
- **1.0.14** Código Pro: tema, botones de apps y navegador iguales en el móvil y el coche, cifrado.
- **1.0.13** Botones de la barra lateral arriba, en la barra de estado (pedido el 27-nov para que el mapa ocupe más). Ajuste «Posición de los botones»: Arriba (de serie) / Izquierda. En vertical van abajo. La fecha se oculta en horizontal con los botones arriba. Mantener pulsada la hora abre Personalizar.
- **1.0.12** Pro Maps 3.13 confirma «shown» al colocarse; Pro Inicio reintenta 2 veces si no llega en 6 s y luego ofrece «Reintentar». Confirmado el 27-nov: Pro Maps en la tarjeta funciona en el móvil y en la Junsun.
- **1.0.11** Pro Maps elegida desde «Todas las apps» ahora también va en modo tarjeta (antes iba por ventana libre).
- **1.0.10** Abre las opciones de desarrollador por varios caminos (Junsun las esconde) y muestra el estado de las ventanas libres. La Junsun V9 es Android 9 por dentro (compilación PKQ1, msm8953) aunque diga 13.
- **1.0.9** (la 1.0.8 no compiló) Ventana libre con HiddenApiBypass 4.3 (setLaunchWindowingMode es una función oculta de Android) y aviso si se abre a pantalla completa.
- **1.0.7** Arreglado «Cambiar» en Personalizar (la lista de apps quedaba detrás).
- **1.0.6** Navegador de verdad en la tarjeta del mapa (Pro Maps en modo tarjeta; otros en ventana libre, de prueba).
- **1.0.5** Todo configurable (barra lateral, tarjetas, cuadro de apps, mapa, barra de arriba), 22 temas, navegador a elegir, tarjetas Tiempo/Reloj/Velocidad.
- **1.0.4** Diseño nuevo tipo pantalla de coche (mapa en directo, barra lateral, música y cuadro de apps con favoritas).
- **1.0.3** Botones «Descargar» también para Pro OBD y Pro Garaje (ya existen). Primera prueba en la Junsun (25-nov): se ve bien a 1280×720.
- **1.0.2** Quitado el permiso de «acceso a notificaciones»: Google Play Protect bloqueaba la instalación («Aplicación bloqueada para proteger tu dispositivo»). Ahora lee lo que suena directamente de Pro Radio.
- **1.0.1** Primera versión.

## Decisiones tomadas
- **No usar nunca el permiso de «acceso a notificaciones»** (NotificationListenerService) ni otros permisos «sensibles» (accesibilidad, SMS): Play Protect bloquea la instalación de apps que no vienen de Google Play y los piden.
- El reproductor solo muestra los títulos de Pro Radio; de otras apps (Deezer, YouTube) solo se controla con ⏮ ⏯ ⏭ o con su widget, a cambio de no necesitar ese permiso.
- **27-nov: no repetir en Pro Inicio lo que ya hacen las otras apps Pro** (modo noche, gasolineras, datos de OBD, avisos de Garaje, destinos de Pro Maps). Francisco ya lo tiene en cada app.
- **27-nov: mensajes rápidos sin envío automático.** WhatsApp no deja que otra app envíe sola; haría falta el permiso de accesibilidad (prohibido, ver arriba). Siempre queda un toque final en «Enviar».
- **27-nov: los widgets y las rutinas son de cada pantalla** (no se copian con el Código Pro); los mensajes rápidos sí se copian.
- **27-nov: el botón Teléfono nunca abre el marcador genérico de Android** (en la Junsun es Android Auto); las llamadas van por la app de teléfono Bluetooth de la pantalla.
- **27-nov: las rutinas siempre avisan 5 s antes** (para poder cancelarlas) y abren el destino en el navegador con `geo:` (sin cambiar Pro Maps).
- **28-nov: sin logos ni modelos reales de marcas en los fondos** (ni el de Nissan ni un Qashqai dibujado); para su coche, «Tu foto».

## 6. Próxima versión
Nada pedido. Pendiente: que Francisco pruebe en la Junsun la 1.0.15, la 1.0.17, la 1.0.18 y la 1.0.19 (widget de Deezer, botón Teléfono, mensajes, rutinas y fondos) y mande capturas.

## 7. Ideas pendientes (no pedidas aún)
Dónde he aparcado (Junsun → móvil por Código Pro), órdenes por voz, contactos de un toque, diseños guardados, pantalla de reposo, tarjeta «Mensajes» (contactos y mensajes en una tarjeta en vez de un botón).

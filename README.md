# SL Financiera Android

APK Android contenedora del Home Banking Flask `mi_tarjeta_web.py`.

## 1. Configurar URL
Editar `gradle.properties`:

```properties
SL_HOME_URL=https://app.slfinanciera.com.ar/mi-tarjeta
```

Debe ser HTTPS y apuntar al servidor donde corre `mi_tarjeta_web.py`.

## 2. Compilar con GitHub Actions
1. Crear repositorio nuevo en GitHub.
2. Subir TODO el contenido de esta carpeta, incluyendo `.github`.
3. Abrir la pestaña **Actions**.
4. Abrir **Compilar APK SL Financiera**.
5. Pulsar **Run workflow**.
6. Al finalizar, abrir la ejecución.
7. En **Artifacts**, descargar `SL-Financiera-APK`.
8. Descomprimir el ZIP y obtener `SL-Financiera.apk`.

## Seguridad aplicada
- Sólo HTTPS.
- Certificados SSL inválidos se rechazan.
- Navegación interna limitada al host configurado.
- Cookies/sesión persistentes para Flask.
- JavaScript y almacenamiento DOM habilitados.
- Cámara disponible sólo para el dominio configurado y con permiso Android.
- Descargas autenticadas reutilizan la cookie de sesión.
- Backup de la app deshabilitado.

## Nota
La app no incluye la base de datos ni ejecuta Flask dentro del teléfono. El servidor Flask debe estar publicado y accesible desde Internet.

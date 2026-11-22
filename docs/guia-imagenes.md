# Cómo agregar imágenes a NexFit

Esta guía es para el equipo. Hoy se pueden mostrar imágenes de **equipos** y **ejercicios**; para el resto
(entrenadores, sedes, gimnasios) la base de datos ya tiene o necesita una columna, pero la pantalla aún no la usa
(ver el final).

## Cómo funciona

- Cada equipo y cada ejercicio guarda **una dirección** en la columna `image_url` (texto, máximo 500 caracteres).
  La imagen en sí **no** se guarda en la base de datos.
- La dirección debe empezar por `https://` (una imagen alojada en otro sitio) o por `/` (un archivo del propio
  proyecto). Cualquier otra cosa la rechaza la API.
- Si no hay dirección, la pantalla muestra una ilustración por categoría o grupo muscular
  (`frontend/public/img/equipos/` y `frontend/public/img/ejercicios/`). No hace falta hacer nada para que se vea bien.

## Opción A: foto como archivo del proyecto (recomendada para la demostración)

1. Consigue la foto. Usa **fotos propias** o de un banco con licencia libre (por ejemplo Pexels o Unsplash) y anota la
   fuente. No uses imágenes de Google sin licencia.
2. Prepárala: formato **JPG o WebP**, proporción **4:3** (por ejemplo 800×600), **menos de 300 KB**.
3. Nómbrala en minúsculas, sin tildes ni espacios: `prensa-piernas.jpg`.
4. Cópiala en el proyecto:
   - equipos: `frontend/public/img/equipos/prensa-piernas.jpg`
   - ejercicios: `frontend/public/img/ejercicios/sentadilla.jpg`
5. En la aplicación, entra como **Administrador** (solo él puede editar equipos y ejercicios del gimnasio; el Entrenador puede crear ejercicios, pero no editarlos),
   edita el registro y en el campo **Imagen** escribe `/img/equipos/prensa-piernas.jpg`. Debajo aparece una vista
   previa: si la ves, quedó bien. Guarda.
6. Sube la foto con un PR como cualquier otro cambio (el archivo viaja en el repositorio y Vercel lo publica).

> Importante: la dirección se escribe **con la barra inicial** (`/img/...`), sin `public`. Si el archivo no está en el
> repositorio, la imagen se verá solo en tu computador.

## Opción B: foto alojada en otro sitio

1. Sube la imagen a un servicio de imágenes (por ejemplo Cloudinary, o un repositorio público de GitHub).
2. Copia la dirección directa a la imagen (termina en `.jpg`, `.png` o `.webp`) y pégala en el campo **Imagen**
   empezando por `https://`.
3. Mira la vista previa antes de guardar.

Ventaja: no pesa en el repositorio. Desventaja: si el sitio borra o cambia la imagen, deja de verse.

## Para los datos de demostración

El conjunto `demo` (`NEXFIT_SEED_DATASET=demo`) asigna a cada equipo la ilustración de su categoría. Para que use fotos
reales de la Opción A, cambia el método `equipmentImage` de
`backend/src/main/java/com/nexfit/bootstrap/seed/DemoSeedService.java` o edita cada equipo desde la pantalla.

## Qué falta (y qué tocaría hacer)

| Imagen | Estado | Qué hay que hacer |
|---|---|---|
| Equipos | Listo: campo en la pantalla y en la API | Solo cargar fotos |
| Ejercicios | Listo: campo en la pantalla y en la API | Solo cargar fotos |
| Sedes y gimnasios | Existe la columna `image_url` (migración V8) | Agregar el campo a la API y a la pantalla |
| Entrenadores y clientes (foto de perfil) | No existe columna | Migración nueva con `photo_url`, campo en la API de perfil, avatar en la pantalla |
| Botón "subir archivo" desde la pantalla | No existe | Guardar los archivos en un almacenamiento (por ejemplo Azure Blob), endpoint de subida con validación de tipo y tamaño, y guardar la dirección resultante |

La subida directa de archivos es el cambio más grande: necesita almacenamiento, controles de seguridad (tipo, tamaño,
permisos por gimnasio) y pruebas. Conviene decidirla con el docente antes de construirla.

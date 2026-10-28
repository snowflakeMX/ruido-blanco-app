# Ruido blanco

App Android de uso personal que reproduce ruido de enmascaramiento cuando detecta ruidos de los vecinos durante la noche. Todo el audio se procesa en el teléfono: no se graba ni se envía nada (la app no tiene permiso de internet).

El PRD vigente está en el proyecto ("PRD v2").

## Estructura

- `core/`: lógica pura en Kotlin (nivel de audio, línea base, máquina de estados de la noche, generador de ruido, registro). Se prueba sin el SDK de Android: `./gradlew -p core test`.
- `app/`: app Android (Jetpack Compose). Servicio en primer plano que escucha el micrófono y reproduce el ruido.

## Cómo funciona una noche

1. "Iniciar noche" mide 10 s de fondo y reproduce 3 s de ruido para aprender cuánto lo oye el propio micrófono.
2. Escucha en ventanas de 100 ms. Si el nivel supera el esperado (fondo + ruido propio) por el umbral durante 200 ms, sube el ruido.
3. Sigue sonando el tiempo de permanencia después del último ruido y luego baja.
4. A la hora de despertar termina sola. 15 min antes ya no empieza a sonar.

## Ruido constante al empezar

En el inicio se elige si la noche empieza con ruido constante (1 a 3 h). Ese tiempo suena sin parar y sin escuchar; después baja, mide el silencio y sigue sola con la detección. Desde la pantalla de noche se puede pasar a escuchar antes. Sirve para dormir a la nena y salir del cuarto sin tener que usar otra app.

El aprendizaje de horarios y el reconocimiento de sonidos se quitaron después de 14 noches de uso: las noches son muy distintas entre sí y encendían el ruido sin necesidad. En el historial quedan las tendencias semana a semana.

## Sonidos y atajo

Blanco, rosa, marrón, lluvia, ventilador y olas se generan en el teléfono. También se puede elegir un archivo propio, que se copia dentro de la app y se repite sin cortes. Hay un atajo en los ajustes rápidos de Android para iniciar o terminar la noche.

## Probar los ajustes

En Ajustes, "Probar sonido" reproduce 6 s con el tipo y volumen elegidos, y "Probar detección" escucha como de noche sin guardar nada: muestra el nivel del micrófono, la línea de disparo y los ruidos detectados. Los cambios se aplican al instante.

## Compilar

GitHub Actions compila en cada cambio: corre las pruebas de `core`, las pruebas de pantallas (Robolectric) y lint, y publica el APK en el release `ultima`. Las capturas de cada pantalla y los informes (permisos del manifiesto final, lint, pruebas) quedan en la rama `capturas`.

El APK se firma siempre con la misma clave (`app/signing/`), así cada versión se instala encima de la anterior sin perder datos.

## Seguridad

- Sin permiso de internet: nada sale del teléfono. Sin copias en la nube ni transferencia entre dispositivos.
- Sin librerías extra: solo AndroidX y Jetpack Compose.
- Permisos: micrófono, notificaciones, servicio en primer plano de micrófono, mantener la CPU despierta y pedir excepción de batería. Nada más.
- El servicio no se exporta. El atajo de ajustes rápidos solo lo puede usar el sistema, y la entrada que inicia la noche desde el atajo no es accesible para otras apps. Solo la pantalla de noche se muestra sobre la pantalla bloqueada; el resto pide desbloquear.
- Las acciones de GitHub están fijadas por hash y el release solo se publica desde este repositorio.

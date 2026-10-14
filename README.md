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

## Compilar

GitHub Actions compila el APK en cada cambio (artefacto `ruido-blanco-apk`). Se firma siempre con la misma clave (`app/signing/`), así cada versión se instala encima de la anterior sin perder datos.

# Reporte de Laboratorio 2 — Programación concurrente (ARSW)

Escuela Colombiana de Ingeniería Julio Garavito — Arquitecturas de Software

---

## Parte I — Calentamiento: wait/notify (PrimeFinder)

El código de `PrimeFinder.java` (entregado en el repositorio de la Parte I,
paquete `edu.eci.arsw.primefinder`) implementa `N` hilos trabajadores que
buscan números primos indefinidamente sobre un contador compartido
(`AtomicInteger nextCandidate`), y un hilo controlador (el hilo `main`) que,
cada `t` milisegundos:

1. Pausa a todos los trabajadores.
2. Espera (bloqueado, sin sondeo) a que **todos** confirmen estar detenidos.
3. Imprime cuántos primos se han encontrado hasta ese instante.
4. Bloquea esperando `ENTER` por consola.
5. Reanuda a todos los trabajadores.

### Diseño de sincronización

- **Un único monitor**: la instancia de la clase interna `PauseGate`
  (concretamente su campo `lock`). Todo el estado compartido relacionado con
  pausa/reanudación (`paused`, `parked`, `totalWorkers`) se protege con ese
  mismo lock — un solo candado para todo el estado relacionado, evitando la
  necesidad de sincronizar entre varios locks distintos.
- **Condición de espera de los trabajadores**: `while (paused) lock.wait();`
  dentro de `checkpoint()`. Cada trabajador la evalúa una vez por iteración
  de su bucle.
- **Condición de espera del controlador**: `while (parked < totalWorkers)
  lock.wait();` dentro de `pauseAndAwaitAllParked()`.
- **Cómo se evitan los "lost wakeups"**: tanto la comprobación de la
  condición como la llamada a `wait()` ocurren **dentro del mismo bloque
  `synchronized`**, sobre el mismo lock que usa quien hace `notifyAll()`. Si
  un hilo pudiera comprobar la condición y luego bloquear en `wait()` en dos
  pasos no atómicos, un `notifyAll()` emitido justo en medio se perdería
  (el hilo ya habría decidido esperar, pero nadie lo despertaría después).
  Al mantener comprobación + `wait()` atómicas respecto al lock, esto es
  imposible: mientras un hilo tiene el lock (para comprobar/decidir
  esperar), ningún otro hilo puede tener el lock para notificar.
- **`while` en vez de `if`**: protege contra *spurious wakeups* (permitidos
  por el JLS) y contra que `notifyAll()` despierte a un hilo cuya condición
  real todavía no se cumple (por ejemplo, el controlador puede despertar por
  el `notifyAll()` de un trabajador que se acaba de "parquear" sin que
  todavía se hayan parqueado los demás).
- **Sin espera activa**: en ningún punto se hace *polling* de una bandera en
  un bucle con `Thread.sleep` corto; todo bloqueo ocurre vía `wait()`.

---

## Parte II — SnakeRace concurrente

### 1) Cómo el código usa hilos para dar autonomía a cada serpiente

Cada serpiente corre en su propio hilo **virtual** (`Executors.newVirtualThreadPerTaskExecutor()`,
`SnakeApp` línea de arranque), ejecutando una instancia de `SnakeRunner`. Cada
`SnakeRunner` tiene su propio bucle independiente: decide si gira
aleatoriamente, invoca `board.step(...)` para avanzar, y duerme un tiempo
(`Thread.sleep`) proporcional a si está en modo turbo. Al usar hilos
virtuales, N puede ser grande (decenas o cientos) sin agotar hilos del
sistema operativo.

### 2) Data races encontradas y su solución

| # | Problema | Dónde | Solución |
|---|----------|-------|----------|
| 1 | `Snake.body` (`ArrayDeque`) se **escribía** desde el hilo de la serpiente (`advance()`, llamado dentro de `Board.step`) y se **leía** desde el hilo de Swing (EDT) en `paintComponent` (`snapshot()`, `head()`), sin ningún candado. `ArrayDeque` no es thread-safe: esto puede producir `ConcurrentModificationException`, lecturas corruptas, o publicación insegura de referencias. | `Snake.java` | Se sincronizan `advance()`, `snapshot()`, `head()`, `length()` y el nuevo `occupies()` sobre el monitor intrínseco de la propia instancia `Snake`. Región mínima: solo tocan el `Deque` y el contador `maxLength`. |
| 2 | El botón **Pausar** (antes "Action") solo llamaba a `clock.pause()`, que detiene el *ticker* de repintado (`GameClock`). Cada `SnakeRunner` seguía su propio bucle con `Thread.sleep()` **ajeno por completo** al estado de pausa: las serpientes seguían moviéndose con el juego "pausado". No es una condición de carrera en el sentido clásico, pero sí es un defecto de coordinación/consistencia entre hilos: el estado visible (tablero pausado) no correspondía al estado real (serpientes en movimiento). | `SnakeRunner.java`, `SnakeApp.java` | Se introdujo `PauseController` (ver punto 3). Cada `SnakeRunner` llama a `pauseController.awaitIfPaused()` una vez por iteración, bloqueando de verdad el avance mientras el juego está pausado. |
| 3 | Al pausar y querer mostrar estadísticas ("serpiente más larga viva", "primera en morir"), leer el estado de las serpientes justo después de pedir la pausa podría capturar una **foto a medias** (*tearing*): algunas serpientes ya detenidas, otras todavía terminando su `step()` actual, porque la suspensión de un hilo nunca es instantánea. | `SnakeApp.togglePause()` | `PauseController.awaitAllParked(timeoutMs)` bloquea al llamante hasta que **todos** los `SnakeRunner` registrados confirman (vía `wait/notify`, no sondeo) que están parqueados, antes de calcular las estadísticas. Si se agota el timeout (posible bajo carga extrema), se muestran igual las estadísticas con una advertencia explícita en vez de bloquear indefinidamente o fallar. |

### 3) Colecciones o estructuras no seguras en contexto concurrente

- `Snake.body` (`ArrayDeque<Position>`) — descrito arriba; corregido con
  sincronización sobre el monitor de `Snake`.
- `Board.mice/obstacles/turbo` (`HashSet`) y `Board.teleports` (`HashMap`) —
  **ya estaban** protegidas correctamente en el starter: todos los métodos
  que las tocan (`step`, `mice()`, `obstacles()`, `turbo()`, `teleports()`)
  son `synchronized` sobre `Board`, y los getters devuelven **copias**
  defensivas (`new HashSet<>(mice)`, etc.), así que la UI nunca itera
  directamente la colección "viva". Se mantuvo este diseño.
- Nueva estructura introducida — `deathOrder`: como varias serpientes pueden
  morir "al mismo tiempo" (hilos distintos), se necesita una estructura
  segura para registrar el orden de muerte. Se usó
  `java.util.concurrent.ConcurrentLinkedQueue<Snake>` (cola no bloqueante,
  basada en el algoritmo de Michael-Scott) en lugar de una `ArrayList`
  sincronizada, para no introducir un lock adicional que compita con el de
  `Board` y porque el único uso es "agregar" (`offer`) concurrente y "leer
  el primero" (`peek`), exactamente el caso de uso de esta colección.
- La lista de serpientes (`allSnakes`, pasada a cada `SnakeRunner`) se
  publica como `List.copyOf(snakes)` — inmutable — porque su **identidad**
  (qué instancias contiene) nunca cambia tras arrancar el juego; solo el
  estado interno de cada `Snake` cambia, y ese estado ya está protegido
  individualmente. Esto evita tener que sincronizar también el acceso a la
  lista misma.

### 4) Esperas activas (busy-wait) identificadas

El starter no tenía bucles de sondeo explícitos (`while (!condicion) {}`
sin bloqueo), pero sí tenía el problema equivalente descrito en el punto 2
de la tabla: un mecanismo de "pausa" que no bloqueaba realmente a los hilos
de movimiento, sino que los dejaba correr libremente ignorando el estado.
Se sustituyó por `PauseController`, que bloquea con `wait()` (cero consumo
de CPU mientras está pausado) y despierta con `notifyAll()` — el mismo
patrón usado en la Parte I.

### 5) Regiones críticas y su justificación

- **`Board.step(...)`**: sigue siendo el único método `synchronized` que
  toca el estado compartido del tablero (ratones, obstáculos, turbo,
  teletransportadores) y ahora también decide colisiones entre serpientes.
  Se mantiene como región crítica **completa** (no se intentó partirla en
  candados más finos) porque:
  - Solo se ejecuta una vez por iteración de cada `SnakeRunner` (no es un
    método "caliente" llamado en un bucle ajustado sin `sleep`).
  - Al ser un único lock, **serializa** el movimiento de todas las
    serpientes (una avanza a la vez), lo cual es precisamente lo que
    garantiza que dos serpientes no puedan "pisar" la misma celda de forma
    inconsistente ni que dos hilos decidan la muerte de una tercera
    serpiente en instantes contradictorios.
  - Lo que **no** está dentro del lock: la decisión de girar aleatoriamente
    (`randomTurn()`) y el `Thread.sleep(...)` de cada `SnakeRunner` — así,
    ningún hilo mantiene el lock de `Board` mientras duerme.
- **`Snake` (métodos sobre `body`)**: región mínima, solo las 4-5 líneas que
  tocan el `Deque`; el resto de la clase (dirección, estado vivo/muerto) usa
  campos `volatile` en vez de synchronized, porque son lecturas/escrituras
  atómicas de una única referencia/booleano que no requieren un lock.
- **`PauseController`**: un único lock (`lock`) protege tres variables de
  estado pequeñas y estrechamente relacionadas (`paused`, `registered`,
  `parked`); no tiene sentido partirlo en varios candados porque casi todas
  las operaciones necesitan leer/escribir más de una de esas variables de
  forma atómica entre sí.
- **Orden de adquisición de locks / ausencia de deadlock**: dentro de
  `Board.step(...)` (con el lock de `Board` ya tomado) se llama a
  `other.occupies(...)` de otras serpientes, lo que internamente toma el
  lock de esa `Snake`. Como `Board` es `synchronized` como un todo, **nunca
  hay dos hilos dentro de `step()` a la vez**, así que nunca hay dos hilos
  intentando tomar el par de locks (Board, Snake) en órdenes distintos al
  mismo tiempo → no hay riesgo de deadlock por orden de adquisición de
  locks anidados.

### 6) Control de ejecución seguro (UI): Pausar / Reanudar

- `PauseController.pause()` marca el estado global como pausado.
- Cada `SnakeRunner` respeta ese estado en `awaitIfPaused()` (bloqueo real,
  sin busy-wait).
- `SnakeApp.togglePause()`, tras pedir la pausa, lanza un hilo virtual
  auxiliar que llama a `pauseController.awaitAllParked(500)` — esto NO
  bloquea el hilo de eventos de Swing (evita congelar la UI) — y solo
  cuando retorna (todos parqueados, o venció el timeout) se calculan y
  publican las estadísticas de vuelta al EDT vía `SwingUtilities.invokeLater`.
- Estadísticas mostradas: **serpiente más larga viva** (recorriendo las
  serpientes vivas y comparando `length()`) y **peor serpiente** (la
  primera en `deathOrder`, es decir, la primera en morir).
- Se probó explícitamente el caso "la suspensión no es instantánea":
  ver `PauseControllerTest`, que verifica que `awaitAllParked` efectivamente
  bloquea hasta que el último hilo confirma, y no antes.

### 7) Regla de "muerte" (diseño nuevo, necesario para las estadísticas pedidas)

El starter original **nunca mataba serpientes** (solo rebotaban contra
obstáculos), por lo que no existía manera de determinar una "peor
serpiente". Se definió: una serpiente muere si la celda a la que se movería
su cabeza está ocupada por el cuerpo de **cualquier** serpiente (incluida
ella misma), verificado dentro de `Board.step(...)` antes de mover. Al
morir: se marca `snake.kill()`, se agrega a `deathOrder`, su hilo
`SnakeRunner` termina (no se deja "zombie" ocupando CPU), y deja de
dibujarse en el tablero. Simplificación consciente: no se libera la celda
de la cola en el mismo tick en que se movería (no se contempla "perseguir
la propia cola"); se documenta como simplificación de diseño razonable para
el alcance del laboratorio.

### 8) Robustez bajo carga

Se agregaron pruebas en `src/test/java`:

- **`BoardConcurrencyTest`**: 30 serpientes en un tablero pequeño (12×12,
  para forzar colisiones frecuentes) corriendo en hilos virtuales durante
  1.5s, seguido de `shutdownNow()`; verifica que ningún hilo lance una
  excepción (ninguna `ConcurrentModificationException` ni otra) y que todos
  terminen limpiamente al ser interrumpidos.
- **`SnakeThreadSafetyTest`**: un hilo escribe (`advance`) 20 000 veces
  mientras otro lee (`snapshot`) 20 000 veces sobre la misma serpiente,
  simultáneamente; verifica que no se lance ninguna excepción.
- **`PauseControllerTest`**: 8 hilos se registran, uno principal pausa y
  espera a que todos confirmen (`awaitAllParked`), luego reanuda y verifica
  que todos se desbloqueen y terminen.

Adicionalmente (fuera del árbol de pruebas, como validación manual durante
el desarrollo) se ejecutó una prueba de humo con **40 serpientes en un
tablero de 12×12** ejecutando 5 ciclos de pausar → leer estadísticas →
reanudar mientras la simulación corría en segundo plano, confirmando cero
excepciones y consistencia del conteo de serpientes vivas/muertas en cada
pausa.

> **Nota sobre evidencias**: al no contar con entorno gráfico, no fue
> posible generar aquí capturas de pantalla de la ventana Swing. Al correr
> `mvn -q -DskipTests exec:java -Dsnakes=20` en tu máquina, adjunta al
> repositorio: (a) captura del juego corriendo con N alto, (b) captura tras
> pulsar "Pausar" mostrando la etiqueta de estadísticas, y (c) la salida de
> consola de `mvn clean verify` con las pruebas en verde.

---

## Entregables de este documento

- Código fuente de la Parte II (SnakeRace) en este mismo repositorio.
- Código fuente de la Parte I (`PrimeFinder.java`) a subir al repositorio
  indicado para esa parte.
- Este archivo: **`REPORT.md`**.

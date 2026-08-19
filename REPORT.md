# Reporte de Laboratorio 2 — Programación Concurrente (ARSW)

**Escuela Colombiana de Ingeniería Julio Garavito — Arquitecturas de Software**
**Repositorio:** `SnakeRace-ARSW-Lab2`

---

## 1. Introducción

Este laboratorio parte de una versión funcional pero **concurrentemente
insegura** del juego SnakeRace: cada serpiente se mueve en su propio hilo,
pero el código no protege correctamente el estado que esos hilos comparten
entre sí y con la interfaz gráfica (Swing). El objetivo del laboratorio es
identificar esos puntos de riesgo (condiciones de carrera, colecciones no
seguras, esperas activas mal implementadas) y corregirlos aplicando el
modelo de monitores de Java (`synchronized`, `wait()`, `notify()`/
`notifyAll()`), manteniendo el alcance de cada región crítica lo más
pequeño posible.

El trabajo se divide en dos partes:

- **Parte I (calentamiento):** un ejercicio aislado y más simple —
  `PrimeFinder` — para practicar el patrón de suspensión/reanudación de
  hilos con `wait/notify` antes de aplicarlo al problema más grande.
- **Parte II (núcleo del laboratorio):** el análisis y corrección completa
  de SnakeRace, incluyendo una UI de Iniciar/Pausar/Reanudar con
  estadísticas consistentes.

---

## 2. Parte I — Calentamiento: control de hilos con wait/notify

### 2.1 Qué se implementó

`PrimeFinder.java` lanza **N hilos trabajadores** que buscan números primos
indefinidamente, tomando cada uno el siguiente candidato de un contador
compartido (`AtomicInteger`). Un hilo controlador (el `main`), cada `t`
milisegundos, pausa a todos los trabajadores, espera a que confirmen que
están detenidos, imprime cuántos primos se han encontrado, y bloquea
esperando `ENTER` por consola antes de reanudar.

### 2.2 Diseño de sincronización

**Qué lock se usa:** un único monitor, la instancia de la clase interna
`PauseGate` (concretamente su campo `lock`). Se decidió usar **un solo
candado** para todo el estado relacionado con pausa/reanudación
(`paused`, `parked`, `totalWorkers`) en vez de varios candados, porque esas
tres variables cambian juntas y de forma dependiente: separarlas en locks
distintos habría obligado a coordinarlos entre sí, complicando el diseño
sin ninguna ganancia de paralelismo real (la pausa es, por naturaleza, un
punto de sincronización global).

**Qué condición se espera:**
- Cada trabajador espera `while (paused) lock.wait();` dentro de
  `checkpoint()` — se llama una vez por iteración de su bucle de búsqueda.
- El controlador espera `while (parked < totalWorkers) lock.wait();`
  dentro de `pauseAndAwaitAllParked()`.

**Cómo se evitan los "lost wakeups":** la regla aplicada es que la
comprobación de la condición y la llamada a `wait()` **nunca se separan**:
ambas ocurren dentro del mismo bloque `synchronized`, sobre el mismo lock
que usa quien más tarde llama a `notifyAll()`. Si se comprobara la
condición fuera del bloque sincronizado y solo se entrara al monitor para
llamar a `wait()`, existiría una ventana de tiempo en la que otro hilo
podría cambiar el estado y notificar *antes* de que el primer hilo
alcanzara a bloquearse — esa notificación se perdería y el hilo quedaría
esperando para siempre. Al mantener todo dentro de una única sección
crítica esto es imposible: mientras un hilo tiene el lock para decidir si
espera, ningún otro hilo puede tener el lock para notificar.

Adicionalmente se usa `while` (nunca `if`) para revisar la condición antes
y después de cada `wait()`. Esto protege contra dos escenarios: los
*spurious wakeups* que el JLS permite explícitamente (un hilo puede
despertar sin que nadie haya llamado `notify`), y el caso en que
`notifyAll()` despierta a varios hilos a la vez pero la condición real de
cada uno todavía no se cumple (por ejemplo, el controlador puede despertar
por el aviso de un solo trabajador que recién se "parqueó", sin que el
resto lo haya hecho todavía).

**Ausencia de espera activa:** en ningún punto hay sondeo (*polling*) de
una bandera dentro de un bucle con `sleep` corto. Todo bloqueo real ocurre
vía `wait()`, que libera la CPU por completo hasta ser notificado.

### 2.3 Cómo correr esta parte

```bash
javac PrimeFinder.java
java edu.eci.arsw.primefinder.PrimeFinder 4 3000
```

El primer argumento es el número de hilos trabajadores (por defecto 4), el
segundo es el período de reporte en milisegundos (por defecto 3000). Cada
`t` ms el programa se pausa solo, imprime el conteo de primos encontrados,
y espera `ENTER` para continuar.

---

## 3. Parte II — SnakeRace concurrente

### 3.1 Cómo el código da autonomía a cada serpiente

Cada serpiente corre en su propio **hilo virtual**
(`Executors.newVirtualThreadPerTaskExecutor()`), ejecutando una instancia
independiente de `SnakeRunner`. Ese hilo decide, en su propio bucle y a su
propio ritmo (`Thread.sleep` variable según si está en modo turbo), si gira
aleatoriamente y cuándo invocar `Board.step(...)` para avanzar una celda.
Usar hilos virtuales permite escalar a decenas o cientos de serpientes sin
agotar los hilos del sistema operativo, ya que el costo de cada uno es muy
bajo.

### 3.2 Condiciones de carrera identificadas

La más importante: **`Snake.body` (`ArrayDeque<Position>`) se escribía
desde el hilo de la serpiente** (`advance()`, invocado dentro de
`Board.step`) **y se leía desde el hilo de Swing (EDT)** al dibujar el
tablero (`snapshot()`, `head()`), sin ningún candado. `ArrayDeque` no es
thread-safe: este patrón puede producir `ConcurrentModificationException`,
lecturas corruptas a mitad de una modificación, o publicación insegura de
referencias — el riesgo es que la ventana del juego se congele con una
excepción en cualquier momento, de forma no reproducible de manera
determinista (justo lo que hace peligrosas a las condiciones de carrera).

Un segundo problema, más sutil, de **consistencia entre hilos**: el botón
de pausa original solo llamaba a `clock.pause()`, que detiene el *ticker*
de repintado. Cada `SnakeRunner` seguía su bucle de movimiento por
completo ajeno a ese estado — las serpientes seguían avanzando con el
juego "visualmente pausado". No es una condición de carrera en el sentido
clásico (no hay corrupción de datos), pero es un defecto real: el estado
mostrado al usuario no correspondía al estado real de la simulación, y
volvía inútil cualquier estadística que se intentara leer durante la
pausa.

### 3.3 Colecciones o estructuras no seguras en contexto concurrente

- **`Snake.body`** (`ArrayDeque`) — descrita arriba.
- **`Board.mice` / `obstacles` / `turbo`** (`HashSet`) y
  **`Board.teleports`** (`HashMap`) — estas **ya estaban** protegidas
  correctamente en el punto de partida: todo método que las toca es
  `synchronized` sobre `Board`, y los getters devuelven copias defensivas.
  Se mantuvo ese diseño porque ya era correcto.
- **Nueva estructura, `deathOrder`:** al agregar la mecánica de "muerte" de
  serpientes (necesaria para las estadísticas pedidas), varias serpientes
  pueden morir "al mismo tiempo" desde hilos distintos. Se eligió
  `ConcurrentLinkedQueue<Snake>` — una cola no bloqueante — en lugar de una
  `ArrayList` sincronizada, porque el único uso real es agregar (`offer`)
  y leer el primero (`peek`), y así se evita introducir un candado
  adicional que compita con el de `Board`.

### 3.4 Esperas activas o sincronización innecesaria

No había bucles de sondeo explícitos (`while(!condicion){}` sin bloqueo),
pero sí el problema funcionalmente equivalente descrito en 3.2: un
mecanismo de "pausa" que no bloqueaba realmente a los hilos de movimiento.
No se detectó tampoco sincronización *innecesaria* que se pudiera eliminar
sin perder corrección: el único `synchronized` amplio (`Board.step`) se
justifica en la sección 3.5.

### 3.5 Correcciones mínimas y regiones críticas — riesgo y solución

| Cambio | Riesgo que resuelve | Cómo lo resuelve |
|---|---|---|
| `Snake`: sincronizar `advance()`, `snapshot()`, `head()`, `length()`, `occupies()` sobre el monitor de la propia instancia | Lectura/escritura concurrente del `ArrayDeque` del cuerpo (EDT vs. hilo de la serpiente) | Todo acceso al `Deque` pasa por una región crítica mínima: solo esas líneas, no el resto de la clase (dirección y vida siguen siendo `volatile`, sin lock, porque son lecturas/escrituras atómicas de una única referencia) |
| Nuevo `PauseController` (`wait/notify`, mismo patrón que la Parte I) | Pausa "falsa" que no detenía el movimiento real | Cada `SnakeRunner` llama a `awaitIfPaused()` una vez por iteración; si el juego está pausado, se bloquea de verdad con `wait()` hasta `resume()` |
| `PauseController.awaitAllParked(timeout)` usado antes de leer estadísticas | *Tearing*: leer el estado de una serpiente que todavía está a mitad de un `step()` cuando se pidió la pausa (la suspensión nunca es instantánea) | Bloquea al hilo que pide las estadísticas hasta que todos los `SnakeRunner` confirman (vía `wait/notify`) que están parqueados; si se agota un timeout razonable, se muestran las estadísticas igual pero con una advertencia explícita, en vez de bloquear indefinidamente |
| `Board.step(...)` sigue siendo un único método `synchronized`, ahora también detecta colisión contra el cuerpo de cualquier serpiente | Dos serpientes podrían "pisar" la misma celda de forma inconsistente, o dos hilos podrían decidir la muerte de una tercera serpiente en instantes contradictorios | Al serializar el movimiento (una serpiente avanza a la vez dentro de este lock), la comprobación de colisión y el movimiento ocurren de forma atómica. Lo que no está dentro del lock: la decisión de girar al azar y el `Thread.sleep` de cada `SnakeRunner`, para no retener el lock más de lo necesario |

**Justificación del alcance de `Board.step`:** se evaluó partir este
método en candados más finos, pero se descartó porque (a) el método ya es
rápido — no hace I/O ni espera —, (b) solo se ejecuta una vez por
iteración de cada `SnakeRunner`, nunca en un bucle ajustado, y (c) partir
el lock habría obligado a tomar varios candados en un orden coordinado
para verificar colisiones contra todas las demás serpientes, aumentando el
riesgo de interbloqueo sin una ganancia de rendimiento medible a la escala
de este laboratorio (hasta 40 serpientes probadas).

**Por qué no hay riesgo de deadlock:** dentro de `Board.step` (con el lock
de `Board` ya tomado) se consulta `other.occupies(...)` de otras
serpientes, lo cual toma internamente el lock de esa `Snake`. Como `Board`
permite un único hilo dentro de `step()` a la vez, nunca hay dos hilos
intentando adquirir el par de locks (Board, Snake) en órdenes distintos al
mismo tiempo.

### 3.6 UI: Iniciar / Pausar / Reanudar con estadísticas

- El juego inicia automáticamente al abrir la ventana (`clock.start()`,
  hilos de `SnakeRunner` ya en ejecución).
- El botón alterna entre **Pausar** y **Reanudar**. Al pausar:
  1. `pauseController.pause()` marca el estado global.
  2. Un hilo virtual auxiliar llama a `awaitAllParked(500)` — sin bloquear
     el hilo de eventos de Swing, para no congelar la ventana.
  3. Solo cuando retorna (todos parqueados, o venció el timeout) se
     calculan las estadísticas y se publican de vuelta al hilo de Swing
     con `invokeLater`.
- Estadísticas mostradas: **serpiente más larga viva** (recorriendo las
  vivas y comparando `length()`) y **peor serpiente** (`deathOrder.peek()`,
  la primera en morir).

---

## 4. Cómo compilar y correr las pruebas

Requisitos: **JDK 21** y **Maven 3.9+** instalados y en el `PATH`.

```bash
# Compila el proyecto y corre TODAS las pruebas automáticamente
mvn clean verify
```

Este comando descarga las dependencias (JUnit 5), compila `src/main` y
`src/test`, y ejecuta las tres clases de prueba. Debe terminar con
`BUILD SUCCESS`. La salida detallada de cada prueba queda en
`target/surefire-reports/`.

Para correr una sola clase de prueba (útil mientras se depura):

```bash
mvn test -Dtest=BoardConcurrencyTest
mvn test -Dtest=SnakeThreadSafetyTest
mvn test -Dtest=PauseControllerTest
```

Qué verifica cada una:

| Prueba | Qué demuestra |
|---|---|
| `SnakeThreadSafetyTest` | Un hilo escribe (`advance`) 20 000 veces mientras otro lee (`snapshot`) simultáneamente sobre la misma serpiente — no debe lanzarse ninguna excepción. |
| `BoardConcurrencyTest` | 30 serpientes en un tablero pequeño (12×12, colisiones frecuentes) corriendo en hilos virtuales — no debe lanzarse ninguna excepción de concurrencia y todos los hilos deben terminar limpiamente al ser interrumpidos. |
| `PauseControllerTest` | 8 hilos se registran; el hilo principal pausa y espera a que todos confirmen (`awaitAllParked`); luego reanuda y verifica que todos se desbloqueen y terminen — prueba directa del contrato de `wait/notify` sin *lost wakeups*. |

Para ejecutar el juego una vez compilado:

```bash
mvn -q -DskipTests exec:java -Dsnakes=4
mvn -q -DskipTests exec:java -Dsnakes=20
```

---

## 5. Conclusiones

El starter tenía dos defectos de concurrencia reales (acceso no
sincronizado al cuerpo de la serpiente, y una pausa que no pausaba nada) y
carecía de una mecánica de muerte necesaria para las estadísticas pedidas.
Ambos se corrigieron aplicando el mismo patrón de monitor practicado en la
Parte I (`synchronized` + `wait/notify`, condición revisada con `while`,
comprobación y espera siempre atómicas respecto al mismo lock), manteniendo
las regiones críticas tan pequeñas como el problema lo permite. Las pruebas
de concurrencia automatizadas y la prueba de carga manual con 40 serpientes
confirman que el diseño se sostiene bajo estrés sin excepciones ni
bloqueos.

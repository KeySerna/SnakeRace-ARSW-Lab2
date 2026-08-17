package co.eci.snake.core;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Estado compartido del tablero (ratones, obstáculos, turbo, teleports).
 * Todas las mutaciones y lecturas consistentes pasan por métodos
 * {@code synchronized} sobre el monitor de Board: es el único candado que
 * protege estas colecciones (HashSet/HashMap, no seguras para concurrencia),
 * y es también el punto que serializa los movimientos de todas las
 * serpientes (una a la vez), lo cual evita condiciones de carrera al mover
 * dos serpientes "a la vez" sobre la misma celda.
 *
 * Justificación del alcance del lock: se sincroniza únicamente el método
 * step() (y los getters de colecciones) — es decir, exactamente la región
 * que lee/escribe el estado compartido. La decisión de girar aleatoriamente
 * (randomTurn) o de dormir (Thread.sleep) en SnakeRunner ocurre FUERA de
 * este lock, para no bloquear a las demás serpientes más de lo necesario.
 */
public final class Board {
  private final int width;
  private final int height;

  private final Set<Position> mice = new HashSet<>();
  private final Set<Position> obstacles = new HashSet<>();
  private final Set<Position> turbo = new HashSet<>();
  private final Map<Position, Position> teleports = new HashMap<>();

  public enum MoveResult { MOVED, ATE_MOUSE, HIT_OBSTACLE, ATE_TURBO, TELEPORTED, DIED }

  public Board(int width, int height) {
    if (width <= 0 || height <= 0) throw new IllegalArgumentException("Board dimensions must be positive");
    this.width = width;
    this.height = height;
    for (int i=0;i<6;i++) mice.add(randomEmpty());
    for (int i=0;i<4;i++) obstacles.add(randomEmpty());
    for (int i=0;i<3;i++) turbo.add(randomEmpty());
    createTeleportPairs(2);
  }

  public int width() { return width; }
  public int height() { return height; }

  public synchronized Set<Position> mice() { return new HashSet<>(mice); }
  public synchronized Set<Position> obstacles() { return new HashSet<>(obstacles); }
  public synchronized Set<Position> turbo() { return new HashSet<>(turbo); }
  public synchronized Map<Position, Position> teleports() { return new HashMap<>(teleports); }

  /**
   * Avanza a {@code snake} un paso. {@code allSnakes} es la lista completa
   * (viva o muerta) usada para detectar colisiones cuerpo-a-cuerpo; se pasa
   * por parámetro en lugar de guardarse como campo de Board porque su
   * identidad (la lista de instancias) no cambia tras el arranque del juego
   * — no añade estado mutable compartido adicional que proteger aquí.
   *
   * Nota de diseño sobre locks anidados: dentro de este método (ya con el
   * lock de Board tomado) se consulta snake.occupies(...) de otras
   * serpientes, lo que internamente toma el lock de esa Snake. Como Board
   * solo permite UN hilo dentro de step() a la vez, nunca hay dos hilos
   * intentando tomar locks de Board+Snake en órdenes distintos al mismo
   * tiempo, así que no hay riesgo de deadlock por orden de adquisición.
   */
  public synchronized MoveResult step(Snake snake, List<Snake> allSnakes) {
    Objects.requireNonNull(snake, "snake");
    if (!snake.isAlive()) return MoveResult.DIED;

    var head = snake.head();
    var dir = snake.direction();
    Position next = new Position(head.x() + dir.dx, head.y() + dir.dy).wrap(width, height);

    if (obstacles.contains(next)) return MoveResult.HIT_OBSTACLE;

    boolean teleported = false;
    if (teleports.containsKey(next)) {
      next = teleports.get(next);
      teleported = true;
    }

    for (Snake other : allSnakes) {
      if (other.isAlive() && other.occupies(next)) {
        return MoveResult.DIED;
      }
    }

    boolean ateMouse = mice.remove(next);
    boolean ateTurbo = turbo.remove(next);

    snake.advance(next, ateMouse);

    if (ateMouse) {
      mice.add(randomEmpty());
      obstacles.add(randomEmpty());
      if (ThreadLocalRandom.current().nextDouble() < 0.2) turbo.add(randomEmpty());
    }

    if (ateTurbo) return MoveResult.ATE_TURBO;
    if (ateMouse) return MoveResult.ATE_MOUSE;
    if (teleported) return MoveResult.TELEPORTED;
    return MoveResult.MOVED;
  }

  private void createTeleportPairs(int pairs) {
    for (int i=0;i<pairs;i++) {
      Position a = randomEmpty();
      Position b = randomEmpty();
      teleports.put(a, b);
      teleports.put(b, a);
    }
  }

  private Position randomEmpty() {
    var rnd = ThreadLocalRandom.current();
    Position p;
    int guard = 0;
    do {
      p = new Position(rnd.nextInt(width), rnd.nextInt(height));
      guard++;
      if (guard > width*height*2) break;
    } while (mice.contains(p) || obstacles.contains(p) || turbo.contains(p) || teleports.containsKey(p));
    return p;
  }
}

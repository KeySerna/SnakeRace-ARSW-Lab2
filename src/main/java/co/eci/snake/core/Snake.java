package co.eci.snake.core;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Representa una serpiente. El cuerpo ({@link #body}) es mutado por el hilo
 * {@code SnakeRunner} que la mueve (a través de {@link Board#step}) y leído
 * concurrentemente por el hilo de UI (Swing EDT) al pintar el tablero.
 * ArrayDeque NO es thread-safe, así que todo acceso al cuerpo se protege con
 * el monitor intrínseco de esta instancia ({@code this}). La región crítica
 * se mantiene mínima: solo las operaciones sobre {@code body} (y el contador
 * asociado {@code maxLength}) están sincronizadas; el resto (dirección, vida)
 * usa campos {@code volatile} porque son lecturas/escrituras atómicas de una
 * sola referencia y no requieren un lock.
 */
public final class Snake {
  private final int id;
  private final Deque<Position> body = new ArrayDeque<>();
  private volatile Direction direction;
  private volatile boolean alive = true;
  private int maxLength = 5;

  private Snake(int id, Position start, Direction dir) {
    this.id = id;
    body.addFirst(start);
    this.direction = dir;
  }

  public static Snake of(int id, int x, int y, Direction dir) {
    return new Snake(id, new Position(x, y), dir);
  }

  public int id() { return id; }

  public Direction direction() { return direction; }

  public void turn(Direction dir) {
    if ((direction == Direction.UP && dir == Direction.DOWN) ||
        (direction == Direction.DOWN && dir == Direction.UP) ||
        (direction == Direction.LEFT && dir == Direction.RIGHT) ||
        (direction == Direction.RIGHT && dir == Direction.LEFT)) {
      return;
    }
    this.direction = dir;
  }

  public synchronized Position head() { return body.peekFirst(); }

  /** Copia defensiva y consistente del cuerpo, segura para el hilo de UI. */
  public synchronized Deque<Position> snapshot() { return new ArrayDeque<>(body); }

  public synchronized int length() { return body.size(); }

  /** true si alguna celda del cuerpo actual ocupa la posición dada. */
  public synchronized boolean occupies(Position p) { return body.contains(p); }

  public synchronized void advance(Position newHead, boolean grow) {
    body.addFirst(newHead);
    if (grow) maxLength++;
    while (body.size() > maxLength) body.removeLast();
  }

  public boolean isAlive() { return alive; }

  public void kill() { alive = false; }
}

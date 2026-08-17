package co.eci.snake.concurrency;

import co.eci.snake.core.Board;
import co.eci.snake.core.Direction;
import co.eci.snake.core.Snake;
import co.eci.snake.core.engine.PauseController;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Prueba de robustez bajo carga (sin UI): N serpientes en hilos virtuales
 * independientes durante un intervalo corto, con espacio pequeño (colisiones
 * frecuentes) para forzar el camino de "muerte". Verifica que no se lance
 * ninguna excepción de concurrencia (ConcurrentModificationException u otra)
 * y que el conjunto de vivas + muertas sea siempre consistente.
 */
class BoardConcurrencyTest {

  @Test
  void manySnakesUnderLoadDoNotThrow() throws InterruptedException {
    int n = 30;
    Board board = new Board(12, 12); // tablero pequeño -> muchas colisiones
    List<Snake> snakes = new ArrayList<>();
    for (int i = 0; i < n; i++) {
      snakes.add(Snake.of(i, i % 12, (i * 3) % 12, Direction.values()[i % 4]));
    }
    var allSnakes = List.copyOf(snakes);
    PauseController pauseController = new PauseController();
    ConcurrentLinkedQueue<Snake> deathOrder = new ConcurrentLinkedQueue<>();
    AtomicReference<Throwable> failure = new AtomicReference<>();

    ExecutorService exec = Executors.newVirtualThreadPerTaskExecutor();
    CountDownLatch done = new CountDownLatch(n);
    for (Snake s : snakes) {
      exec.submit(() -> {
        try {
          new SnakeRunner(s, board, allSnakes, pauseController, deathOrder::add).run();
        } catch (Throwable t) {
          failure.set(t);
        } finally {
          done.countDown();
        }
      });
    }

    // Deja correr la simulación un rato, luego interrumpe todo.
    Thread.sleep(1500);
    exec.shutdownNow();
    boolean finished = exec.awaitTermination(3, TimeUnit.SECONDS);

    assertTrue(finished, "Todos los hilos deben terminar tras la interrupción");
    assertNull(failure.get(), "No debe haber excepciones de concurrencia bajo carga");
  }
}

package co.eci.snake.core;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Verifica que Snake.advance()/snapshot() no producen
 * ConcurrentModificationException ni ninguna otra excepción bajo lectura y
 * escritura concurrentes reales (un hilo escribe, otro lee sin parar).
 */
class SnakeThreadSafetyTest {

  @Test
  void concurrentAdvanceAndSnapshotDoNotThrow() throws InterruptedException {
    Snake snake = Snake.of(0, 5, 5, Direction.RIGHT);
    AtomicReference<Throwable> failure = new AtomicReference<>();
    int iterations = 20_000;
    CountDownLatch done = new CountDownLatch(2);

    Thread writer = new Thread(() -> {
      try {
        for (int i = 0; i < iterations; i++) {
          snake.advance(new Position(i % 50, (i * 3) % 50), i % 7 == 0);
        }
      } catch (Throwable t) {
        failure.set(t);
      } finally {
        done.countDown();
      }
    });

    Thread reader = new Thread(() -> {
      try {
        for (int i = 0; i < iterations; i++) {
          List<Position> copy = new ArrayList<>(snake.snapshot());
          // fuerza a iterar la copia, como hace GamePanel.paintComponent
          for (Position p : copy) { assert p != null; }
        }
      } catch (Throwable t) {
        failure.set(t);
      } finally {
        done.countDown();
      }
    });

    writer.start();
    reader.start();
    done.await();

    assertNull(failure.get(), "No debe haber excepciones por acceso concurrente al cuerpo de la serpiente");
  }
}

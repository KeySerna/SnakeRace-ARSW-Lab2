package co.eci.snake.core.engine;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifica el contrato de PauseController: los hilos "trabajadores" se
 * bloquean (sin espera activa) mientras está en pausa, y awaitAllParked()
 * solo retorna cuando TODOS confirmaron estar parqueados.
 */
class PauseControllerTest {

  @Test
  void allWorkersParkOnPauseAndResumeUnblocksThem() throws InterruptedException {
    int workers = 8;
    PauseController controller = new PauseController();
    AtomicInteger loopsAfterResume = new AtomicInteger();
    CountDownLatch registered = new CountDownLatch(workers);
    CountDownLatch startGate = new CountDownLatch(1); // el main la abre DESPUÉS de pause()
    CountDownLatch resumedAndLooped = new CountDownLatch(workers);

    for (int i = 0; i < workers; i++) {
      Thread t = new Thread(() -> {
        try {
          controller.register();
          registered.countDown();
          startGate.await(); // todos entran a awaitIfPaused() ya con paused=true
          controller.awaitIfPaused(); // debe bloquear aquí hasta resume()
          loopsAfterResume.incrementAndGet();
        } catch (InterruptedException ignored) {
        } finally {
          controller.unregister();
          resumedAndLooped.countDown();
        }
      });
      t.setDaemon(true);
      t.start();
    }

    assertTrue(registered.await(2, TimeUnit.SECONDS), "Todos los hilos deben registrarse");
    controller.pause();
    startGate.countDown(); // ahora sí entran a awaitIfPaused(), con paused ya en true

    boolean allParked = controller.awaitAllParked(2000);
    assertTrue(allParked, "Todos los hilos deben confirmar que están parqueados");

    controller.resume();
    boolean finished = resumedAndLooped.await(2, TimeUnit.SECONDS);
    assertTrue(finished, "Tras resume(), todos los hilos deben desbloquearse y terminar");
  }
}

package co.eci.snake.core.engine;

/**
 * Coordina la pausa/reanudación cooperativa de un número variable de hilos
 * trabajadores (uno por serpiente) usando el patrón clásico de monitor:
 * un único lock intrínseco + wait()/notifyAll(). No hay espera activa: un
 * hilo pausado queda bloqueado en wait() hasta que resume() lo despierta.
 *
 * También resuelve el problema de la "foto a medias" (tearing) al pausar:
 * como la suspensión de cada hilo no es instantánea (puede estar a mitad de
 * un step()), el hilo de UI puede llamar a {@link #awaitAllParked(long)}
 * tras pause() para bloquearse hasta que TODOS los hilos registrados hayan
 * confirmado que están efectivamente detenidos, y solo entonces leer las
 * estadísticas (serpiente más larga viva, primera en morir).
 *
 * Diseño del lock: un único monitor ({@code lock}) protege tres variables de
 * estado (paused, registered, parked). Evita "lost wakeups" porque tanto la
 * comprobación de la condición como el wait() ocurren dentro del mismo bloque
 * synchronized, y el while (en vez de if) protege contra "spurious wakeups"
 * y contra que un hilo se despierte antes de que la condición realmente se
 * cumpla.
 */
public final class PauseController {
  private final Object lock = new Object();
  private boolean paused = false;
  private int registered = 0;
  private int parked = 0;

  /** Debe llamarse una vez al iniciar cada SnakeRunner. */
  public void register() {
    synchronized (lock) {
      registered++;
    }
  }

  /** Debe llamarse una vez cuando un SnakeRunner termina (muere o se interrumpe). */
  public void unregister() {
    synchronized (lock) {
      registered--;
      // Puede destrabar a un hilo esperando a que todos estén parqueados.
      lock.notifyAll();
    }
  }

  public void pause() {
    synchronized (lock) {
      paused = true;
    }
  }

  public void resume() {
    synchronized (lock) {
      paused = false;
      parked = 0;
      lock.notifyAll();
    }
  }

  public boolean isPaused() {
    synchronized (lock) {
      return paused;
    }
  }

  /**
   * Llamado por cada hilo trabajador una vez por iteración. Si el juego está
   * en pausa, el hilo se marca como "parqueado" y bloquea en wait() (sin
   * consumir CPU) hasta que resume() lo despierte.
   */
  public void awaitIfPaused() throws InterruptedException {
    synchronized (lock) {
      if (!paused) return;
      parked++;
      lock.notifyAll(); // avisa a quien esté esperando a que todos se parqueen
      while (paused) {
        lock.wait();
      }
      parked--;
    }
  }

  /**
   * Bloquea al hilo llamante (típicamente la UI) hasta que todos los hilos
   * registrados estén parqueados, o venza el timeout. Debe llamarse después
   * de pause(). Devuelve false si venció el timeout (se sigue mostrando el
   * mejor estado disponible, pero se documenta que podría haber tearing).
   */
  public boolean awaitAllParked(long timeoutMs) throws InterruptedException {
    synchronized (lock) {
      long deadline = System.currentTimeMillis() + timeoutMs;
      while (parked < registered) {
        long remaining = deadline - System.currentTimeMillis();
        if (remaining <= 0) return false;
        lock.wait(remaining);
      }
      return true;
    }
  }
}

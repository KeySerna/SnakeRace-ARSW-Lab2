package co.eci.snake.concurrency;

import co.eci.snake.core.Board;
import co.eci.snake.core.Direction;
import co.eci.snake.core.Snake;
import co.eci.snake.core.engine.PauseController;

import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Consumer;

/**
 * Lógica autónoma de una serpiente, ejecutada en su propio hilo virtual.
 *
 * Antes: el botón "Pausar" solo detenía el reloj de repintado (GameClock),
 * pero cada SnakeRunner seguía su propio bucle con Thread.sleep(), ajeno por
 * completo al estado de pausa -> las serpientes seguían moviéndose con el
 * juego "pausado". Ahora cada hilo consulta un {@link PauseController}
 * compartido una vez por iteración y se bloquea allí (wait(), sin busy-wait)
 * mientras el juego esté en pausa.
 */
public final class SnakeRunner implements Runnable {
  private final Snake snake;
  private final Board board;
  private final List<Snake> allSnakes;
  private final PauseController pauseController;
  private final Consumer<Snake> onDeath;
  private final int baseSleepMs = 80;
  private final int turboSleepMs = 40;
  private int turboTicks = 0;

  public SnakeRunner(Snake snake, Board board, List<Snake> allSnakes,
                      PauseController pauseController, Consumer<Snake> onDeath) {
    this.snake = snake;
    this.board = board;
    this.allSnakes = allSnakes;
    this.pauseController = pauseController;
    this.onDeath = onDeath;
  }

  @Override
  public void run() {
    pauseController.register();
    try {
      while (!Thread.currentThread().isInterrupted() && snake.isAlive()) {
        pauseController.awaitIfPaused();
        if (!snake.isAlive()) break;

        maybeTurn();
        var res = board.step(snake, allSnakes);
        if (res == Board.MoveResult.HIT_OBSTACLE) {
          randomTurn();
        } else if (res == Board.MoveResult.ATE_TURBO) {
          turboTicks = 100;
        } else if (res == Board.MoveResult.DIED) {
          snake.kill();
          onDeath.accept(snake);
          break;
        }
        int sleep = (turboTicks > 0) ? turboSleepMs : baseSleepMs;
        if (turboTicks > 0) turboTicks--;
        Thread.sleep(sleep);
      }
    } catch (InterruptedException ie) {
      Thread.currentThread().interrupt();
    } finally {
      pauseController.unregister();
    }
  }

  private void maybeTurn() {
    double p = (turboTicks > 0) ? 0.05 : 0.10;
    if (ThreadLocalRandom.current().nextDouble() < p) randomTurn();
  }

  private void randomTurn() {
    var dirs = Direction.values();
    snake.turn(dirs[ThreadLocalRandom.current().nextInt(dirs.length)]);
  }
}

package co.eci.snake.ui.legacy;

import co.eci.snake.concurrency.SnakeRunner;
import co.eci.snake.core.Board;
import co.eci.snake.core.Direction;
import co.eci.snake.core.Position;
import co.eci.snake.core.Snake;
import co.eci.snake.core.engine.GameClock;
import co.eci.snake.core.engine.PauseController;

import javax.swing.*;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executors;

public final class SnakeApp extends JFrame {

  private final Board board;
  private final GamePanel gamePanel;
  private final JButton actionButton;
  private final JLabel statsLabel;
  private final GameClock clock;
  private final PauseController pauseController = new PauseController();
  private final java.util.List<Snake> snakes = new java.util.ArrayList<>();
  /**
   * Cola concurrente no bloqueante (Michael-Scott) que registra el orden en
   * que las serpientes van muriendo. Se usa en lugar de una lista sencilla
   * porque varios hilos SnakeRunner pueden reportar una muerte
   * simultáneamente; ConcurrentLinkedQueue permite offer() concurrente sin
   * necesidad de un lock explícito adicional (evita contención sobre el
   * lock de Board, que ya está protegiendo el paso de movimiento).
   */
  private final Queue<Snake> deathOrder = new ConcurrentLinkedQueue<>();

  public SnakeApp() {
    super("The Snake Race");
    this.board = new Board(35, 28);

    int N = Integer.getInteger("snakes", 2);
    for (int i = 0; i < N; i++) {
      int x = 2 + (i * 3) % board.width();
      int y = 2 + (i * 2) % board.height();
      var dir = Direction.values()[i % Direction.values().length];
      snakes.add(Snake.of(i, x, y, dir));
    }
    // Vista inmutable: la identidad de la lista no cambia tras arrancar el
    // juego (ninguna serpiente se agrega/quita), solo su estado interno,
    // que cada Snake ya protege con su propio lock.
    var allSnakes = java.util.List.copyOf(snakes);

    this.gamePanel = new GamePanel(board, () -> snakes);
    this.actionButton = new JButton("Pausar");
    this.statsLabel = new JLabel(" ", SwingConstants.CENTER);

    setLayout(new BorderLayout());
    add(gamePanel, BorderLayout.CENTER);
    var south = new JPanel(new BorderLayout());
    south.add(actionButton, BorderLayout.WEST);
    south.add(statsLabel, BorderLayout.CENTER);
    add(south, BorderLayout.SOUTH);

    setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
    pack();
    setLocationRelativeTo(null);

    this.clock = new GameClock(60, () -> SwingUtilities.invokeLater(gamePanel::repaint));

    var exec = Executors.newVirtualThreadPerTaskExecutor();
    for (Snake s : snakes) {
      exec.submit(new SnakeRunner(s, board, allSnakes, pauseController, deathOrder::add));
    }

    actionButton.addActionListener((ActionEvent e) -> togglePause());

    gamePanel.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).put(KeyStroke.getKeyStroke("SPACE"), "pause");
    gamePanel.getActionMap().put("pause", new AbstractAction() {
      @Override
      public void actionPerformed(ActionEvent e) {
        togglePause();
      }
    });

    var player = snakes.get(0);
    InputMap im = gamePanel.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW);
    ActionMap am = gamePanel.getActionMap();
    im.put(KeyStroke.getKeyStroke("LEFT"), "left");
    im.put(KeyStroke.getKeyStroke("RIGHT"), "right");
    im.put(KeyStroke.getKeyStroke("UP"), "up");
    im.put(KeyStroke.getKeyStroke("DOWN"), "down");
    am.put("left", new AbstractAction() {
      @Override
      public void actionPerformed(ActionEvent e) {
        player.turn(Direction.LEFT);
      }
    });
    am.put("right", new AbstractAction() {
      @Override
      public void actionPerformed(ActionEvent e) {
        player.turn(Direction.RIGHT);
      }
    });
    am.put("up", new AbstractAction() {
      @Override
      public void actionPerformed(ActionEvent e) {
        player.turn(Direction.UP);
      }
    });
    am.put("down", new AbstractAction() {
      @Override
      public void actionPerformed(ActionEvent e) {
        player.turn(Direction.DOWN);
      }
    });

    if (snakes.size() > 1) {
      var p2 = snakes.get(1);
      im.put(KeyStroke.getKeyStroke('A'), "p2-left");
      im.put(KeyStroke.getKeyStroke('D'), "p2-right");
      im.put(KeyStroke.getKeyStroke('W'), "p2-up");
      im.put(KeyStroke.getKeyStroke('S'), "p2-down");
      am.put("p2-left", new AbstractAction() {
        @Override
        public void actionPerformed(ActionEvent e) {
          p2.turn(Direction.LEFT);
        }
      });
      am.put("p2-right", new AbstractAction() {
        @Override
        public void actionPerformed(ActionEvent e) {
          p2.turn(Direction.RIGHT);
        }
      });
      am.put("p2-up", new AbstractAction() {
        @Override
        public void actionPerformed(ActionEvent e) {
          p2.turn(Direction.UP);
        }
      });
      am.put("p2-down", new AbstractAction() {
        @Override
        public void actionPerformed(ActionEvent e) {
          p2.turn(Direction.DOWN);
        }
      });
    }

    setVisible(true);
    clock.start();
  }

  /**
   * Al pausar: primero se marca el estado como PAUSED (pauseController +
   * clock), y luego -en un hilo virtual aparte, para no bloquear el EDT- se
   * espera a que TODOS los SnakeRunner confirmen que ya están parqueados
   * (awaitAllParked). Solo entonces se calculan y publican las estadísticas
   * de vuelta al EDT con invokeLater. Esto evita el "tearing": sin esta
   * espera, se podría leer el largo de una serpiente que en ese instante
   * todavía está a mitad de un step().
   */
  private void togglePause() {
    if ("Pausar".equals(actionButton.getText())) {
      actionButton.setText("Reanudar");
      actionButton.setEnabled(false);
      clock.pause();
      pauseController.pause();
      Thread.ofVirtual().start(() -> {
        boolean allParked;
        try {
          allParked = pauseController.awaitAllParked(500);
        } catch (InterruptedException ie) {
          Thread.currentThread().interrupt();
          allParked = false;
        }
        boolean finalAllParked = allParked;
        SwingUtilities.invokeLater(() -> {
          statsLabel.setText(buildStats(finalAllParked));
          actionButton.setEnabled(true);
        });
      });
    } else {
      actionButton.setText("Pausar");
      statsLabel.setText(" ");
      pauseController.resume();
      clock.resume();
    }
  }

  private String buildStats(boolean consistent) {
    Snake longestAlive = null;
    int bestLen = -1;
    for (Snake s : snakes) {
      if (s.isAlive()) {
        int len = s.length();
        if (len > bestLen) { bestLen = len; longestAlive = s; }
      }
    }
    Snake worst = deathOrder.peek();

    String longestTxt = (longestAlive != null)
        ? "Más larga viva: serpiente #" + longestAlive.id() + " (" + bestLen + ")"
        : "Ninguna serpiente sigue viva";
    String worstTxt = (worst != null)
        ? "Peor (primera en morir): serpiente #" + worst.id()
        : "Ninguna serpiente ha muerto aún";

    String warn = consistent ? "" : " [advertencia: no todos los hilos confirmaron pausa a tiempo]";
    return longestTxt + "   |   " + worstTxt + warn;
  }

  public static final class GamePanel extends JPanel {
    private final Board board;
    private final Supplier snakesSupplier;
    private final int cell = 20;

    @FunctionalInterface
    public interface Supplier {
      List<Snake> get();
    }

    public GamePanel(Board board, Supplier snakesSupplier) {
      this.board = board;
      this.snakesSupplier = snakesSupplier;
      setPreferredSize(new Dimension(board.width() * cell + 1, board.height() * cell + 40));
      setBackground(Color.WHITE);
    }

    @Override
    protected void paintComponent(Graphics g) {
      super.paintComponent(g);
      var g2 = (Graphics2D) g.create();
      g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

      g2.setColor(new Color(220, 220, 220));
      for (int x = 0; x <= board.width(); x++)
        g2.drawLine(x * cell, 0, x * cell, board.height() * cell);
      for (int y = 0; y <= board.height(); y++)
        g2.drawLine(0, y * cell, board.width() * cell, y * cell);

      // Obstáculos
      g2.setColor(new Color(255, 102, 0));
      for (var p : board.obstacles()) {
        int x = p.x() * cell, y = p.y() * cell;
        g2.fillRect(x + 2, y + 2, cell - 4, cell - 4);
        g2.setColor(Color.RED);
        g2.drawLine(x + 4, y + 4, x + cell - 6, y + 4);
        g2.drawLine(x + 4, y + 8, x + cell - 6, y + 8);
        g2.drawLine(x + 4, y + 12, x + cell - 6, y + 12);
        g2.setColor(new Color(255, 102, 0));
      }

      // Ratones
      g2.setColor(Color.BLACK);
      for (var p : board.mice()) {
        int x = p.x() * cell, y = p.y() * cell;
        g2.fillOval(x + 4, y + 4, cell - 8, cell - 8);
        g2.setColor(Color.WHITE);
        g2.fillOval(x + 8, y + 8, cell - 16, cell - 16);
        g2.setColor(Color.BLACK);
      }

      // Teleports (flechas rojas)
      Map<Position, Position> tp = board.teleports();
      g2.setColor(Color.RED);
      for (var entry : tp.entrySet()) {
        Position from = entry.getKey();
        int x = from.x() * cell, y = from.y() * cell;
        int[] xs = { x + 4, x + cell - 4, x + cell - 10, x + cell - 10, x + 4 };
        int[] ys = { y + cell / 2, y + cell / 2, y + 4, y + cell - 4, y + cell / 2 };
        g2.fillPolygon(xs, ys, xs.length);
      }

      // Turbo (rayos)
      g2.setColor(Color.BLACK);
      for (var p : board.turbo()) {
        int x = p.x() * cell, y = p.y() * cell;
        int[] xs = { x + 8, x + 12, x + 10, x + 14, x + 6, x + 10 };
        int[] ys = { y + 2, y + 2, y + 8, y + 8, y + 16, y + 10 };
        g2.fillPolygon(xs, ys, xs.length);
      }

      // Serpientes
      var snakes = snakesSupplier.get();
      int idx = 0;
      for (Snake s : snakes) {
        if (!s.isAlive()) { idx++; continue; }
        var body = s.snapshot().toArray(new Position[0]);
        for (int i = 0; i < body.length; i++) {
          var p = body[i];
          Color base = (idx == 0) ? new Color(0, 170, 0) : new Color(0, 160, 180);
          int shade = Math.max(0, 40 - i * 4);
          g2.setColor(new Color(
              Math.min(255, base.getRed() + shade),
              Math.min(255, base.getGreen() + shade),
              Math.min(255, base.getBlue() + shade)));
          g2.fillRect(p.x() * cell + 2, p.y() * cell + 2, cell - 4, cell - 4);
        }
        idx++;
      }
      g2.dispose();
    }
  }

  public static void launch() {
    SwingUtilities.invokeLater(SnakeApp::new);
  }
}

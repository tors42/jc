package jc.app;

import java.util.*;
import java.util.concurrent.*;
import java.util.prefs.Preferences;
import java.util.stream.Stream;

import javax.swing.*;
import java.awt.*;
import java.time.Duration;

import chariot.*;
import chariot.model.*;
import chariot.model.Event.*;
import chariot.model.GameStateEvent.Soon;
import chariot.model.Enums.*;
import chariot.model.Enums.Color;
import chariot.util.Board;
import jc.model.JCState;
import jc.model.JCState.*;

public interface Play {
    static ExecutorService executor = Executors.newSingleThreadExecutor();
    static Map<String, GameHandler> games = new ConcurrentHashMap<>();

    static Play casual15m10s() {

        if (! (initializeClient() instanceof Some<ClientAuth>(ClientAuth client))) return dummy;

        // Connnect to Lichess so we can receive events.
        // If someone accepts the "seek" we are about to send to Lichess,
        // we will be notified about that through a gameStart event from this stream.
        final var events = client.board().connect().stream();

        executor.submit(() -> {
            events.forEach(event -> {
                switch(event) {
                    case GameStartEvent(var game, var __) -> {
                        var gameHandler = new GameHandler(client, game);
                        games.put(game.gameId(), gameHandler);
                        gameHandler.start();
                    }
                    case GameStopEvent(var game, var __, var ___) -> {
                        var gameHandler = games.remove(game.gameId());
                        if (gameHandler != null) gameHandler.stop();
                    }
                    default -> {}
                }
            });
        });

        return new Play() {
            volatile Stream<?> closeToStopCurrentSeek = Stream.of();

            public void startSeek() {
                closeToStopCurrentSeek.close();
                var seek = client.board().seekRealTime(params -> params
                        .clockRapid15m10s()
                        .rated(false)
                        );
                closeToStopCurrentSeek = seek.stream();
            }

            @Override
            public void stop() {
                events.close();
                closeToStopCurrentSeek.close();
                executor.shutdownNow();

            }
        };
    }

    sealed interface PlayEvent {}
    record NewGame(JCUser white, JCUser black, Duration intitial, Board board, boolean flipped) implements PlayEvent {};
    record BoardUpdate(Board board, Duration whiteTime, Duration blackTime) implements PlayEvent {};
    record TimeTick() implements PlayEvent {};
    record Chat(String from, String text, String room) implements PlayEvent {};
    record Gone(boolean gone, Opt<Integer> secondsUntilClaimable) implements PlayEvent {};

    class GameHandler extends JFrame {
        final ClientAuth client;
        final GameInfo game;
        BlockingQueue<PlayEvent> queue = new ArrayBlockingQueue<>(1024);
        volatile Stream<PlayEvent> stream = Stream.of();
        ScheduledExecutorService timeTickerExecutor = Executors.newSingleThreadScheduledExecutor();
        ExecutorService executor = Executors.newFixedThreadPool(2);

        final JCUser me;

        JTextArea textArea = new JTextArea(22, 35);
        JTextField textField = new JTextField(8);
        JPanel buttonPanel = new JPanel();
        JButton resign = new JButton("Resign");
        JButton draw = new JButton("Draw");
        JButton exit = new JButton("Exit");


        GameHandler(ClientAuth client, GameInfo game) {
            this.client = client;
            this.game = game;

            setDefaultCloseOperation(WindowConstants.EXIT_ON_CLOSE);
            setTitle("JC - Game ID: " + game.gameId());
            JPanel panel = new JPanel();
            getContentPane().add(panel);

            textArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 20));

            panel.setLayout(new BorderLayout());
            panel.add(textArea, BorderLayout.CENTER);
            panel.add(buttonPanel, BorderLayout.NORTH);
            panel.add(textField, BorderLayout.SOUTH);

            textField.addActionListener(event -> {
                String move = textField.getText();
                client.board().move(game.gameId(), move);
                SwingUtilities.invokeLater(() -> textField.setText(""));
            });

            buttonPanel.add(resign);
            buttonPanel.add(draw);
            buttonPanel.add(exit);

            resign.addActionListener(event -> client.board().resign(game.gameId()));
            draw.addActionListener(event -> client.board().handleDrawOffer(game.gameId(), Offer.yes));
            exit.addActionListener(event -> {
                resign.doClick();
                stop();
                dispose();
            });

            pack();
            setVisible(true);

            me = switch(client.account().profile()) {
                case Entry<UserAuth>(UserAuth profile) -> new JCUser(profile.name(), profile.title().orElse(""));
                default                                -> new JCUser("Me", "");
            };

            var opponent = new JCUser(game.opponent().name(), "");

            Board board = Board.fromFEN(game.fen());

            record Colors(JCUser white, JCUser black) {}
            var colors = switch(game.color()) {
                case white -> new Colors(me, opponent);
                case black -> new Colors(opponent, me);
            };

            queue.offer(new NewGame(colors.white, colors.black, game.time().timeLeft().orElse(Duration.ZERO), board, colors.black == me));
        }

        void start() {
            timeTickerExecutor.scheduleAtFixedRate(
                    () -> queue.offer(new TimeTick()),
                    0, 1, TimeUnit.SECONDS);

            stream = client.board().connectToGame(game.gameId()).stream()
                .map(event -> switch(event) {
                    case GameStateEvent.Full full ->
                        new BoardUpdate(
                                full.gameType().variant() instanceof VariantType.FromPosition fromPosition
                                && fromPosition.fen() instanceof Some<String>(String fen)
                                ? Board.fromFEN(fen)
                                : Board.fromStandardPosition(),
                                full.gameType().timeControl() instanceof RealTime real
                                ? real.initial()
                                : Duration.ZERO,
                                full.gameType().timeControl() instanceof RealTime real
                                ? real.initial()
                                : Duration.ZERO);

                    case GameStateEvent.State state -> new BoardUpdate(
                            Board.fromFEN(game.fen()).play(state.moves()),
                            state.wtime(),
                            state.btime());
                    case GameStateEvent.Chat(String username, String text, String room) -> new Chat(username, text, room);
                    case GameStateEvent.OpponentGone gone -> new Gone(gone.gone(), gone.claimable() instanceof Soon(Duration time) ? Opt.of((int)time.toSeconds()) : gone.canClaim() ? Opt.of(0) : Opt.of(-1));
                });

            executor.submit(() -> stream.forEach(queue::offer));

            executor.submit(() -> {

                JCState currentState = new JCState.None();

                while(true) {
                    final PlayEvent event;
                    try {
                        event = queue.take();
                    } catch(InterruptedException ie) {
                        break;
                    }

                    currentState = switch(event) {
                        case NewGame(var white, var black, Duration initial, var board, var flipped) -> JCState.of(
                                new JCPlayerInfo(white, initial, Color.white),
                                new JCPlayerInfo(black, initial, Color.black),
                                board,
                                flipped);
                        case BoardUpdate(var board, Duration whiteTime, Duration blackTime) -> currentState
                            .withBoard(board)
                            .withWhiteTime(whiteTime)
                            .withBlackTime(blackTime);
                        case TimeTick() -> currentState.withOneSecondTick();
                        case Chat chat  -> currentState;
                        case Gone gone  -> currentState;
                    };

                    String board = JCState.render(currentState);
                    SwingUtilities.invokeLater(() -> textArea.setText(board));
                };
            });
        }

        void stop() {
            stream.close();
            executor.shutdownNow();
            timeTickerExecutor.shutdownNow();
        }

    }


    static String lichessApi = "https://lichess.org";

    // Either user has provided a token via environment variable LICHESS_TOKEN,
    // or we will use OAuth2 PKCE to ask the user for authorization
    static Opt<ClientAuth> initializeClient() {
        return switch(System.getenv("LICHESS_TOKEN")) {
            case null -> {
                var client = Client.load(prefs());
                if (client instanceof ClientAuth auth) yield Opt.of(auth);

                client = Client.basic(c -> c.api(lichessApi));

                var authResult = client.withPkce(
                        uri -> System.out.println("""

                        Visit the following URL and choose to grant access or not,
                        %s

                        """.formatted(uri)),
                        pkce -> pkce.scope(Client.Scope.board_play));

                if (! (authResult instanceof Client.AuthOk ok)) yield Opt.empty();

                var auth = ok.client();
                auth.store(prefs());
                yield Opt.of(auth);
            }
            case String token -> Opt.of(Client.auth(c -> c.api(lichessApi), token));
        };
    }

    static Preferences prefs() {
        return Preferences.userRoot().node("jc");
    }



    default void startSeek() {}
    default void stop() {};
    static Play dummy = new Play(){};

}

package jc.app;

import java.time.Duration;
import java.util.concurrent.*;
import java.util.function.*;
import java.util.stream.*;

import chariot.Client;
import chariot.model.GameUser;
import chariot.model.LightUser;
import chariot.model.MoveInfo;
import chariot.model.TVChannels;
import chariot.model.TVFeedEvent;
import chariot.model.Enums.Color;
import chariot.model.TVFeedEvent.*;
import chariot.util.Board;

public interface TVFeed {

    static Client client = Client.basic();

    void stop();

    static TVFeed featuredGame(Consumer<String> consumer) {
        BlockingQueue<JCEvent> eventQueue = new ArrayBlockingQueue<>(1024);
        var stream = client.games().tvFeed().stream();

        Runnable runnable = () -> stream
                .forEach(tvFeedEvent -> {
                    switch(tvFeedEvent.d()) {
                        case Featured(String id, Color orientation, var players, String fen) -> {
                            record PlayerColors(PlayerInfo white, PlayerInfo black) {}
                            var playerColors = switch(players.get(0).color()) {
                                case white -> new PlayerColors(players.get(0), players.get(1));
                                case black -> new PlayerColors(players.get(1), players.get(0));
                            };
                            Board board = Board.fromFEN(fen);
                            eventQueue.offer(new JCNewGame(playerColors.white, playerColors.black, board, orientation != Color.white));
                        }
                        case Fen(String fen, String lm, Integer wc, Integer bc) ->
                            eventQueue.offer(new JCBoardUpdate(Board.fromFEN(fen), wc, bc));
                    }
                });

        return watch(consumer, stream, runnable, eventQueue);
    }

    static TVFeed classicalGame(Consumer<String> consumer) {
        TVChannels tvchannels = client.games().tvChannels().get();
        TVChannels.TVChannel classical = tvchannels.classical();
        String gameId = classical.gameId();

        return gameId(gameId, consumer);
    }

    static TVFeed rapidGame(Consumer<String> consumer) {
        TVChannels tvchannels = client.games().tvChannels().get();
        TVChannels.TVChannel rapid = tvchannels.rapid();
        String gameId = rapid.gameId();

        return gameId(gameId, consumer);
    }

    static TVFeed blitzGame(Consumer<String> consumer) {
        TVChannels tvchannels = client.games().tvChannels().get();
        TVChannels.TVChannel blitz = tvchannels.blitz();
        String gameId = blitz.gameId();

        return gameId(gameId, consumer);
    }


    static TVFeed gameId(String gameId, Consumer<String> consumer) {
        BlockingQueue<JCEvent> eventQueue = new ArrayBlockingQueue<>(1024);
        var game = client.games().byGameId(gameId).get();

        var stream = client.games().moveInfosByGameId(gameId).stream();

        Runnable runnable = () -> stream
                .forEach(moveInfo -> {
                    switch(moveInfo) {
                        case MoveInfo.GameSummary summary -> {
                            var white = switch(game.players().white()) {
                                case GameUser.User user -> new PlayerInfo(user.user(), Color.white, user.rating(), 0);
                                default -> new PlayerInfo(new LightUser("", game.players().white().name(), "", false), Color.white, 0, 0);
                            };
                            var black = switch(game.players().black()) {
                                case GameUser.User user -> new PlayerInfo(user.user(), Color.black, user.rating(), 0);
                                default -> new PlayerInfo(new LightUser("", game.players().black().name(), "", false), Color.black, 0, 0);
                            };

                            var board = Board.fromFEN(summary.fen());
                            eventQueue.offer(new JCNewGame(white, black, board, false));
                        }
                        case MoveInfo.Move move -> eventQueue.offer(new JCBoardUpdate(Board.fromFEN(move.fen()), move.wc(), move.bc()));
                    }
                });

        return watch(consumer, stream, runnable, eventQueue);
    }

    private static TVFeed watch(Consumer<String> consumer, Stream<?> stream, Runnable runnable, BlockingQueue<JCEvent> eventQueue) {
        ExecutorService executorService = Executors.newFixedThreadPool(2);
        ScheduledExecutorService timeTickerExecutor = Executors.newSingleThreadScheduledExecutor();
        executorService.submit(runnable);

        timeTickerExecutor.scheduleAtFixedRate(
                () -> eventQueue.offer(new JCTimeTick()),
                0, 1, TimeUnit.SECONDS);

        executorService.submit(() -> {
            State currentState = null;
            while(true) {
                final JCEvent event;
                try {
                    event = eventQueue.take();
                } catch(InterruptedException ie) {
                    // Ok, let's exit
                    break;
                }

                currentState = switch(event) {
                    case JCNewGame(var white, var black, var board, var flipped) -> new State(white, black, board, flipped);
                    case JCBoardUpdate(Board board, int whiteSeconds, int blackSeconds) -> currentState != null ?
                        currentState.withBoard(board)
                        .withWhiteSeconds(whiteSeconds)
                        .withBlackSeconds(blackSeconds) :
                        currentState;
                    case JCTimeTick() -> (currentState != null && !currentState.board().ended()) ?
                        currentState.board().whiteToMove() ?
                        currentState.withWhiteSeconds(currentState.white().syntheticSeconds()-1) :
                        currentState.withBlackSeconds(currentState.black().syntheticSeconds()-1) :
                        currentState;
                };

                if (currentState != null) {
                    consumer.accept(render(currentState));
                }
            }
        });
        return new Feed(executorService, timeTickerExecutor, stream);
    }

    record Feed(
            ExecutorService executorService,
            ScheduledExecutorService timeTickerExecutor,
            Stream<?> stream) implements TVFeed {
        @Override
        public void stop() {
            stream.close();
            if (!timeTickerExecutor.isShutdown()) timeTickerExecutor.shutdownNow();
            if (!executorService.isShutdown()) executorService.shutdownNow();
        }
    }

    sealed interface JCEvent {}

    record JCNewGame(PlayerInfo white, PlayerInfo black, Board board, boolean flipped) implements JCEvent {};
    record JCBoardUpdate(Board board, int whiteSeconds, int blackSeconds) implements JCEvent {};
    record JCTimeTick() implements JCEvent {};

    record JCPlayerAndClock(PlayerInfo info, int syntheticSeconds) {
        public JCPlayerAndClock withSeconds(int syntheticSeconds) { return new JCPlayerAndClock(info, syntheticSeconds); }
    }

    record State(JCPlayerAndClock white, JCPlayerAndClock black, Board board, boolean flipped) {
        public State(PlayerInfo infoWhite, PlayerInfo infoBlack, Board board, boolean flipped) {
            this(new JCPlayerAndClock(infoWhite, infoWhite.seconds()),
                 new JCPlayerAndClock(infoBlack, infoBlack.seconds()),
                 board,
                 flipped);
        }

        public State withWhiteSeconds(int seconds) { return new State(white.withSeconds(seconds), black, board, flipped); }
        public State withBlackSeconds(int seconds) { return new State(white, black.withSeconds(seconds), board, flipped); }

        public State withWhite(PlayerInfo white) { return new State(new JCPlayerAndClock(white, white.seconds()), black, board, flipped); }
        public State withBlack(PlayerInfo black) { return new State(white, new JCPlayerAndClock(black, black.seconds()), board, flipped); }
        public State withBoard(Board board) { return new State(white, black, board, flipped); }
    }


    static String render(State state) {

        var upperPlayer = state.flipped() ? state.white() : state.black();
        var lowerPlayer = state.flipped() ? state.black() : state.white();
        String upperTitle = upperPlayer.info().user().title().isEmpty() ? "" : upperPlayer.info().user().title() + " ";
        String upperName =  upperPlayer.info().user().name();
        String upperClock = formatSeconds(upperPlayer.syntheticSeconds());
        String upperToMove = (state.flipped() && state.board().whiteToMove()) ||
            (!state.flipped() && state.board().blackToMove()) ? "*" : "";
        String board = state.flipped() ? state.board().toString(c -> c.frame().flipped()) :
            state.board().toString(c -> c.frame());
        String lowerToMove = (state.flipped() && state.board().blackToMove()) ||
            (!state.flipped() && state.board().whiteToMove()) ? "*" : "";
        String lowerClock = formatSeconds(lowerPlayer.syntheticSeconds());
        String lowerTitle = lowerPlayer.info().user().title().isEmpty() ? "" : lowerPlayer.info().user().title() + " ";
        String lowerName = lowerPlayer.info().user().name();

        String rendered = """
            %s%s
            %s %s
            %s
            %s %s
            %s%s
            """.formatted(
                    upperTitle, upperName,
                    upperClock, upperToMove,
                    board,
                    lowerClock, lowerToMove,
                    lowerTitle, lowerName
                    );
        return rendered;
    }

    static String formatSeconds(int seconds) {
        Duration duration = Duration.ofSeconds(seconds);
        return String.format("%d:%02d:%02d", duration.toHoursPart(), duration.toMinutesPart(), duration.toSecondsPart());
    }
}

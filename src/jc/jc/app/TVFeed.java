package jc.app;

import java.time.Duration;
import java.util.concurrent.*;
import java.util.function.*;
import java.util.stream.*;

import chariot.Client;
import chariot.model.Enums.Color;
import chariot.model.TVFeedEvent.*;
import chariot.util.Board;

public interface TVFeed {

    void stop();

    static TVFeed startConsole(Consumer<String> consumer) {
        BlockingQueue<JCEvent> eventQueue = new ArrayBlockingQueue<>(1024);
        ExecutorService executorService = Executors.newFixedThreadPool(2);
        ScheduledExecutorService timeTickerExecutor = Executors.newSingleThreadScheduledExecutor();
        var stream = Client.basic().games().tvFeed().stream();
        executorService.submit(() -> stream
                .forEach(tvFeedEvent -> {
                    switch(tvFeedEvent.d()) {
                        case Featured featured -> {
                            PlayerInfo p1 = featured.players().get(0);
                            PlayerInfo p2 = featured.players().get(1);
                            PlayerInfo white = p1.color() == Color.white ? p1 : p2;
                            PlayerInfo black = white == p1 ? p2 : p1;
                            Board board = Board.fromFEN(featured.fen());
                            eventQueue.offer(new JCNewGame(white, black, board, featured.orientation() != Color.white));
                        }
                        case Fen fen -> eventQueue.offer(new JCBoardUpdate(Board.fromFEN(fen.fen()), fen.wc(), fen.bc()));
                    }
                }));

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
                    case JCNewGame game     -> new State(game.white(), game.black(), game.board(), game.flipped());
                    case JCBoardUpdate move -> currentState != null ?
                        currentState.withBoard(move.board())
                        .withWhiteSeconds(move.whiteSeconds())
                        .withBlackSeconds(move.blackSeconds()) :
                        currentState;
                    case JCTimeTick tick    -> (currentState != null && !currentState.board().ended()) ?
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

package jc.app;

import jc.model.JCState;
import jc.model.JCState.JCPlayerInfo;
import jc.model.JCState.JCUser;

import java.util.concurrent.*;
import java.util.function.*;
import java.util.stream.*;

import chariot.Client;
import chariot.model.*;
import chariot.model.Enums.Color;
import chariot.model.TVFeedEvent.*;
import chariot.util.Board;

public interface TVFeed {

    static Client client = Client.basic();

    void stop();

    static TVFeed featuredGame(Consumer<String> consumer) {
        BlockingQueue<FeedEvent> eventQueue = new ArrayBlockingQueue<>(1024);
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
                            eventQueue.offer(new JCNewGame(fromPlayerInfo(playerColors.white), fromPlayerInfo(playerColors.black), board, orientation != Color.white));
                        }
                        case Fen(String fen, String lm, Integer wc, Integer bc) ->
                            eventQueue.offer(new JCBoardUpdate(Board.fromFEN(fen), wc, bc));
                    }
                });

        return watch(consumer, stream, runnable, eventQueue);
    }

    private static JCPlayerInfo fromPlayerInfo(PlayerInfo playerInfo) {
        JCUser user = new JCUser(playerInfo.user().name(), playerInfo.user().title());
        return new JCPlayerInfo(user, playerInfo.seconds());
    }

    static TVFeed classicalGame(Consumer<String> consumer) {
        String gameId = client.games().tvChannels().get().classical().gameId();
        return gameId(gameId, consumer);
    }

    static TVFeed rapidGame(Consumer<String> consumer) {
        String gameId = client.games().tvChannels().get().rapid().gameId();
        return gameId(gameId, consumer);
    }

    static TVFeed blitzGame(Consumer<String> consumer) {
        String gameId = client.games().tvChannels().get().blitz().gameId();
        return gameId(gameId, consumer);
    }

    static TVFeed gameId(String gameId, Consumer<String> consumer) {
        BlockingQueue<FeedEvent> eventQueue = new ArrayBlockingQueue<>(1024);
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
                            eventQueue.offer(new JCNewGame(fromPlayerInfo(white), fromPlayerInfo(black), board, false));
                        }
                        case MoveInfo.Move move -> eventQueue.offer(new JCBoardUpdate(Board.fromFEN(move.fen()), move.wc(), move.bc()));
                    }
                });

        return watch(consumer, stream, runnable, eventQueue);
    }

    private static TVFeed watch(Consumer<String> consumer, Stream<?> stream, Runnable runnable, BlockingQueue<FeedEvent> eventQueue) {
        ExecutorService executorService = Executors.newFixedThreadPool(2);
        ScheduledExecutorService timeTickerExecutor = Executors.newSingleThreadScheduledExecutor();
        executorService.submit(runnable);

        timeTickerExecutor.scheduleAtFixedRate(
                () -> eventQueue.offer(new JCTimeTick()),
                0, 1, TimeUnit.SECONDS);

        executorService.submit(() -> {
            JCState currentState = null;
            while(true) {
                final FeedEvent event;
                try {
                    event = eventQueue.take();
                } catch(InterruptedException ie) {
                    // Ok, let's exit
                    break;
                }

                currentState = switch(event) {
                    case JCNewGame(var white, var black, var board, var flipped) -> new JCState(white, black, board, flipped);
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
                    consumer.accept(JCState.render(currentState));
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

    sealed interface FeedEvent {}

    record JCNewGame(JCPlayerInfo white, JCPlayerInfo black, Board board, boolean flipped) implements FeedEvent {};
    record JCBoardUpdate(Board board, int whiteSeconds, int blackSeconds) implements FeedEvent {};
    record JCTimeTick() implements FeedEvent {};



}

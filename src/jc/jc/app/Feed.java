package jc.app;

import jc.model.JCState;
import jc.model.JCState.*;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.*;
import java.util.stream.*;

import chariot.Client;
import chariot.model.*;
import chariot.model.MoveInfo.*;
import chariot.model.TVFeedEvent.*;
import chariot.util.Board;
import chariot.model.Enums.Color;
import static chariot.model.Enums.Color.white;
import static chariot.model.Enums.Color.black;

public interface Feed {

    static Client client = Client.basic();

    static Feed featuredGame(Consumer<String> consumer) {
        // Eternal stream of games
        Stream<FeedEvent> streamFromFeed = client.games().tvFeed().stream()
            .map(tvFeedEvent -> switch(tvFeedEvent.d()) {
                case Fen(String fen, var lm, var wc, var bc)
                    -> new JCBoardUpdate(Board.fromFEN(fen), wc, bc);
                case Featured(var id, Color orientation, var players, String fen)
                    -> new JCNewGame(players.stream().map(Feed::fromPlayerInfo).toList(),
                            Board.fromFEN(fen),
                            orientation != white);
            });

        return watch(consumer, streamFromFeed);
    }

    static Feed classical(Consumer<String> consumer) { return resubscribingGameId(() -> tvChannels().classical().gameId(), consumer); }
    static Feed rapid(Consumer<String> consumer)     { return resubscribingGameId(() -> tvChannels().rapid().gameId(), consumer); }
    static Feed blitz(Consumer<String> consumer)     { return resubscribingGameId(() -> tvChannels().blitz().gameId(), consumer); }

    static Feed gameId(String gameId, Consumer<String> consumer) {
        return watch(consumer, streamFromGameId(gameId));
    }

    private static Stream<FeedEvent> streamFromGameId(String gameId) {
        var game = client.games().byGameId(gameId).get();
        // Single game stream
        Stream<FeedEvent> streamFromGameId = client.games().moveInfosByGameId(gameId).stream()
            .map(moveInfo -> switch(moveInfo) {
                case Move(String fen, var lm, int wc, int bc)
                    -> new JCBoardUpdate(Board.fromFEN(fen), wc, bc);
                case GameSummary summary
                    -> new JCNewGame(fromGameUser(white, game.players().white()), fromGameUser(black, game.players().black()),
                            Board.fromFEN(summary.fen()),
                            false);
            });

        return streamFromGameId;
    }

    static private Feed resubscribingGameId(Supplier<String> gameIdProvider, Consumer<String> consumer) {
        // Synthetic eternal stream of games
        Stream<FeedEvent> resubscribingStream = StreamSupport.stream(new FeedEventSpliterator(gameIdProvider), false);
        return watch(consumer, resubscribingStream);
    }

    private static Feed watch(Consumer<String> consumer, Stream<FeedEvent> stream) {
        BlockingQueue<FeedEvent> eventQueue = new ArrayBlockingQueue<>(1024);
        ExecutorService executorService = Executors.newFixedThreadPool(2);
        ScheduledExecutorService timeTickerExecutor = Executors.newSingleThreadScheduledExecutor();
        executorService.submit(() -> stream.forEach(eventQueue::offer));

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
        return new FeedHandle(executorService, timeTickerExecutor, stream);
    }

    record FeedHandle(
            ExecutorService executorService,
            ScheduledExecutorService timeTickerExecutor,
            Stream<FeedEvent> stream) implements Feed {
        @Override
        public void stop() {
            stream.close();
            if (!timeTickerExecutor.isShutdown()) timeTickerExecutor.shutdownNow();
            if (!executorService.isShutdown()) executorService.shutdownNow();
        }
    }

    class FeedEventSpliterator implements Spliterator<FeedEvent> {
        final Supplier<String> gameIdProvider;
        Iterator<FeedEvent> iterator = Stream.<FeedEvent>of().iterator();

        FeedEventSpliterator(Supplier<String> gameIdProvider) {
            this.gameIdProvider = gameIdProvider;
        }

        @Override
        public boolean tryAdvance(Consumer<? super FeedEvent> action) {
            if (iterator.hasNext()) {
                action.accept(iterator.next());
            } else {
                String newGameId = gameIdProvider.get();
                var gameOngoingSignal = new AtomicBoolean(true);
                Thread.ofPlatform().name("game-over-listener").start(() -> {
                    client.games().gameInfosByGameIds("jc", newGameId).stream()
                        .takeWhile(gameInfo -> gameInfo.status() <= 20)
                        .forEach(__ -> {});
                    gameOngoingSignal.set(false);
                });

                iterator = streamFromGameId(newGameId)
                    .takeWhile(__ -> gameOngoingSignal.get())
                    .iterator();
            }
            return true;
        }

        @Override public Spliterator<FeedEvent> trySplit() { return null; }
        @Override public long estimateSize() { return Long.MAX_VALUE; }
        @Override public int characteristics() { return ORDERED; }
    }

    void stop();

    sealed interface FeedEvent {}

    record PlayerColors(JCPlayerInfo white, JCPlayerInfo black) {}

    record JCNewGame(JCPlayerInfo white, JCPlayerInfo black, Board board, boolean flipped) implements FeedEvent {
        JCNewGame(List<JCPlayerInfo> players, Board board, boolean flipped) {
            this(switch(players.get(0).color()) {
                case white -> new PlayerColors(players.get(0), players.get(1));
                case black -> new PlayerColors(players.get(1), players.get(0));
            }, board, flipped);
        }

        JCNewGame(PlayerColors playerColors, Board board, boolean flipped) {
            this(playerColors.white, playerColors.black, board, flipped);
        }
    };
    record JCBoardUpdate(Board board, int whiteSeconds, int blackSeconds) implements FeedEvent {};
    record JCTimeTick() implements FeedEvent {};

    private static TVChannels tvChannels() {
        return client.games().tvChannels().get();
    }

    private static JCPlayerInfo fromPlayerInfo(PlayerInfo playerInfo) {
        JCUser user = new JCUser(playerInfo.user().name(), playerInfo.user().title());
        return new JCPlayerInfo(user, playerInfo.seconds(), playerInfo.color());
    }

    private static JCPlayerInfo fromGameUser(Color color, GameUser gameUser) {
        return fromPlayerInfo(switch(gameUser) {
            case GameUser.User user -> new PlayerInfo(user.user(), color, user.rating(), 0);
            default -> new PlayerInfo(new LightUser("", gameUser.name(), "", false), color, 0, 0);
        });
    }

}

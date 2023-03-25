package jc.app;

import jc.model.JCState;
import jc.model.JCState.*;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.*;
import java.util.stream.*;

import chariot.Client;
import chariot.model.*;
import chariot.model.MoveInfo.*;
import chariot.model.TVChannels.TVChannel;
import chariot.model.TVFeedEvent.*;
import chariot.util.Board;
import chariot.model.Enums.Color;

public interface Feed {

    static Client client = Client.basic();

    static Feed featuredGame(Consumer<JCState> consumer) {
        // Eternal stream of games
        Stream<FeedEvent> streamFromFeed = client.games().tvFeed().stream()
            .map(tvFeedEvent -> switch(tvFeedEvent.d()) {
                case Fen(String fen, var lm, var wc, var bc)
                    -> new JCBoardUpdate(Board.fromFEN(fen), wc, bc, lm);
                case Featured(var id, Color orientation, var players, String fen)
                    -> new JCNewGame(players.stream().map(Feed::fromPlayerInfo).toList(),
                            Board.fromFEN(fen),
                            orientation != Color.white);
            });

        return watch(consumer, streamFromFeed);
    }

    static Feed gameId(String gameId, Consumer<JCState> consumer) {
        return watch(consumer, streamFromGameId(gameId, ""));
    }

    private static Stream<FeedEvent> streamFromGameId(String gameId, String userId) {
        // Single game stream
        Stream<FeedEvent> streamFromGameId = client.games().moveInfosByGameId(gameId).stream()
            .map(moveInfo -> switch(moveInfo) {
                case Move(String fen, var lm, int wc, int bc)
                    -> new JCBoardUpdate(Board.fromFEN(fen), wc, bc, lm);
                case GameSummary game
                    -> new JCNewGame(fromPlayer(Color.white, game.players().white()), fromPlayer(Color.black, game.players().black()),
                            Board.fromFEN(game.fen()),
                            game.players().black().name().toLowerCase().equals(userId));
            });

        return streamFromGameId;
    }

    record GameAndUser(String gameId, String userId) {
        public GameAndUser(TVChannel channel) {
            this(channel.gameId(), channel.user().id());
        }
    }

    static Feed classical(Consumer<JCState> consumer) { return resubscribingGameId(() -> new GameAndUser(tvChannels().classical()), consumer); }
    static Feed rapid(Consumer<JCState> consumer)     { return resubscribingGameId(() -> new GameAndUser(tvChannels().rapid()), consumer); }
    static Feed blitz(Consumer<JCState> consumer)     { return resubscribingGameId(() -> new GameAndUser(tvChannels().blitz()), consumer); }

    private static Feed resubscribingGameId(Supplier<GameAndUser> gameIdProvider, Consumer<JCState> consumer) {
        // Synthetic eternal stream of games
        Stream<FeedEvent> resubscribingStream = StreamSupport.stream(new FeedEventSpliterator(gameIdProvider), false);
        return watch(consumer, resubscribingStream);
    }

    private static Feed watch(Consumer<JCState> consumer, Stream<FeedEvent> stream) {
        BlockingQueue<FeedEvent> eventQueue = new ArrayBlockingQueue<>(1024);
        ExecutorService executorService = Executors.newFixedThreadPool(2);
        ScheduledExecutorService timeTickerExecutor = Executors.newSingleThreadScheduledExecutor();
        executorService.submit(() -> stream.forEach(eventQueue::offer));

        timeTickerExecutor.scheduleAtFixedRate(
                () -> eventQueue.offer(new JCTimeTick()),
                0, 1, TimeUnit.SECONDS);

        executorService.submit(() -> {
            JCState currentState = new JCState.None();
            while(true) {
                final FeedEvent event;
                try {
                    event = eventQueue.take();
                } catch(InterruptedException ie) {
                    // Ok, let's exit
                    break;
                }

                currentState = switch(event) {
                    case JCNewGame(var white, var black, var board, var flipped) -> JCState.of(white, black, board, flipped);
                    case JCBoardUpdate(Board board, int whiteSeconds, int blackSeconds, var lm) -> currentState
                        .withBoard(board)
                        .withWhiteSeconds(whiteSeconds)
                        .withBlackSeconds(blackSeconds)
                        .withLastMove(lm);
                    case JCTimeTick() -> currentState.withOneSecondTick();
                };

                consumer.accept(currentState);
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
        final Supplier<GameAndUser> gameIdProvider;
        Instant previousQuery = null;
        Iterator<FeedEvent> iterator = Stream.<FeedEvent>of().iterator();

        FeedEventSpliterator(Supplier<GameAndUser> gameIdProvider) {
            this.gameIdProvider = gameIdProvider;
        }

        @Override
        public boolean tryAdvance(Consumer<? super FeedEvent> action) {
            if (iterator.hasNext()) {
                action.accept(iterator.next());
            } else {
                if (previousQuery != null && Duration.between(previousQuery, Instant.now()).toSeconds() < 10) {
                    try {TimeUnit.SECONDS.sleep(10); } catch (InterruptedException ie) {}
                }
                var gameAndUser = gameIdProvider.get();
                previousQuery = Instant.now();
                var gameOngoingSignal = new AtomicBoolean(true);
                Thread.ofPlatform().name("game-over-listener").start(() -> {
                    var result = client.games().gameInfosByGameIds("jc", gameAndUser.gameId());
                    var stream = result.stream();
                    stream.takeWhile(gameInfo -> gameInfo.status() <= 20).forEach(__ -> {});
                    stream.close();
                    gameOngoingSignal.set(false);
                });

                iterator = streamFromGameId(gameAndUser.gameId(), gameAndUser.userId())
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
    record JCBoardUpdate(Board board, int whiteSeconds, int blackSeconds, String lm) implements FeedEvent {};
    record JCTimeTick() implements FeedEvent {};

    private static TVChannels tvChannels() {
        return client.games().tvChannels().get();
    }

    private static JCPlayerInfo fromPlayerInfo(PlayerInfo playerInfo) {
        JCUser user = new JCUser(playerInfo.user().name(), playerInfo.user().title());
        return new JCPlayerInfo(user, playerInfo.seconds(), playerInfo.color());
    }

    private static JCPlayerInfo fromPlayer(Color color, Player player) {
        return fromPlayerInfo(switch(player) {
            case Player.User user -> new PlayerInfo(user.user(), color, user.rating(), 0);
            default -> new PlayerInfo(new LightUser("", player.name(), "", false), color, 0, 0);
        });
    }
}

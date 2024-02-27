package jc.app;

import jc.model.JCState;
import jc.model.JCState.*;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;
import java.util.stream.*;

import chariot.Client;
import chariot.model.*;
import chariot.model.MoveInfo.*;
import chariot.model.TVFeedEvent.*;
import chariot.util.Board;
import chariot.model.Enums.Color;

public interface Feed {

    static Client client = Client.basic(c -> c.api("http://localhost:8080"));

    static Feed featuredGame(Consumer<JCState> consumer) {
        return featuredGame(consumer, client.games().tvFeed().stream());
    }

    static Feed featuredGame(Consumer<JCState> consumer, Enums.Channel channel) {
        return featuredGame(consumer, client.games().tvFeed(channel).stream());
    }

    static Feed featuredGame(Consumer<JCState> consumer, Stream<TVFeedEvent> tvFeed) {
        // Eternal stream of games
        Stream<FeedEvent> streamFromFeed = tvFeed
            .map(tvFeedEvent -> switch(tvFeedEvent) {
                case Fen(String fen, var lm, var wc, var bc)
                    -> new JCBoardUpdate(Board.fromFEN(fen), wc, bc, lm);
                case Featured(var __, Color orientation, var players, String fen)
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
                    -> new JCBoardUpdate(Board.fromFEN(fen), Duration.ofSeconds(wc), Duration.ofSeconds(bc), lm);
                case GameSummary game
                    -> new JCNewGame(fromPlayer(Color.white, game.players().white()), fromPlayer(Color.black, game.players().black()),
                            Board.fromFEN(game.fen()),
                            game.players().black().name().toLowerCase().equals(userId));
            });

        return streamFromGameId;
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
                    case JCBoardUpdate(Board board, Duration whiteTime, Duration blackTime, var lm) -> currentState
                        .withBoard(board)
                        .withWhiteTime(whiteTime)
                        .withBlackTime(blackTime)
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
    record JCBoardUpdate(Board board, Duration whiteTime, Duration blackTime, String lm) implements FeedEvent {};
    record JCTimeTick() implements FeedEvent {};

    private static JCPlayerInfo fromPlayerInfo(PlayerInfo playerInfo) {
        JCUser user = new JCUser(playerInfo.user().name(), playerInfo.user().titleOpt().orElse(""));
        return new JCPlayerInfo(user, playerInfo.time(), playerInfo.color());
    }

    private static JCPlayerInfo fromPlayer(Color color, Player player) {
        return fromPlayerInfo(switch(player) {
            case Player.Account(var user, var rating, var __, var ___, var ____) ->
                new PlayerInfo(UserInfo.of(
                            user.id(),
                            user.name(),
                            user.title().orElse(null)),
                        color, rating, Duration.ZERO);
            default ->
                new PlayerInfo(
                        UserInfo.of("", player.name()),
                        color, 0, Duration.ZERO);
        });
    }
}

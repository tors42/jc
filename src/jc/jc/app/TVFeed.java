package jc.app;

import jc.model.JCState;
import jc.model.JCState.*;

import java.util.List;
import java.util.concurrent.*;
import java.util.function.*;
import java.util.stream.*;

import chariot.Client;
import chariot.model.*;
import chariot.model.Enums.Color;
import chariot.model.MoveInfo.*;
import chariot.model.TVFeedEvent.*;
import chariot.util.Board;

public interface TVFeed {

    static Client client = Client.basic();

    static TVFeed featuredGame(Consumer<String> consumer) {
        Stream<FeedEvent> streamFromFeed = client.games().tvFeed().stream()
            .map(tvFeedEvent -> switch(tvFeedEvent.d()) {
                case Fen(String fen, var lm, var wc, var bc)
                    -> new JCBoardUpdate(Board.fromFEN(fen), wc, bc);
                case Featured(var id, Color orientation, var players, String fen)
                    -> new JCNewGame(players.stream().map(TVFeed::fromPlayerInfo).toList(), Board.fromFEN(fen), orientation != Color.white);
            });

        return watch(consumer, streamFromFeed);
    }

    static TVFeed classical(Consumer<String> consumer) { return gameId(client.games().tvChannels().get().classical().gameId(), consumer); }
    static TVFeed rapid(Consumer<String> consumer)     { return gameId(client.games().tvChannels().get().rapid().gameId(),     consumer); }
    static TVFeed blitz(Consumer<String> consumer)     { return gameId(client.games().tvChannels().get().blitz().gameId(),     consumer); }

    static TVFeed gameId(String gameId, Consumer<String> consumer) {
        var game = client.games().byGameId(gameId).get();
        Stream<FeedEvent> streamFromGameId = client.games().moveInfosByGameId(gameId).stream()
            .map(moveInfo -> switch(moveInfo) {
                case Move(String fen, var lm, int wc, int bc)
                    -> new JCBoardUpdate(Board.fromFEN(fen), wc, bc);
                case GameSummary summary
                    -> new JCNewGame(fromGameUser(Color.white, game.players().white()), fromGameUser(Color.black, game.players().black()), Board.fromFEN(summary.fen()), false);
            });

        return watch(consumer, streamFromGameId);
    }

    private static TVFeed watch(Consumer<String> consumer, Stream<FeedEvent> stream) {
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

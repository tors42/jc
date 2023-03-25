package jc.model;

import java.time.Duration;

import chariot.model.Enums.Color;
import chariot.util.Board;

public sealed interface JCState {

    record None() implements JCState {}

    default JCState withBoard(Board board) {
        return switch(this) {
            case None n -> n;
            case Basic b -> b.withBoard(board);
            case WithMove(Basic b, var lm) -> new WithMove(b.withBoard(board), lm);
        };
    }

    default JCState withWhiteSeconds(int seconds) {
        return switch(this) {
            case None n -> n;
            case Basic b -> b.withWhiteSeconds(seconds);
            case WithMove(Basic b, var lm) -> new WithMove(b.withWhiteSeconds(seconds), lm);
        };
    }

    default JCState withBlackSeconds(int seconds) {
        return switch(this) {
            case None n -> n;
            case Basic b -> b.withBlackSeconds(seconds);
            case WithMove(Basic b, var lm) -> new WithMove(b.withBlackSeconds(seconds), lm);
        };
    }

    default JCState withLastMove(String lm) {
        return switch(this) {
            case None n -> n;
            case Basic b -> new WithMove(b, lm);
            case WithMove wm -> new WithMove(wm.basic, lm);
        };
    }

    default JCState withOneSecondTick() {
        return switch(this) {
            case None n -> n;
            case Basic b -> b.board().whiteToMove()
                ? b.withWhiteSeconds(b.white.syntheticSeconds - 1)
                : b.withBlackSeconds(b.black.syntheticSeconds - 1);
            case WithMove wm -> new WithMove(wm.basic.board.whiteToMove()
                    ? wm.basic().withWhiteSeconds(wm.basic.white.syntheticSeconds - 1)
                    : wm.basic().withBlackSeconds(wm.basic.black.syntheticSeconds - 1),
                    wm.lm);
        };
    }


    record Basic(JCPlayerAndClock white, JCPlayerAndClock black, Board board, boolean flipped) implements JCState {
        public Basic withWhiteSeconds(int seconds) { return new Basic(white.withSeconds(seconds), black, board, flipped); }
        public Basic withBlackSeconds(int seconds) { return new Basic(white, black.withSeconds(seconds), board, flipped); }
        public Basic withBoard(Board board)        { return new Basic(white, black, board, flipped); }
    }

    record WithMove(Basic basic, String lm) implements JCState {}

    static JCState of(JCPlayerInfo white, JCPlayerInfo black, Board board, boolean flipped) {
        return new Basic(
                new JCPlayerAndClock(white, white.seconds()),
                new JCPlayerAndClock(black, black.seconds()),
                board,
                flipped);
    }

    public record JCUser(String name, String title) {}
    public record JCPlayerInfo(JCUser user, Integer seconds, Color color) {}

    public record JCPlayerAndClock(JCPlayerInfo info, int syntheticSeconds) {
        public JCPlayerAndClock withSeconds(int syntheticSeconds) { return new JCPlayerAndClock(info, syntheticSeconds); }
    }

    public static String render(JCState jcstate) {
        Basic state = switch(jcstate) {
            case None n -> null;
            case Basic b -> b;
            case WithMove wm -> wm.basic();
        };
        if (state == null) return "";

        var upperPlayer = state.flipped() ? state.white() : state.black();
        var lowerPlayer = state.flipped() ? state.black() : state.white();
        String upperTitle = upperPlayer.info().user().title().isEmpty() ? "" : upperPlayer.info().user().title() + " ";
        String upperName =  upperPlayer.info().user().name();
        String upperClock = formatSeconds(upperPlayer.syntheticSeconds());
        String upperToMove = (state.flipped() && state.board().whiteToMove()) ||
            (!state.flipped() && state.board().blackToMove()) ? "*" : "";
        String board = state.flipped() ? state.board().toString(c -> c.frame().flipped().coordinates()) :
            state.board().toString(c -> c.frame().coordinates());
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

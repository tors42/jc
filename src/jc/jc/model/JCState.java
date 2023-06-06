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

    default JCState withWhiteTime(Duration time) {
        return switch(this) {
            case None n -> n;
            case Basic b -> b.withWhiteTime(time);
            case WithMove(Basic b, var lm) -> new WithMove(b.withWhiteTime(time), lm);
        };
    }

    default JCState withBlackTime(Duration time) {
        return switch(this) {
            case None n -> n;
            case Basic b -> b.withBlackTime(time);
            case WithMove(Basic b, var lm) -> new WithMove(b.withBlackTime(time), lm);
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
                ? b.withWhiteTime(b.white.syntheticTime.minusSeconds(1))
                : b.withBlackTime(b.black.syntheticTime.minusSeconds(1));
            case WithMove wm -> new WithMove(wm.basic.board.whiteToMove()
                    ? wm.basic().withWhiteTime(wm.basic.white.syntheticTime.minusSeconds(1))
                    : wm.basic().withBlackTime(wm.basic.black.syntheticTime.minusSeconds(1)),
                    wm.lm);
        };
    }


    record Basic(JCPlayerAndClock white, JCPlayerAndClock black, Board board, boolean flipped) implements JCState {
        public Basic withWhiteTime(Duration time) { return new Basic(white.withTime(time), black, board, flipped); }
        public Basic withBlackTime(Duration time) { return new Basic(white, black.withTime(time), board, flipped); }
        public Basic withBoard(Board board)        { return new Basic(white, black, board, flipped); }
    }

    record WithMove(Basic basic, String lm) implements JCState {}

    static JCState of(JCPlayerInfo white, JCPlayerInfo black, Board board, boolean flipped) {
        return new Basic(
                new JCPlayerAndClock(white, white.time()),
                new JCPlayerAndClock(black, black.time()),
                board,
                flipped);
    }

    public record JCUser(String name, String title) {}
    public record JCPlayerInfo(JCUser user, Duration time, Color color) {}

    public record JCPlayerAndClock(JCPlayerInfo info, Duration syntheticTime) {
        public JCPlayerAndClock withTime(Duration time) { return new JCPlayerAndClock(info, time); }
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
        String upperClock = formatTime(upperPlayer.syntheticTime());
        String upperToMove = (state.flipped() && state.board().whiteToMove()) ||
            (!state.flipped() && state.board().blackToMove()) ? "*" : "";
        String board = state.flipped() ? state.board().toString(c -> c.frame().flipped().coordinates()) :
            state.board().toString(c -> c.frame().coordinates());
        String lowerToMove = (state.flipped() && state.board().blackToMove()) ||
            (!state.flipped() && state.board().whiteToMove()) ? "*" : "";
        String lowerClock = formatTime(lowerPlayer.syntheticTime());
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

    static String formatTime(Duration time) {
        return String.format("%d:%02d:%02d", time.toHoursPart(), time.toMinutesPart(), time.toSecondsPart());
    }

}

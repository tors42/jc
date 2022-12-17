package jc.model;

import java.time.Duration;

import chariot.util.Board;

public record JCState(JCPlayerAndClock white, JCPlayerAndClock black, Board board, boolean flipped) {
    public JCState(JCPlayerInfo infoWhite, JCPlayerInfo infoBlack, Board board, boolean flipped) {
        this(new JCPlayerAndClock(infoWhite, infoWhite.seconds()),
             new JCPlayerAndClock(infoBlack, infoBlack.seconds()),
             board,
             flipped);
    }

    public JCState withWhiteSeconds(int seconds) { return new JCState(white.withSeconds(seconds), black, board, flipped); }
    public JCState withBlackSeconds(int seconds) { return new JCState(white, black.withSeconds(seconds), board, flipped); }

    public record JCUser(String name, String title) {}
    public record JCPlayerInfo(JCUser user, Integer seconds) {}

    public JCState withWhite(JCPlayerInfo white) { return new JCState(new JCPlayerAndClock(white, white.seconds()), black, board, flipped); }
    public JCState withBlack(JCPlayerInfo black) { return new JCState(white, new JCPlayerAndClock(black, black.seconds()), board, flipped); }
    public JCState withBoard(Board board) { return new JCState(white, black, board, flipped); }

    public record JCPlayerAndClock(JCPlayerInfo info, int syntheticSeconds) {
        public JCPlayerAndClock withSeconds(int syntheticSeconds) { return new JCPlayerAndClock(info, syntheticSeconds); }
    }

    public static String render(JCState state) {

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

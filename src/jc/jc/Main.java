package jc;

import java.util.*;
import java.util.function.Consumer;

import jc.app.*;
import jc.model.JCState;

class Main {

    public static void main(String[] args) {
        var argsList = Arrays.stream(args).toList();
        boolean play = argsList.stream().anyMatch(s -> s.contains("play"));

        if (play) {
            play();
        } else {
            watch(argsList);
        }

    }

    static void watch(List<String> args) {
        Consumer<JCState> consumer = state -> System.out.println(JCState.render(state));

        Feed tvFeed = switch(args) {
            case List<String> l when l.isEmpty()             -> Feed.featuredGame(consumer);
            case List<String> l when l.contains("classical") -> Feed.classical(consumer);
            case List<String> l when l.contains("rapid")     -> Feed.rapid(consumer);
            case List<String> l when l.contains("blitz")     -> Feed.blitz(consumer);
            case List<String> l                              -> Feed.gameId(l.get(0), consumer);
        };

        System.console().readLine();
        tvFeed.stop();
    }

    static void play() {
        Play play = Play.casual15m10s();
        play.startSeek();

        System.console().readLine();
        play.stop();
    }

}

package jc;

import java.util.*;
import java.util.function.Consumer;

import chariot.model.Enums.Channel;
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

        Feed tvFeed = args.isEmpty()
            ? Feed.featuredGame(consumer)
            : switch(args.getFirst()) {
                case "classical" -> Feed.featuredGame(consumer, Channel.classical);
                case "rapid" -> Feed.featuredGame(consumer, Channel.rapid);
                case "blitz" -> Feed.featuredGame(consumer, Channel.blitz);
                case String gameId -> Feed.gameId(gameId, consumer);
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

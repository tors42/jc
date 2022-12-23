package jc;

import java.util.Arrays;

import jc.app.*;

class Main {

    public static void main(String[] args) {
        boolean play = Arrays.stream(args).anyMatch(s -> s.contains("play"));

        if (play) {
            play();
        } else {
            watch();
        }

    }

    static void watch() {
        //TVFeed tvFeed = TVFeed.gameId("IXVqbsOP", System.out::println);
        //TVFeed tvFeed = TVFeed.classical(System.out::println);
        //TVFeed tvFeed = TVFeed.rapid(System.out::println);
        //TVFeed tvFeed = TVFeed.blitz(System.out::println);
        TVFeed tvFeed = TVFeed.featuredGame(System.out::println);

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

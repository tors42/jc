package jc;

import java.util.Arrays;

import jc.app.*;

class Main {

    public static void main(String[] args) {
        boolean play = Arrays.stream(args).anyMatch(s -> s.contains("play"));

        if (play) {
            play();
        } else {
            System.out.println("java Main.java play");
            watch();
        }

    }

    static void watch() {
        //TVFeed tvFeed = TVFeed.gameId("IXVqbsOP", System.out::println);
        //TVFeed tvFeed = TVFeed.classicalGame(System.out::println);
        //TVFeed tvFeed = TVFeed.rapidGame(System.out::println);
        //TVFeed tvFeed = TVFeed.blitzGame(System.out::println);
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

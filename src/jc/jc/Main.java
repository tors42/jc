package jc;

import jc.app.TVFeed;

class Main {

    public static void main(String[] args) {

        //TVFeed tvFeed = TVFeed.gameId("IXVqbsOP", System.out::println);
        //TVFeed tvFeed = TVFeed.classicalGame(System.out::println);
        //TVFeed tvFeed = TVFeed.rapidGame(System.out::println);
        //TVFeed tvFeed = TVFeed.blitzGame(System.out::println);
        TVFeed tvFeed = TVFeed.featuredGame(System.out::println);

        System.console().readLine();

        tvFeed.stop();
    }
}

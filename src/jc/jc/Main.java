package jc;

import jc.app.TVFeed;

class Main {

    public static void main(String[] args) {

        TVFeed tvFeed = TVFeed.startConsole(System.out::println);

        System.console().readLine();

        tvFeed.stop();
    }
}

package jc;

import jc.app.TVFeed;

class Main {

    public static void main(String[] args) {

        TVFeed tvFeed = TVFeed.startConsole();

        // Block until user hits Enter
        System.console().readLine();

        tvFeed.stop();
    }
}

# jc

A demo application for the [chariot](https://github.com/tors42/chariot) library.  


# Build

Uses Maven and Java 19

    $ mvn clean install

## Watch

In default mode, **jc** continuously writes a text board of the featured Lichess TV game to console.  

    GM DarthMccartney
    0:00:20 *
    ┌───┬───┬───┬───┬───┬───┬───┬───┐
    │   │   │   │   │   │   │   │   │
    ├───┼───┼───┼───┼───┼───┼───┼───┤
    │   │   │   │   │ ♗ │   │   │   │
    ├───┼───┼───┼───┼───┼───┼───┼───┤
    │ ♟ │   │ ♟ │   │   │   │ ♝ │   │
    ├───┼───┼───┼───┼───┼───┼───┼───┤
    │ ♙ │   │ ♟ │   │   │   │ ♙ │   │
    ├───┼───┼───┼───┼───┼───┼───┼───┤
    │   │   │ ♚ │ ♟ │   │   │   │   │
    ├───┼───┼───┼───┼───┼───┼───┼───┤
    │   │   │   │   │   │   │   │   │
    ├───┼───┼───┼───┼───┼───┼───┼───┤
    │   │   │   │ ♔ │   │   │   │   │
    ├───┼───┼───┼───┼───┼───┼───┼───┤
    │   │   │   │   │   │   │   │   │
    └───┴───┴───┴───┴───┴───┴───┴───┘
    0:00:11
    Caviar2068


Run with

    $ java --enable-preview -jar target/jc-0.0.1-SNAPSHOT-jar-with-dependencies.jar

The above command shows the featured TV game.

Specify "classical", "rapid" or "blitz" to watch that TV channel, or a game id to watch that game.

    $ java --enable-preview -jar target/jc-0.0.1-SNAPSHOT-jar-with-dependencies.jar blitz

## Play

In play mode, **jc** creates a seek for a casual Rapid game (15+10) and lets the user input moves in UCI format (*e2e4*, *b8c6* etc) in a text field.  
Since playing a game on Lichess needs an account - it is necessary to authorize **jc** in order for it to be allowed to send moves.  
You can either use OAuth2 PKCE or a [Personal Access Token](https://lichess.org/account/oauth/token/create?scopes[]=board:play&description=Board+API) with scope _board:play_.

To run with OAuth2 PKCE,

    $ java --enable-preview -jar target/jc-0.0.1-SNAPSHOT-jar-with-dependencies.jar play


To run with a pre-created token,

    $ export LICHESS_TOKEN=lip_...
    $ java --enable-preview -jar target/jc-0.0.1-SNAPSHOT-jar-with-dependencies.jar play


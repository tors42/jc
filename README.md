# jc

A demo application for the [chariot](https://github.com/tors42/chariot) library, currently rendering the featured Lichess TV game to console.  
_Hint, manually resize the terminal so only the newest lines showing the newest position are visible_

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


# Build

Uses Java 19

    mvn clean install

# Run

    java --enable-preview -jar target/jc-0.0.1-SNAPSHOT-jar-with-dependencies.jar


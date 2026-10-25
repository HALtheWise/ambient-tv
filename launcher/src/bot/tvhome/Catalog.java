package bot.tvhome;

/**
 * The patterns the home screen rotates through, from Golly's collection and conwaylife.com
 * (assets/life). Credits are from each file's own header comments.
 */
final class Catalog {

    static final class Entry {
        final String file, name, credit, blurb;
        /** Generations per second. */
        final int speed;
        /** Stop after this many generations (methuselahs settle); 0 = run for `seconds`. */
        final long gens;
        final int seconds;
        /**
         * Frame the dense soup, not everything: methuselahs throw off gliders that would otherwise
         * pull the camera out until the interesting part is a speck.
         */
        final boolean soup;

        Entry(String file, String name, String credit, String blurb, int speed, long gens, int seconds) {
            this.file = file;
            this.name = name;
            this.credit = credit;
            this.blurb = blurb;
            this.speed = speed;
            this.gens = gens;
            this.seconds = seconds;
            this.soup = java.util.Arrays.asList(SOUPS).contains(file);
        }
    }

    private static final String[] SOUPS = {
        "rpentomino.rle", "acorn.lif", "rabbits.lif", "lidka-predecessor.rle", "iwona.rle", "52513m.rle",
    };

    static final Entry[] ALL = {
        new Entry("gosperglidergun.rle", "Gosper glider gun", "Bill Gosper, 1970",
                "The first pattern proven to grow forever: a glider every 30 generations", 12, 0, 150),
        new Entry("rpentomino.rle", "R-pentomino", "John Conway, 1969",
                "Five cells, 1,103 generations of chaos before it settles", 20, 1200, 0),
        new Entry("acorn.lif", "Acorn", "Charles Corderman, 1971",
                "Seven cells that take 5,206 generations to settle", 40, 5300, 0),
        new Entry("rabbits.lif", "Rabbits", "Andrew Trevorrow, 1972",
                "Nine cells that run for 17,331 generations", 96, 17400, 0),
        new Entry("lidka-predecessor.rle", "Lidka", "Andrzej Okrasiński and David Bell, 2005",
                "Thirteen cells, 29,055 generations, 28 gliders", 160, 29100, 0),
        new Entry("iwona.rle", "Iwona", "Andrzej Okrasiński, 2004",
                "Nineteen cells, 28,786 generations, 47 gliders", 160, 28900, 0),
        new Entry("52513m.rle", "52513M", "Dylan Chen, 2021",
                "The longest-lived 16 by 16 soup found by apgsearch: 52,513 generations", 320, 52600, 0),
        new Entry("breeder.lif", "Breeder", "Bill Gosper, early 1970s",
                "The first quadratic growth: puffers that leave glider guns behind them", 40, 0, 180),
        new Entry("spacefiller.rle", "Spacefiller", "Hartmut Holzwart, 1998",
                "Fills the plane at half density, spreading at c/2 in every direction", 30, 0, 120),
        new Entry("quartermax.rle", "Quartermax", "Keith Amling, 2022",
                "Fills a quarter of the plane with stripes", 30, 0, 120),
        new Entry("puffer-train.rle", "Puffer train", "Bill Gosper, 1971",
                "A B-heptomino escorted by two spaceships, leaving debris in its wake", 20, 0, 120),
        new Entry("millionaire-puffer.rle", "Millionaire puffer", "pigeon, 2023",
                "A switch-engine puffer that repeats every 229,689,152 generations", 60, 0, 150),
        new Entry("Turing-Machine-3-state.rle", "Turing machine", "Paul Rendell, 2000",
                "A working three-state, three-symbol Turing machine built from guns and gliders", 768, 0, 180),
        new Entry("twinprimes.rle", "Twin prime generator", "Dean Hickerson, 1994",
                "A spaceship escapes past the gun for every pair of twin primes", 96, 0, 180),
        new Entry("fermat-primes.rle", "Fermat prime calculator", "Jason Summers, 2000",
                "Self-destructs if a Fermat prime above 65,537 exists. Nobody knows if it will", 192, 0, 180),
        new Entry("golly-ticker.rle", "Golly ticker", "Brice Due, 2005",
                "A ticker-tape gun that spells out its own name", 48, 0, 150),
        new Entry("vacuum-cleaner.rle", "Vacuum cleaner", "Dean Hickerson, 1997",
                "A double p46 gun that pulls in blocks and beehives", 20, 0, 120),
        new Entry("corderships.rle", "Corderships", "named after Charles Corderman",
                "c/12 diagonal spaceships built from switch engines that clean up each other's debris", 24, 0, 120),
        new Entry("three-halves-growth.rle", "Three-halves growth", "Martin Grant, 2013",
                "Population grows as t^1.5, halfway between linear and quadratic", 96, 0, 150),
        new Entry("sawtooth177.rle", "Sawtooth 177", "thunk, 2015",
                "Keeps reaching new population highs, then falling back to 177", 48, 0, 150),
        new Entry("sirrobin.rle", "Sir Robin", "Adam P. Goucher and Tom Rokicki, 2018",
                "The first elementary knightship: two forward and one across every six generations", 8, 0, 90),
        new Entry("copperhead.rle", "Copperhead", "zdr, 2016",
                "A twelve-row c/10 spaceship", 10, 0, 60),
        new Entry("simkinglidergun.rle", "Simkin glider gun", "Michael Simkin, 2015",
                "A period-120 glider gun, found 45 years after Gosper's", 12, 0, 120),
        new Entry("lobster.rle", "Lobster", "Matthias Merzenich, 2011",
                "The first c/7 diagonal spaceship", 10, 0, 60),
        new Entry("loafer.rle", "Loafer", "Josh Ball, 2013",
                "A small c/7 spaceship", 7, 0, 60),
    };

    private Catalog() {
    }
}

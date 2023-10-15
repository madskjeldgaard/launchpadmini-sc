
## Examples

```supercollider
l = LaunchpadMiniMk1.new()

// Randomize colors
(
r{
    loop{
        // Randomize color of all 8x8 grid buttons
        8.do{|x|
            8.do{|y|
                l.setColor(x,y,rrand(0,127));
                (rrand(0.001,0.01)).wait;
            }
        }


    }
}.play;
)

// Set a callback for a specific pad
l.setFunc(x: 3, y: 5, func: {|...args|
    "YO".postln;
    args.postln;
});

```

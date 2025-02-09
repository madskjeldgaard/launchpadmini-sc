// Simple interface for the Launchpad Mini MK1
// It is a controller with an 8x8 grid of buttons
// Each button has a note on and note off event
/*

// Example
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

(
r{
    var index = 0.0;
    var p = Pseg([0.025,0.1],Pwhite(0.5,1.0),Pwhite(0.1,10.0),inf);
    var waitTime = p.asStream;

    loop{
        index = (index + (1.0/127.0)).wrap(0.0,1.0);
        l.setAllColors((index * 127.0).postln);
        waitTime.next().wait;
    }
}.play

)
*/
LaunchpadMiniMk1 {
    var <callbacks, <midiFuncs, <sideButtonCallbacks, <sideButtonMidiFuncs, <topButtonCallbacks, <topButtonMidiFuncs;
    var <deviceMidiOut;
    var <channel = 0;
    var <verbose = true;

    classvar <midiDeviceName = "Launchpad Mini";
    classvar <deviceButtonNotes, <sideButtonNotes, <topButtonCCNums;

    *new{
        ^super.newCopyArgs().init();
    }

    *initClass{
        deviceButtonNotes = [
            // Rows
            (0..7),
            (16..23),
            (32..39),
            (48..55),
            (64..71),
            (80..87),
            (96..103),
            (112..119)
        ];

        sideButtonNotes = [
            8,
            24,
            40,
            56,
            72,
            88,
            104,
            120
        ];

        topButtonCCNums = (104..111);
    }

    init{
        var devices;

        var afterConnection = {

            fork{
                var routine = Routine({
                    var index = 0.0;
                    var p = Pseg([0.025,0.1],Pwhite(0.5,1.0),Pwhite(0.1,10.0),inf);
                    var waitTime = p.asStream;

                    loop{
                        index = (index + (1.0/127.0)).wrap(0.0,1.0);
                        this.setAllColors((index * 127.0));
                        waitTime.next().wait;
                    }
                });

                routine.play;
                1.wait;
                routine.stop;
                this.setAllColors(0);
            }

        };

        callbacks = Array.fill(8, {Array.fill(8, {})});
        sideButtonCallbacks = Array.fill(8, {});
        topButtonCallbacks = Array.fill(8, {});
        midiFuncs = Array.fill(8, {Array.fill(8, {nil})});

        // Connect midi controller
        if(MIDIClient.initialized.not, {
            "MIDIClient not initialized... initializing now".postln;
            MIDIClient.init;
        });

        devices = MIDIClient.sources.select{|source| source.device == midiDeviceName};

        if(devices.size > 1, {
            "Dont know what to do about more than 1 controller atm".error
        });

        if(devices.size < 1, {
            "No device found - may only be used with GUI".warn;
        }, {
            deviceMidiOut = MIDIOut.newByName(devices[0].device, devices[0].name).latency_(0);

            // A dirty hack to connect the device
            MIDIClient.sources.do{|src, srcNum|
                var methodname = (midiDeviceName ++ "connected").asSymbol;

                if(src.device == midiDeviceName, {
                    if(try{MIDIIn.perform(methodname)}.isNil, {
                        if(MIDIClient.sources.any({|e| e.device==midiDeviceName}), {
                            "Connecting %".format(midiDeviceName).postln;
                            MIDIIn.connect(srcNum, src).addUniqueMethod(methodname, {true});
                            afterConnection.value();
                        });
                    }, {"% is already connected... (device is busy)".format(midiDeviceName).postln});
                });
            }

        })
    }

    setFunc{|x,y,func|
        // Register as a MIDIFunc
        var note = deviceButtonNotes[y][x];

        // Create a MIDIFunc
        // TODO: Filter by UID
        var midiFunc =
        Dictionary.newFrom([
            \on, MIDIFunc.noteOn({|...args|
                var velocity = args[0];
                var note = args[1];
                var chan = args[2];
                var uid = args[3];
                func.value(this,\on, x,y,velocity, note, chan, uid);        }, noteNum: note, chan: channel, srcID: nil /* TODO */),

            \off, MIDIFunc.noteOff({|...args|
                var velocity = args[0];
                var note = args[1];
                var chan = args[2];
                var uid = args[3];
                func.value(this,\off, x,y,velocity, note, chan, uid);        }, noteNum: note, chan: channel, srcID: nil /* TODO */)
            ]);

        // Store callback
        callbacks[x][y] = func;
    }

    setSideButtonFunc{|y,func|
        // Register as a MIDIFunc
        var note = sideButtonNotes[y];

        // Create a MIDIFunc
        var midiFunc = MIDIFunc.noteOn({|...args|
            var velocity = args[0];
            var note = args[1];
            var chan = args[2];
            var uid = args[3];
            func.value(this, y,velocity, note, chan, uid);
        }, noteNum: note, chan: channel, srcID: nil /* TODO */);

        // Store callback
        sideButtonCallbacks[y] = func;

    }

    setTopButtonFunc{|x,func|
        // Register as a MIDIFunc
        var ccNum = topButtonCCNums[x];

        // Create a MIDIFunc
        var midiFunc = MIDIFunc.cc({|...args|
            var value = args[0];
            var ccNum = args[1];
            var chan = args[2];
            var uid = args[3];

            func.value(this, x,value, ccNum, chan, uid);
        }, ccNum: ccNum, chan: channel, srcID: nil /* TODO */);

        // Store callback
        topButtonCallbacks[x] = func;
    }

    // Colours on the controller are controlled using midi noteOn events, where the velocity is the color
    setColor{|x,y,color=64|
        var note = deviceButtonNotes[y][x];
        deviceMidiOut.noteOn(channel, note, color);
    }

    setColorAroundPad{|x,y,color=64, distance=1|
        var maxFieldsAroundPad = 8;
        var xMax = 7, yMax = 7;
        var coordinates = Array.fill(8, {Array.fill(8, {})});
        var xStart = (x-distance).clip(0,xMax);
        var yStart = (y-distance).clip(0,yMax);

        // Find all fields surrounding the x,y coordinate
        // The matrix looks like this: [
        //     [x, x, x],
        //      [x, 0, x],
        //      [x, x, x]
        // ]
        // Where x is a coordinate that is not the center
        // And 0 is the center coordinate
        // The coordinates are stored in the coordinates array
        (xStart..(x+distance).clip(0,xMax)).do{|xRow|
            (yStart..(y+distance).clip(0,yMax)).do{|yRow|
                if(xRow != x || yRow != y, {
                    coordinates[xRow][yRow] = [xRow, yRow];
                });
            };
        };

        // Send notes
        coordinates.do{|xRow|
            xRow.do{|coord|
                if(coord.notNil, {
                    var x = coord[0];
                    var y = coord[1];
                    if(x.isNil or: {y.isNil}, {
                        // Do nothing
                    }, {
                        this.setColor(x,y,color);
                    })
                });
            };
        };

    }

    setColorSide{|y,color=64|
        var note = sideButtonNotes[y];
        deviceMidiOut.noteOn(channel, note, color);
    }

    // TODO: Does this actually work?
    setColorTop{|x,color=64|
        var ccNum = topButtonCCNums[x];
        deviceMidiOut.control(channel, ccNum, color);
    }

    setAllColors{|newColor|
        8.do{|x|
            8.do{|y|
                this.setColor(x,y,newColor);
            }
        };

        8.do{|y|
            this.setColorSide(y,newColor);
        };

        8.do{|x|
            this.setColorTop(x,newColor);
        };
    }

    gui{
        LaunchpadMiniMk1GUI.new(this);
    }
}

LaunchpadMiniMk1GUI {
    var <window, <gridButtons, <sideButtons, <topButtons;
    var <launchpad;

    *new { |launchpad|
        ^super.new.init(launchpad);
    }

    init { |argLaunchpad|
        var windowSize = Rect(100, 100, 400, 400), buttonSize = Rect(0, 0, 35, 35);
        launchpad = argLaunchpad;

        // Create the window
        window = Window("Launchpad Mini MK1 Simulator", windowSize).front;
        window.onClose_({ this.free });

        // Create the 8x8 grid of buttons
        gridButtons = Array.fill(8, { Array.fill(8, { nil }) });
        8.do { |x|
            8.do { |y|
                gridButtons[x][y] = Button(window, Rect((x * buttonSize.width) + 20, (y * buttonSize.height) + 80, buttonSize.width, buttonSize.height))
                    .states_([["%,%".format(x,y), Color.black, Color.yellow], ["%,%".format(x,y), Color.black, Color.green]])
                    .action_({ |btn|
                        var state = btn.value;
                        if (state == 1, {
                            launchpad.callbacks[x][y].value(launchpad, \on, x, y, 127, LaunchpadMiniMk1.deviceButtonNotes[y][x], 0, nil);
                        }, {
                            launchpad.callbacks[x][y].value(launchpad, \off, x, y, 0, LaunchpadMiniMk1.deviceButtonNotes[y][x], 0, nil);
                        });
                    });
            };
        };

        // Create the side buttons
        sideButtons = Array.fill(8, { nil });
        8.do { |y|
            sideButtons[y] = Button(window, Rect(windowSize.width - buttonSize.width - 20, (y * buttonSize.height) + 80, buttonSize.width, buttonSize.height))
                .states_([["%".format(y), Color.white, Color.black], ["%".format(y), Color.white, Color.black]])
                .action_({ |btn|
                    var state = btn.value;
                    if (state == 1, {
                        launchpad.sideButtonCallbacks[y].value(launchpad, y, 127, LaunchpadMiniMk1.sideButtonNotes[y], 0, nil);
                    }, {
                        launchpad.sideButtonCallbacks[y].value(launchpad, y, 0, LaunchpadMiniMk1.sideButtonNotes[y], 0, nil);
                    });
                });
        };

        // Create the top buttons
        topButtons = Array.fill(8, { nil });
        8.do { |x|
            topButtons[x] = Button(window, Rect((x * buttonSize.width) + 20, 20, buttonSize.width, buttonSize.height))
                .states_([["%".format(x), Color.white, Color.black], ["%".format(x), Color.white, Color.black]])
                .action_({ |btn|
                    var state = btn.value;
                    if (state == 1, {
                        launchpad.topButtonCallbacks[x].value(launchpad, x, 127, LaunchpadMiniMk1.topButtonCCNums[x], 0, nil);
                    }, {
                        launchpad.topButtonCallbacks[x].value(launchpad, x, 0, LaunchpadMiniMk1.topButtonCCNums[x], 0, nil);
                    });
                });
        };
    }

    free {
        window.close;
    }
}

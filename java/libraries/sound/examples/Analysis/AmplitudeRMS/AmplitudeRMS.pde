/*
This example shows how to use the RMS amplitude tracker. The tracker
calculates the Root Mean Square over a block of audio and returns
the mean as a float between 0 and 1.
*/

import processing.sound.*;

SoundFile sample;
Amplitude rms;

int scale=1;

public void setup() {
    size(640,360);

    //Load and play a soundfile and loop it
    sample = new SoundFile(this, "beat.aiff");
    sample.loop();
    
    // Create and patch the rms tracker
    rms = new Amplitude(this);
    rms.input(sample);
}      

public void draw() {
    background(125,255,125);
    
    // rms.analyze() return a value between 0 and 1. To adjust
    // the scaling and mapping of an ellipse we scale from 0 to 0.5
    scale=int(map(rms.analyze(), 0, 0.5, 1, 350));
    noStroke();
    
    fill(255,0,150);
    // We draw an ellispe coupled to the audio analysis
    ellipse(width/2, height/2, 1*scale, 1*scale);
}

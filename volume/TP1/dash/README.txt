Stage 1 – MPEG-DASH

Run ./prepare_dash.sh to generate content/video_manifest.mpd and the corresponding segments.

On the Streamer, run ./start-dash-server.sh. The server disables caching and conditional responses to ensure that the segments traverse the experimental link in each test.

On PC3, run ./open-dash-player.sh PC3.

The player displays R1/R2/R3, resolution, nominal bitrate, estimated throughput, buffer level, and actual rebuffering. The target buffer has been reduced to 8–10 s so that ABR transitions can be observed during the 120 s experiment.

After changing the bandwidth in CORE and clicking Apply, use the player buttons (10 Mbit/s, 2 Mbit/s, 800 kbit/s, 5 Mbit/s) to mark the time of each change in the log. The buttons only record the corresponding network condition; they do not configure CORE.

The “Save log” button allows the experimental evidence to be saved in TXT format.

When using WSLg, open-dash-player.sh automatically configures PULSE_SERVER when the /mnt/wslg/PulseServer socket is available. The player starts muted due to browser autoplay restrictions; enable audio using the video controls.

All three MP4 representations include the same audio track. prepare_dash.sh creates three video representations and a separate audio Adaptation Set in the MPD.
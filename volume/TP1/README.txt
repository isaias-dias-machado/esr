ESR 2026/27 - TP1 - Resource Package

The structure of this package must be located at ~/esr/TP1 in the WSL environment.
Do not change file or directory names: the lab guide assumes this structure.

If you received the package in ZIP format, a simple way to install it is:
mkdir -p ~/esr
unzip ESR-TP1-26-27-resources.zip -d ~/esr

Then confirm that the following file exists:
~/esr/TP1/README.txt

IMPORTANT IN CORE/VCMD TERMINALS
VCMDs are normally executed as root, so '~' may refer to /root rather than
the WSL user's home directory. The package scripts derive TP1_ROOT from their
own location. Use the commands specified in the lab guide and, when necessary,
check the value with:
echo "$TP1_ROOT"

The auxiliary scripts standardize the preparation and execution procedures.
You do not need to implement them, but you are encouraged to inspect them to
understand the commands and parameters used.

STAGE 1 - DASH

prepare_dash.sh creates the MPD and the segments;

start-dash-server.sh serves the content without caching, preventing 304 responses;

open-dash-player.sh starts a clean Firefox session and automatically configures
WSLg audio when /mnt/wslg/PulseServer is available;

the player allows you to mark the 10 Mbit/s, 2 Mbit/s, 800 kbit/s, and
5 Mbit/s changes in the log and save the log in TXT format.

Artifacts produced by the group during TP1:

dash/content/video_manifest.mpd and DASH segments;

rtp/pc1.sdp ... rtp/pc4.sdp and rtp/multicast.sdp;

.pcap captures, logs, and other results in results/.

MEDIA WITH AUDIO
All provided videos include a synthetic reference AAC audio track. In the
DASH representations, the audio is shared by all three quality levels and is
packaged as a separate Adaptation Set.
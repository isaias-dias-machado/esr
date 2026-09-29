Stage 3 – Auxiliary Multicast Forwarding

The enable-multicast.sh script configures a static multicast route (S,G) for:
source 10.0.1.10 (Streamer)
group  239.10.10.10

Run the script with root privileges first on routerA and then on routerB:
sudo ~/esr/TP1/multicast/enable-multicast.sh

The configuration uses SMCRoute and is not part of the assessment; it is provided solely to make the RTP multicast experiment reproducible.
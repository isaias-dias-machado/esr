Stage 2 – HTTP/2/TCP vs HTTP/3/QUIC

On the Streamer, in two separate terminals:
./start-http2-server.sh
./start-http3-server.sh

On PC3:
./get-http2.sh
./get-http3.sh

Both scripts transfer the same ~/esr/TP1/media/video_ref.mp4 file and save the copies to:
/tmp/http2_video_ref.mp4
/tmp/http3_video_ref.mp4

Requirements: nginx with HTTP/2 and HTTP/3 support, and curl with HTTP/2 and HTTP/3 support.
The certificate in certs/ is self-signed and is intended for laboratory use only.
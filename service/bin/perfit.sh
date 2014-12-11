#!/bin/sh

# To run this, just call: sh ./perfit.sh
# You can optionally pass the port: sh ./perfit.sh 8383
# You may also optionally pass a port and URL, it must start with a slashL sh ./perfit.sh 8383 /about

# THIS IS TO ONLY GET A BASELINE MARK!  THIS DOES NOT REFLECT PROD PERFORMANCE
# THE GOAL IS TO HAVE SO NOTION OF PERF AND POTENTIAL REGRESSIONS

HOST='127.0.0.1'
port=8080
PORT=${1:-$port}
url="/"
#url="/nio"
#url="/file"
#url="/niofile"
#url="/proxy"
#url="/nioproxy"
#url="/anioproxy"
URL=${2:-$url}

#lein devprod-deploy
#echo "Waiting for server to come up"
#lein devprod-run &
#sleep 25

## Uncomment the desired benchmarks
#httperf --close-with-reset --session-cookies --hog  --server $HOST --port $PORT --uri $URL --wsess=40,5,2 --rate 2 --timeout 5
#echo
#siege -c 20 -t 30S http://$HOST:$PORT$URL 1> /dev/null
#echo
wrk -t4 -c24 -d30s http://$HOST:$PORT$URL


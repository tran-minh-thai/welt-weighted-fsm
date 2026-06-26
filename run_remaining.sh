#!/usr/bin/env bash
# Chạy TIẾP phần thực nghiệm còn thiếu sau khi bị ngắt, TUẦN TỰ (không chạy đồng thời),
# theo đúng thứ tự yêu cầu:
#   1) sc3 (vertex generality) -- đầy đủ
#   2) sc1 lastfm còn thiếu (minSup 300 và 200) -- NỐI vào file SC1 cũ (không lặp 600/500/400)
#   3) sc2 (scalability) -- đầy đủ
#
# Chạy:
#   TIME_LIMIT_MS=1800000 JAVA_OPTS="-Xmx20g" nohup ./run_remaining.sh > remaining.log 2>&1 &
#   tail -f remaining.log
# (-Xmx20g cần cho mico/github ở sc2; vô hại với sc3/lastfm.)
set -euo pipefail
cd "$(dirname "$0")"
[ -d target/classes ] || ./build.sh

LIMIT="${TIME_LIMIT_MS:-1800000}"
WARMUP="${WARMUP:-1}"
MEASURED="${MEASURED:-5}"

# Giữ máy thức suốt quá trình (macOS); no-op nếu không có caffeinate.
if command -v caffeinate >/dev/null 2>&1; then
    caffeinate -i -w $$ &
fi

echo "########## 1/3: sc3 (vertex generality) ##########"
./experiments.sh sc3

echo
echo "########## 2/3: sc1 lastfm còn thiếu (minSup 300, 200) ##########"
sc1csv=$(ls -t results/SC1_*efficiency.csv 2>/dev/null | head -1 || true)
if [ -z "${sc1csv:-}" ]; then
    sc1csv="results/SC1_$(date +%Y%m%d_%H%M%S)_efficiency.csv"
    echo "dataset,algorithm,weightModel,minSup,minWeight,budget,limitMs,freq,candMNI,mineMNI,medianMs,timedOut" > "$sc1csv"
fi
echo "Nối kết quả vào file SC1 hiện có: $sc1csv"
for s in 300 200; do
    echo ">>> datasets/lastfm.lg  minSup=$s  tau_w=40  budget=400  limit=${LIMIT}ms"
    tmp=$(mktemp)
    BENCH_CSV=1 BENCH_ALGOS="GraMi,WEGM,WeLT" java ${JAVA_OPTS:-} -cp target/classes \
        welt.runner.BenchmarkMain datasets/lastfm.lg "$s" 40 400 "$LIMIT" "$WARMUP" "$MEASURED" > "$tmp" 2>&1 || true
    cat "$tmp"
    grep '^CSV,' "$tmp" | grep -v 'CSV,dataset' | sed 's/^CSV,//' >> "$sc1csv" || true
    rm -f "$tmp"
done

echo
echo "########## 3/3: sc2 (scalability) -- mico/github cần -Xmx20g ##########"
./experiments.sh sc2

echo
echo "########## HOÀN TẤT PHẦN CÒN LẠI ##########"
echo "Các file kết quả trong results/:"
ls -1 results/SC*.csv | sed 's/^/  /'
echo "SC1 hiện có $(($(grep -c . "$sc1csv")-1)) dòng dữ liệu (đủ là 51: [6 citeseer + 6 email + 5 lastfm] x 3 thuật toán)."

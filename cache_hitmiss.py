#!/usr/bin/env python3
import sys
import psutil
from bcc import BPF
from time import sleep
import datetime

# --------------------------
# Helper to detect Cassandra PID automatically
# --------------------------

def find_cassandra_pid():
    for proc in psutil.process_iter(['pid', 'name', 'cmdline']):
        try:
            cmdline = proc.info['cmdline']
            if not cmdline:
                continue
            cmdline_str = ' '.join(cmdline).lower()
            if 'java' in cmdline_str and 'cassandra' in cmdline_str:
                return proc.info['pid']
        except (psutil.NoSuchProcess, psutil.AccessDenied):
            continue
    return None

# --------------------------
# Parse command line arguments
# --------------------------
filename = "pagecache_result.txt"  # default output file
if len(sys.argv) > 2:
    print("Usage: sudo python3 pagecache_hitmiss_auto.py [output_filename]")
    exit(1)
elif len(sys.argv) == 2:
    filename = sys.argv[1]

# --------------------------
# Detect Cassandra PID
# --------------------------
TARGET_PID = find_cassandra_pid()
if not TARGET_PID:
    print("Cassandra process not found. Exiting.")
    exit(1)

print(f"Detected Cassandra PID: {TARGET_PID}")
print(f"Final results will be saved to: {filename}")

# --------------------------
# eBPF C program
# --------------------------
prog = r"""
#include <uapi/linux/ptrace.h>
#include <linux/sched.h>

BPF_ARRAY(stats, u64, 2);   // stats[0] = hits, stats[1] = misses

static inline bool filter_pid(void) {
    u32 pid = bpf_get_current_pid_tgid() >> 32;
    return pid == TARGET_PID;
}

// Page cache hit
int trace_page_access(struct pt_regs *ctx) {
    if (!filter_pid()) return 0;
    u32 idx = 0;
    u64 *v = stats.lookup(&idx);
    if (v) (*v)++;
    return 0;
}

// Page cache miss
int trace_page_cache_miss(struct pt_regs *ctx) {
    if (!filter_pid()) return 0;
    u32 idx = 1;
    u64 *v = stats.lookup(&idx);
    if (v) (*v)++;
    return 0;
}
"""

# Load BPF program and attach probes
b = BPF(text=prog.replace("TARGET_PID", str(TARGET_PID)))
b.attach_kprobe(event="mark_page_accessed", fn_name="trace_page_access")
b.attach_kprobe(event="add_to_page_cache_lru", fn_name="trace_page_cache_miss")

print(f"Tracing pagecache hits/misses for PID {TARGET_PID} ... Ctrl-C to stop.")

hits_idx = 0
miss_idx = 1

# --------------------------
# Reporting loop
# --------------------------
try:
    while True:
        sleep(10)
        stats = b.get_table("stats")
        hits = stats[hits_idx].value
        misses = stats[miss_idx].value
        total = hits + misses
        rate = 0.0 if total == 0 else (hits * 100.0) / total
        print(f"PID {TARGET_PID}: workload {filename} hits={hits}  misses={misses}  hit-rate={rate:.2f}%")
except KeyboardInterrupt:
    # On Ctrl-C → write final stats to the specified file
    stats = b.get_table("stats")
    hits = stats[hits_idx].value
    misses = stats[miss_idx].value
    total = hits + misses
    rate = 0.0 if total == 0 else (hits * 100.0) / total
    timestamp = datetime.datetime.now().strftime("%Y-%m-%d %H:%M:%S")

    with open(filename, "w") as f:
        f.write(f"Page Cache Hit/Miss Summary for PID {TARGET_PID} and workload {filename}\n")
        f.write(f"Timestamp: {timestamp}\n\n")
        f.write(f"Total Hits:   {hits}\n")
        f.write(f"Total Misses: {misses}\n")
        f.write(f"Total Access: {total}\n")
        f.write(f"Hit Rate:     {rate:.2f}%\n")

    print(f"\nFinal results saved to: {filename}")
    print("Done.")

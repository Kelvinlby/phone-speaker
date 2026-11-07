// phone-speakerd: bridges PipeWire and an Android phone over USB (adb reverse).
//
// While no phone is connected the daemon sleeps in poll() and uses no audio
// resources. When the app connects it starts pw-record ("Phone Speaker" sink)
// and/or pw-play ("Phone Mic" source), so those devices exist only during a
// session.
//
// Protocol on 127.0.0.1:PORT, single full-duplex TCP stream:
//   phone -> PC  header: "PSPK", version(1), flags (1=speaker, 2=mic)
//   PC -> phone  ack:    "PSOK"
//   PC -> phone  s16le 48 kHz stereo while speaker is on
//   phone -> PC  s16le 48 kHz mono   while mic is on
//
// Latency stays bounded: audio that cannot be delivered is dropped, never queued.
#define _GNU_SOURCE
#include <arpa/inet.h>
#include <errno.h>
#include <fcntl.h>
#include <netinet/in.h>
#include <netinet/tcp.h>
#include <poll.h>
#include <signal.h>
#include <stdarg.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/prctl.h>
#include <sys/socket.h>
#include <sys/wait.h>
#include <time.h>
#include <unistd.h>

#define PORT 47100
#define ADB_SERVER_PORT 5037
#define ADB_RETRY_MS 5000
#define HEADER_TIMEOUT_MS 2000
#define HEADER_LEN 6
#define PROTO_VERSION 1
#define FLAG_SPEAKER 1
#define FLAG_MIC 2
#define REC_PIPE_BYTES 4096
#define PLAY_PIPE_BYTES 8192   // about 85 ms of mono, the most the mic can lag
#define SOCK_BYTES 16384
#define SPK_FRAME 4
#define MIC_FRAME 2
#define SPK_READ 3840         // 20 ms of stereo
#define SPK_QUEUE (SPK_READ * 3)
#define MIC_READ 1920         // 20 ms of mono; below PIPE_BYTES so writes are atomic

#define SPK_PROPS                                                            \
    "{ media.class=Audio/Sink node.name=phone_speaker "                      \
    "node.description=\"Phone Speaker\" priority.session=2000 "              \
    "device.icon-name=phone }"
#define MIC_PROPS                                                            \
    "{ media.class=Audio/Source node.name=phone_mic "                        \
    "node.description=\"Phone Mic\" priority.session=2000 "                  \
    "device.icon-name=phone }"

static int log_line(const char *fmt, ...) {
    va_list ap;
    va_start(ap, fmt);
    vfprintf(stderr, fmt, ap);
    va_end(ap);
    fputc('\n', stderr);
    return -1;
}

static long now_ms(void) {
    struct timespec t;
    clock_gettime(CLOCK_MONOTONIC, &t);
    return t.tv_sec * 1000L + t.tv_nsec / 1000000L;
}

static void set_nonblock(int fd) { fcntl(fd, F_SETFL, fcntl(fd, F_GETFL) | O_NONBLOCK); }

static const char *adb_path(void) {
    const char *p = getenv("ADB");
    return p && *p ? p : "adb";
}

// Runs a command with stdio on /dev/null except for the fds given. Returns pid.
static pid_t spawn(char *const argv[], int stdin_fd, int stdout_fd, int die_with_parent) {
    pid_t pid = fork();
    if (pid != 0) return pid;
    signal(SIGCHLD, SIG_DFL);
    signal(SIGPIPE, SIG_DFL);
    if (die_with_parent) prctl(PR_SET_PDEATHSIG, SIGTERM);
    int null_fd = open("/dev/null", O_RDWR);
    dup2(stdin_fd >= 0 ? stdin_fd : null_fd, 0);
    dup2(stdout_fd >= 0 ? stdout_fd : null_fd, 1);
    execvp(argv[0], argv);
    log_line("exec %s failed: %s", argv[0], strerror(errno));
    _exit(127);
}

// Makes a device the PC's default. WirePlumber keeps a history of chosen devices, so
// when the device goes away it falls back to the previous choice by itself.
static void set_default(const char *key, const char *node_json) {
    char *const argv[] = {"pw-metadata", "-n", "default", "0", (char *)key, (char *)node_json,
                          "Spa:String:JSON", NULL};
    spawn(argv, -1, -1, 0);
}

// ---------------------------------------------------------------- audio session

static struct {
    int sock;         // connected phone, -1 when idle
    int rec_fd;       // pw-record stdout
    int play_fd;      // pw-play stdin
    pid_t rec_pid, play_pid;
    unsigned char spk[SPK_QUEUE + 1024];  // the slack keeps stream alignment on overflow
    size_t spk_len;
    unsigned char mic[MIC_READ];
    size_t mic_len;
    unsigned long spk_dropped, mic_dropped;
} s = {.sock = -1, .rec_fd = -1, .play_fd = -1};

static void session_stop(const char *why) {
    if (s.sock < 0) return;
    log_line("session ended: %s (dropped speaker=%lu mic=%lu bytes)", why, s.spk_dropped,
             s.mic_dropped);
    s.spk_dropped = s.mic_dropped = 0;
    close(s.sock);
    s.sock = -1;
    if (s.rec_fd >= 0) close(s.rec_fd);
    if (s.play_fd >= 0) close(s.play_fd);
    s.rec_fd = s.play_fd = -1;
    if (s.rec_pid > 0) kill(s.rec_pid, SIGTERM);
    if (s.play_pid > 0) kill(s.play_pid, SIGTERM);
    s.rec_pid = s.play_pid = 0;
    s.spk_len = s.mic_len = 0;
}

static int session_start(int sock, int flags) {
    int fds[2];
    s.sock = sock;
    if (flags & FLAG_SPEAKER) {
        if (pipe2(fds, O_CLOEXEC) < 0) {
            session_stop("cannot create pipe");
            return -1;
        }
        fcntl(fds[0], F_SETPIPE_SZ, REC_PIPE_BYTES);
        char *const argv[] = {"pw-record", "--raw", "--rate", "48000", "--channels", "2",
                              "--format", "s16", "--latency", "20ms", "-P", SPK_PROPS, "-", NULL};
        s.rec_pid = spawn(argv, -1, fds[1], 1);
        close(fds[1]);
        s.rec_fd = fds[0];
        set_nonblock(s.rec_fd);
        set_default("default.configured.audio.sink", "{\"name\":\"phone_speaker\"}");
    }
    if (flags & FLAG_MIC) {
        if (pipe2(fds, O_CLOEXEC) < 0) {
            session_stop("cannot create pipe");
            return -1;
        }
        fcntl(fds[1], F_SETPIPE_SZ, PLAY_PIPE_BYTES);
        char *const argv[] = {"pw-play", "--raw", "--rate", "48000", "--channels", "1",
                              "--format", "s16", "--latency", "20ms", "-P", MIC_PROPS, "-", NULL};
        s.play_pid = spawn(argv, fds[0], -1, 1);
        close(fds[0]);
        s.play_fd = fds[1];
        set_nonblock(s.play_fd);
        set_default("default.configured.audio.source", "{\"name\":\"phone_mic\"}");
    }
    if (send(sock, "PSOK", 4, MSG_DONTWAIT | MSG_NOSIGNAL) != 4) {
        session_stop("could not acknowledge");
        return -1;
    }
    log_line("session started: speaker=%s mic=%s", flags & FLAG_SPEAKER ? "on" : "off",
             flags & FLAG_MIC ? "on" : "off");
    return 0;
}

// Queues speaker audio, then sends what the socket accepts. Overflow is dropped
// in whole frames so the stereo stream stays aligned.
static void speaker_pump(void) {
    if (s.rec_fd >= 0) {
        unsigned char buf[SPK_READ];
        ssize_t n = read(s.rec_fd, buf, sizeof buf);
        if (n == 0 || (n < 0 && errno != EAGAIN)) {
            session_stop("pw-record exited");
            return;
        }
        if (n > 0) {
            size_t take = (size_t)n;
            size_t room = sizeof s.spk - s.spk_len;
            if (s.spk_len + take > SPK_QUEUE) {
                size_t keep = take % SPK_FRAME;  // drop whole frames only
                if (keep > room) keep = 0;
                s.spk_dropped += take - keep;
                memcpy(s.spk + s.spk_len, buf + n - keep, keep);
                s.spk_len += keep;
            } else {
                memcpy(s.spk + s.spk_len, buf, take);
                s.spk_len += take;
            }
        }
    }
    if (s.sock >= 0 && s.spk_len > 0) {
        ssize_t n = send(s.sock, s.spk, s.spk_len, MSG_DONTWAIT | MSG_NOSIGNAL);
        if (n > 0) {
            s.spk_len -= (size_t)n;
            memmove(s.spk, s.spk + n, s.spk_len);
        } else if (n < 0 && errno != EAGAIN) {
            session_stop("phone disconnected");
        }
    }
}

// Forwards mic audio to pw-play, dropping it if pw-play is not keeping up (for
// example when no application is recording from "Phone Mic").
static void mic_pump(void) {
    ssize_t n = recv(s.sock, s.mic + s.mic_len, sizeof s.mic - s.mic_len, MSG_DONTWAIT);
    if (n == 0) {
        session_stop("phone disconnected");
        return;
    }
    if (n < 0) {
        if (errno != EAGAIN) session_stop("phone disconnected");
        return;
    }
    s.mic_len += (size_t)n;
    size_t whole = s.mic_len - s.mic_len % MIC_FRAME;
    if (s.play_fd >= 0 && whole > 0 && write(s.play_fd, s.mic, whole) < 0) {
        if (errno != EAGAIN) {
            session_stop("pw-play exited");
            return;
        }
        s.mic_dropped += whole;
    }
    memmove(s.mic, s.mic + whole, s.mic_len - whole);
    s.mic_len -= whole;
}

// ------------------------------------------------------------- adb device watch

static struct {
    int fd;
    int greeted;
    size_t len;
    char buf[4096];
    long retry_at;
} adb = {.fd = -1};

static void adb_close(void) {
    if (adb.fd >= 0) close(adb.fd);
    adb.fd = -1;
    adb.retry_at = now_ms() + ADB_RETRY_MS;
}

static void adb_connect(void) {
    struct sockaddr_in a = {.sin_family = AF_INET, .sin_port = htons(ADB_SERVER_PORT),
                            .sin_addr.s_addr = htonl(INADDR_LOOPBACK)};
    int fd = socket(AF_INET, SOCK_STREAM | SOCK_CLOEXEC, 0);
    if (fd < 0 || connect(fd, (struct sockaddr *)&a, sizeof a) < 0) {
        if (fd >= 0) close(fd);
        char *const argv[] = {(char *)adb_path(), "start-server", NULL};
        spawn(argv, -1, -1, 0);
        adb.retry_at = now_ms() + ADB_RETRY_MS;
        return;
    }
    static const char req[] = "0012host:track-devices";
    if (write(fd, req, sizeof req - 1) != sizeof req - 1) {
        close(fd);
        adb.retry_at = now_ms() + ADB_RETRY_MS;
        return;
    }
    adb.fd = fd;
    adb.greeted = 0;
    adb.len = 0;
    adb.retry_at = 0;
    set_nonblock(fd);
}

static void adb_handle_devices(char *list) {
    char port[32];
    snprintf(port, sizeof port, "tcp:%d", PORT);
    for (char *line = strtok(list, "\n"); line; line = strtok(NULL, "\n")) {
        char *tab = strchr(line, '\t');
        if (!tab) continue;
        *tab = 0;
        const char *state = tab + 1;
        log_line("phone %s: %s", line, state);
        if (strcmp(state, "device") == 0) {
            char *const argv[] = {(char *)adb_path(), "-s", line, "reverse", port, port, NULL};
            spawn(argv, -1, -1, 0);
        }
    }
}

static void adb_read(void) {
    ssize_t n = read(adb.fd, adb.buf + adb.len, sizeof adb.buf - 1 - adb.len);
    if (n == 0 || (n < 0 && errno != EAGAIN)) {
        log_line("lost adb server, retrying");
        adb_close();
        return;
    }
    if (n < 0) return;
    adb.len += (size_t)n;
    if (!adb.greeted) {
        if (adb.len < 4) return;
        if (memcmp(adb.buf, "OKAY", 4) != 0) {
            log_line("adb server refused device tracking");
            adb_close();
            return;
        }
        adb.greeted = 1;
        adb.len -= 4;
        memmove(adb.buf, adb.buf + 4, adb.len);
    }
    while (adb.len >= 4) {  // messages: 4 hex digits of length, then text
        char hex[5] = {adb.buf[0], adb.buf[1], adb.buf[2], adb.buf[3], 0};
        size_t size = strtoul(hex, NULL, 16);
        if (size + 4 >= sizeof adb.buf) {
            adb_close();
            return;
        }
        if (adb.len < size + 4) return;
        char saved = adb.buf[size + 4];
        adb.buf[size + 4] = 0;
        adb_handle_devices(adb.buf + 4);
        adb.buf[size + 4] = saved;
        adb.len -= size + 4;
        memmove(adb.buf, adb.buf + size + 4, adb.len);
    }
}

// --------------------------------------------------------------------- main loop

int main(void) {
    signal(SIGPIPE, SIG_IGN);
    signal(SIGCHLD, SIG_IGN);  // children are reaped automatically; pipes report exits
    setvbuf(stderr, NULL, _IOLBF, 0);

    int lfd = socket(AF_INET, SOCK_STREAM | SOCK_CLOEXEC, 0);
    int on = 1;
    setsockopt(lfd, SOL_SOCKET, SO_REUSEADDR, &on, sizeof on);
    struct sockaddr_in addr = {.sin_family = AF_INET, .sin_port = htons(PORT),
                               .sin_addr.s_addr = htonl(INADDR_LOOPBACK)};
    if (bind(lfd, (struct sockaddr *)&addr, sizeof addr) < 0 || listen(lfd, 2) < 0) {
        log_line("cannot listen on 127.0.0.1:%d: %s", PORT, strerror(errno));
        return 1;
    }
    log_line("listening on 127.0.0.1:%d", PORT);

    int pending = -1;  // accepted socket whose header has not arrived yet
    long pending_deadline = 0;
    adb_connect();

    for (;;) {
        struct pollfd p[6];
        int n = 0, i_l, i_pend = -1, i_adb = -1, i_sock = -1, i_rec = -1, i_play = -1;
        p[i_l = n++] = (struct pollfd){lfd, POLLIN, 0};
        if (pending >= 0) p[i_pend = n++] = (struct pollfd){pending, POLLIN, 0};
        if (adb.fd >= 0) p[i_adb = n++] = (struct pollfd){adb.fd, POLLIN, 0};
        if (s.sock >= 0) {
            p[i_sock = n++] = (struct pollfd){s.sock, POLLIN | (s.spk_len ? POLLOUT : 0), 0};
            if (s.rec_fd >= 0) p[i_rec = n++] = (struct pollfd){s.rec_fd, POLLIN, 0};
            if (s.play_fd >= 0) p[i_play = n++] = (struct pollfd){s.play_fd, 0, 0};
        }

        long now = now_ms(), timeout = -1;
        if (adb.fd < 0) timeout = adb.retry_at > now ? adb.retry_at - now : 0;
        if (pending >= 0) {
            long left = pending_deadline > now ? pending_deadline - now : 0;
            if (timeout < 0 || left < timeout) timeout = left;
        }

        if (poll(p, (nfds_t)n, (int)timeout) < 0) {
            if (errno == EINTR) continue;
            log_line("poll: %s", strerror(errno));
            return 1;
        }

        if (adb.fd < 0 && now_ms() >= adb.retry_at) adb_connect();
        else if (i_adb >= 0 && p[i_adb].revents) adb_read();

        if (i_sock >= 0 && s.sock >= 0 && (p[i_sock].revents & (POLLIN | POLLHUP | POLLERR)))
            mic_pump();
        if (s.sock >= 0 && i_play >= 0 && (p[i_play].revents & (POLLERR | POLLHUP)))
            session_stop("pw-play exited");
        if (s.sock >= 0 && ((i_rec >= 0 && p[i_rec].revents) ||
                            (i_sock >= 0 && (p[i_sock].revents & POLLOUT))))
            speaker_pump();

        if (i_pend >= 0 && (p[i_pend].revents || now_ms() >= pending_deadline)) {
            unsigned char h[HEADER_LEN];
            ssize_t got = recv(pending, h, sizeof h, MSG_PEEK | MSG_DONTWAIT);
            if (got == HEADER_LEN) {
                got = recv(pending, h, sizeof h, MSG_DONTWAIT);
                if (got == HEADER_LEN && memcmp(h, "PSPK", 4) == 0 && h[4] == PROTO_VERSION) {
                    session_stop("replaced by a new connection");
                    session_start(pending, h[5]);
                } else {
                    log_line("rejected connection with a bad header");
                    close(pending);
                }
                pending = -1;
            } else if (got == 0 || (got < 0 && errno != EAGAIN) ||
                       now_ms() >= pending_deadline) {
                close(pending);
                pending = -1;
            }
        } else if (pending >= 0 && now_ms() >= pending_deadline) {
            close(pending);
            pending = -1;
        }

        if (p[i_l].revents & POLLIN) {
            int c = accept4(lfd, NULL, NULL, SOCK_CLOEXEC);
            if (c >= 0) {
                if (pending >= 0) close(pending);
                int bytes = SOCK_BYTES;
                setsockopt(c, IPPROTO_TCP, TCP_NODELAY, &on, sizeof on);
                setsockopt(c, SOL_SOCKET, SO_SNDBUF, &bytes, sizeof bytes);
                setsockopt(c, SOL_SOCKET, SO_RCVBUF, &bytes, sizeof bytes);
                pending = c;
                pending_deadline = now_ms() + HEADER_TIMEOUT_MS;
            }
        }
    }
}

#define _POSIX_C_SOURCE 200809L

#include <errno.h>
#include <inttypes.h>
#include <stdarg.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>
#include <unistd.h>
#include <stdint.h>
#include <arpa/inet.h>
#include <netinet/in.h>
#include <sys/socket.h>

static int64_t timespec_diff_us(const struct timespec *start, const struct timespec *end)
{
    int64_t sec = (int64_t)(end->tv_sec - start->tv_sec);
    int64_t nsec = (int64_t)(end->tv_nsec - start->tv_nsec);
    if (nsec < 0) {
        sec--;
        nsec += 1000000000L;
    }
    return sec * 1000000L + nsec / 1000;
}

static int format_utc_timestamp(char *buf, size_t buflen)
{
    struct timespec ts;
    struct tm tm_utc;

    if (clock_gettime(CLOCK_REALTIME, &ts) == -1) {
        return -1;
    }
    if (gmtime_r(&ts.tv_sec, &tm_utc) == NULL) {
        return -1;
    }

    int ms = (int)(ts.tv_nsec / 1000000L);
    int written = snprintf(buf, buflen, "%04d-%02d-%02dT%02d:%02d:%02d.%03dZ",
                           tm_utc.tm_year + 1900,
                           tm_utc.tm_mon + 1,
                           tm_utc.tm_mday,
                           tm_utc.tm_hour,
                           tm_utc.tm_min,
                           tm_utc.tm_sec,
                           ms);
    if (written < 0 || (size_t)written >= buflen) {
        return -1;
    }
    return 0;
}

static void log_event(const char *event, const char *kv_fmt, ...)
{
    char ts[32];
    char kv[512];
    char line[768];

    if (format_utc_timestamp(ts, sizeof(ts)) != 0) {
        snprintf(ts, sizeof(ts), "0000-00-00T00:00:00.000Z");
    }

    kv[0] = '\0';
    if (kv_fmt != NULL && kv_fmt[0] != '\0') {
        va_list ap;
        va_start(ap, kv_fmt);
        vsnprintf(kv, sizeof(kv), kv_fmt, ap);
        va_end(ap);
    }

    if (kv[0] != '\0') {
        snprintf(line, sizeof(line),
                 "%s CLIENT session=C0 event=%s %s\n",
                 ts, event, kv);
    } else {
        snprintf(line, sizeof(line),
                 "%s CLIENT session=C0 event=%s\n",
                 ts, event);
    }

    fputs(line, stderr);
}

static int send_all(int fd, const char *buffer, size_t length)
{
    size_t remaining = length;

    while (remaining > 0) {
        ssize_t sent = send(fd, buffer, remaining, 0);
        if (sent == -1) {
            if (errno == EINTR) {
                continue;
            }
            return -1;
        }
        if (sent == 0) {
            errno = ECONNRESET;
            return -1;
        }
        buffer += sent;
        remaining -= (size_t)sent;
    }

    return 0;
}

static int get_connection_duration_us(const struct timespec *conn_start,
                                     int64_t *duration_us)
{
    struct timespec conn_end;

    if (clock_gettime(CLOCK_MONOTONIC, &conn_end) == -1) {
        return -1;
    }
    *duration_us = timespec_diff_us(conn_start, &conn_end);
    return 0;
}

static void emit_session_end(const char *reason,
                             int64_t messages_sent,
                             int64_t messages_received,
                             int64_t malformed_messages,
                             int64_t completed_exchanges,
                             int64_t min_rtt_us,
                             int64_t max_rtt_us,
                             int64_t total_rtt_us,
                             const struct timespec *conn_start,
                             int have_conn_start)
{
    int64_t duration_us = 0;
    int have_duration = 0;

    if (have_conn_start &&
        get_connection_duration_us(conn_start, &duration_us) == 0) {
        have_duration = 1;
    }

    if (completed_exchanges > 0 && have_duration) {
        log_event("SESSION_END",
                  "reason=%s messages_sent=%" PRId64
                  " messages_received=%" PRId64
                  " malformed_messages=%" PRId64
                  " rtt_min_us=%" PRId64
                  " rtt_max_us=%" PRId64
                  " rtt_avg_us=%" PRId64
                  " connection_duration_us=%" PRId64,
                  reason, messages_sent, messages_received, malformed_messages,
                  min_rtt_us, max_rtt_us, total_rtt_us / completed_exchanges,
                  duration_us);
    } else if (completed_exchanges > 0) {
        log_event("SESSION_END",
                  "reason=%s messages_sent=%" PRId64
                  " messages_received=%" PRId64
                  " malformed_messages=%" PRId64
                  " rtt_min_us=%" PRId64
                  " rtt_max_us=%" PRId64
                  " rtt_avg_us=%" PRId64,
                  reason, messages_sent, messages_received, malformed_messages,
                  min_rtt_us, max_rtt_us, total_rtt_us / completed_exchanges);
    } else if (have_duration) {
        log_event("SESSION_END",
                  "reason=%s messages_sent=%" PRId64
                  " messages_received=%" PRId64
                  " malformed_messages=%" PRId64
                  " connection_duration_us=%" PRId64,
                  reason, messages_sent, messages_received, malformed_messages,
                  duration_us);
    } else {
        log_event("SESSION_END",
                  "reason=%s messages_sent=%" PRId64
                  " messages_received=%" PRId64
                  " malformed_messages=%" PRId64,
                  reason, messages_sent, messages_received, malformed_messages);
    }
}

int main(int argc, char *argv[])
{
    int sock_fd = -1;
    struct sockaddr_in addr;
    int connected = 0;
    int session_end_emitted = 0;
    int exit_code = EXIT_FAILURE;
    const char *reason = "internal_error";
    struct timespec conn_start;
    int have_conn_start = 0;
    char buffer[64];
    int64_t completed_exchanges = 0;
    int64_t min_rtt_us = INT64_MAX;
    int64_t max_rtt_us = 0;
    int64_t total_rtt_us = 0;
    int64_t messages_sent = 0;
    int64_t messages_received = 0;
    int64_t malformed_messages = 0;

    if (argc != 7) {
        fprintf(stderr, "usage: ./net_client --host <host> --port <port> --count <count>\n");
        return EXIT_FAILURE;
    }

    if (strcmp(argv[1], "--host") != 0 || strcmp(argv[3], "--port") != 0 ||
        strcmp(argv[5], "--count") != 0) {
        fprintf(stderr, "usage: ./net_client --host <host> --port <port> --count <count>\n");
        return EXIT_FAILURE;
    }

    const char *host = argv[2];

    char *end;
    errno = 0;
    long port = strtol(argv[4], &end, 10);
    if (errno != 0 || end == argv[4] || *end != '\0' || port < 1 || port > 65535) {
        fprintf(stderr, "error: invalid port '%s'\n", argv[4]);
        return EXIT_FAILURE;
    }

    errno = 0;
    long count = strtol(argv[6], &end, 10);
    if (errno != 0 || end == argv[6] || *end != '\0' || count < 1 || count > 1000000) {
        fprintf(stderr, "error: invalid count '%s'\n", argv[6]);
        return EXIT_FAILURE;
    }

    memset(&addr, 0, sizeof(addr));

    addr.sin_family = AF_INET;
    addr.sin_port = htons((uint16_t)port);

    if (inet_pton(AF_INET, host, &addr.sin_addr) != 1) {
        fprintf(stderr, "inet_pton: invalid address\n");
        return EXIT_FAILURE;
    }

    log_event("CONNECT_ATTEMPT", "host=%s port=%ld", host, port);

    sock_fd = socket(AF_INET, SOCK_STREAM, 0);
    if (sock_fd == -1) {
        int saved_errno = errno;
        log_event("CONNECT_FAIL",
                  "host=%s port=%ld error_code=%d error_text=\"%s\"",
                  host, port, saved_errno, strerror(saved_errno));
        return EXIT_FAILURE;
    }

    if (connect(sock_fd, (struct sockaddr *)&addr, sizeof(addr)) == -1) {
        int saved_errno = errno;
        log_event("CONNECT_FAIL",
                  "host=%s port=%ld error_code=%d error_text=\"%s\"",
                  host, port, saved_errno, strerror(saved_errno));
        close(sock_fd);
        return EXIT_FAILURE;
    }

    connected = 1;
    log_event("CONNECT_OK", "host=%s port=%ld", host, port);

    if (clock_gettime(CLOCK_MONOTONIC, &conn_start) == -1) {
        int saved_errno = errno;
        reason = "internal_error";
        log_event("ERROR",
                  "op=clock_gettime error_code=%d error_text=\"%s\"",
                  saved_errno, strerror(saved_errno));
        goto cleanup;
    }
    have_conn_start = 1;

    for (long sent_seq = 1; sent_seq <= count; sent_seq++) {
        char request[64];
        int req_written = snprintf(request, sizeof(request), "PING %ld\n", sent_seq);
        if (req_written < 0 || (size_t)req_written >= sizeof(request)) {
            reason = "internal_error";
            log_event("ERROR",
                      "op=snprintf error_text=\"request formatting failed\"");
            goto cleanup;
        }

        struct timespec start;
        if (clock_gettime(CLOCK_MONOTONIC, &start) == -1) {
            int saved_errno = errno;
            reason = "internal_error";
            log_event("ERROR",
                      "op=clock_gettime error_code=%d error_text=\"%s\"",
                      saved_errno, strerror(saved_errno));
            goto cleanup;
        }

        if (send_all(sock_fd, request, (size_t)req_written) == -1) {
            int saved_errno = errno;
            reason = "send_error";
            log_event("ERROR",
                      "op=send error_code=%d error_text=\"%s\"",
                      saved_errno, strerror(saved_errno));
            goto cleanup;
        }
        messages_sent++;

        size_t len = 0;

        for (;;) {
            char c;
            ssize_t n = recv(sock_fd, &c, 1, 0);
            if (n == -1) {
                int saved_errno = errno;
                reason = "recv_error";
                log_event("ERROR",
                          "op=recv error_code=%d error_text=\"%s\"",
                          saved_errno, strerror(saved_errno));
                goto cleanup;
            }
            if (n == 0) {
                malformed_messages++;
                reason = "malformed";
                log_event("ERROR",
                          "op=protocol error_text=\"incomplete response\"");
                goto cleanup;
            }
            if (c == '\n') {
                buffer[len] = '\0';
                break;
            }
            if (len >= sizeof(buffer) - 1) {
                malformed_messages++;
                reason = "malformed";
                log_event("ERROR",
                          "op=protocol error_text=\"response too long\"");
                goto cleanup;
            }
            buffer[len++] = c;
        }

        const char *prefix = "PONG ";
        if (strncmp(buffer, prefix, strlen(prefix)) != 0) {
            malformed_messages++;
            reason = "malformed";
            log_event("ERROR",
                      "op=protocol error_text=\"invalid response\"");
            goto cleanup;
        }

        char *seq_start = buffer + strlen(prefix);
        errno = 0;
        long recv_seq = strtol(seq_start, &end, 10);
        if (errno != 0 || end == seq_start || *end != '\0' || recv_seq < 1) {
            int saved_errno = errno;
            malformed_messages++;
            reason = "malformed";
            if (saved_errno != 0) {
                log_event("ERROR",
                          "op=protocol error_code=%d error_text=\"%s\"",
                          saved_errno, strerror(saved_errno));
            } else {
                log_event("ERROR",
                          "op=protocol error_text=\"invalid sequence\"");
            }
            goto cleanup;
        }

        if (recv_seq != sent_seq) {
            malformed_messages++;
            reason = "malformed";
            log_event("ERROR",
                      "op=protocol error_text=\"unexpected sequence\"");
            goto cleanup;
        }

        messages_received++;

        struct timespec end_ts;
        if (clock_gettime(CLOCK_MONOTONIC, &end_ts) == -1) {
            int saved_errno = errno;
            reason = "internal_error";
            log_event("ERROR",
                      "op=clock_gettime error_code=%d error_text=\"%s\"",
                      saved_errno, strerror(saved_errno));
            goto cleanup;
        }

        int64_t rtt_us = timespec_diff_us(&start, &end_ts);
        completed_exchanges++;
        if (rtt_us < min_rtt_us) {
            min_rtt_us = rtt_us;
        }
        if (rtt_us > max_rtt_us) {
            max_rtt_us = rtt_us;
        }
        total_rtt_us += rtt_us;
    }

    if (send_all(sock_fd, "QUIT\n", 5) == -1) {
        int saved_errno = errno;
        reason = "send_error";
        log_event("ERROR",
                  "op=send error_code=%d error_text=\"%s\"",
                  saved_errno, strerror(saved_errno));
        goto cleanup;
    }

    {
        size_t len = 0;

        for (;;) {
            char c;
            ssize_t n = recv(sock_fd, &c, 1, 0);
            if (n == -1) {
                int saved_errno = errno;
                reason = "recv_error";
                log_event("ERROR",
                          "op=recv error_code=%d error_text=\"%s\"",
                          saved_errno, strerror(saved_errno));
                goto cleanup;
            }
            if (n == 0) {
                malformed_messages++;
                reason = "malformed";
                log_event("ERROR",
                          "op=protocol error_text=\"incomplete response\"");
                goto cleanup;
            }
            if (c == '\n') {
                buffer[len] = '\0';
                break;
            }
            if (len >= sizeof(buffer) - 1) {
                malformed_messages++;
                reason = "malformed";
                log_event("ERROR",
                          "op=protocol error_text=\"response too long\"");
                goto cleanup;
            }
            buffer[len++] = c;
        }
    }

    if (strcmp(buffer, "BYE") != 0) {
        malformed_messages++;
        reason = "malformed";
        log_event("ERROR",
                  "op=protocol error_text=\"invalid BYE\"");
        goto cleanup;
    }

    reason = "bye";
    exit_code = EXIT_SUCCESS;

cleanup:
    if (connected && !session_end_emitted) {
        emit_session_end(reason, messages_sent, messages_received,
                         malformed_messages, completed_exchanges,
                         min_rtt_us, max_rtt_us, total_rtt_us,
                         &conn_start, have_conn_start);
        session_end_emitted = 1;
    }

    if (sock_fd != -1) {
        close(sock_fd);
    }

    return exit_code;
}

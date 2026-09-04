#define _POSIX_C_SOURCE 200809L

#include <errno.h>
#include <inttypes.h>
#include <pthread.h>
#include <stdarg.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include <stdint.h>
#include <time.h>
#include <arpa/inet.h>
#include <netinet/in.h>
#include <sys/socket.h>

static int active_connections = 0;
static uint64_t next_client_id = 1;
static pthread_mutex_t server_state_mutex = PTHREAD_MUTEX_INITIALIZER;
static pthread_mutex_t log_mutex = PTHREAD_MUTEX_INITIALIZER;

struct worker_arg {
    int client_fd;
    uint64_t client_id;
    char peer[INET_ADDRSTRLEN + 16];
};

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

static void log_event(uint64_t client_id, const char *event, const char *kv_fmt, ...)
{
    char ts[32];
    char kv[512];
    char line[768];
    int rc;

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
                 "%s SERVER session=C%" PRIu64 " event=%s %s\n",
                 ts, client_id, event, kv);
    } else {
        snprintf(line, sizeof(line),
                 "%s SERVER session=C%" PRIu64 " event=%s\n",
                 ts, client_id, event);
    }

    rc = pthread_mutex_lock(&log_mutex);
    if (rc != 0) {
        fprintf(stderr, "pthread_mutex_lock (log): %s\n", strerror(rc));
        fputs(line, stderr);
        return;
    }
    fputs(line, stderr);
    rc = pthread_mutex_unlock(&log_mutex);
    if (rc != 0) {
        fprintf(stderr, "pthread_mutex_unlock (log): %s\n", strerror(rc));
    }
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

static void emit_session_end(uint64_t client_id,
                             const char *reason,
                             int64_t messages_sent,
                             int64_t messages_received,
                             int64_t malformed_messages,
                             const struct timespec *conn_start,
                             int have_conn_start)
{
    if (have_conn_start) {
        struct timespec conn_end;
        if (clock_gettime(CLOCK_MONOTONIC, &conn_end) == 0) {
            log_event(client_id, "SESSION_END",
                      "reason=%s messages_sent=%" PRId64
                      " messages_received=%" PRId64
                      " malformed_messages=%" PRId64
                      " connection_duration_us=%" PRId64,
                      reason, messages_sent, messages_received,
                      malformed_messages,
                      timespec_diff_us(conn_start, &conn_end));
            return;
        }
    }

    log_event(client_id, "SESSION_END",
              "reason=%s messages_sent=%" PRId64
              " messages_received=%" PRId64
              " malformed_messages=%" PRId64,
              reason, messages_sent, messages_received,
              malformed_messages);
}

static void *client_thread(void *arg)
{
    struct worker_arg *warg = arg;
    int client_fd = warg->client_fd;
    uint64_t client_id = warg->client_id;
    char peer[sizeof(warg->peer)];
    memcpy(peer, warg->peer, sizeof(peer));
    free(warg);

    int rc;
    int active_n = 0;
    int counted_active = 0;
    int disconnect_active_unknown = 0;
    const char *reason = "internal_error";
    struct timespec conn_start;
    int have_conn_start = 0;
    char buffer[64];
    char *end;
    long expected_seq = 1;
    int completed_exchanges = 0;
    int64_t messages_sent = 0;
    int64_t messages_received = 0;
    int64_t malformed_messages = 0;

    rc = pthread_mutex_lock(&server_state_mutex);
    if (rc != 0) {
        reason = "internal_error";
        log_event(client_id, "ERROR",
                  "op=pthread_mutex_lock error_code=%d error_text=\"%s\"",
                  rc, strerror(rc));
        disconnect_active_unknown = 1;
        goto worker_exit;
    }
    active_n = ++active_connections;
    counted_active = 1;
    rc = pthread_mutex_unlock(&server_state_mutex);
    if (rc != 0) {
        log_event(client_id, "ERROR",
                  "op=pthread_mutex_unlock error_code=%d error_text=\"%s\"",
                  rc, strerror(rc));
    }

    log_event(client_id, "CONNECT", "peer=%s active_connections=%d", peer, active_n);

    if (clock_gettime(CLOCK_MONOTONIC, &conn_start) == -1) {
        int saved_errno = errno;
        reason = "internal_error";
        log_event(client_id, "ERROR",
                  "op=clock_gettime error_code=%d error_text=\"%s\"",
                  saved_errno, strerror(saved_errno));
        goto worker_exit;
    }
    have_conn_start = 1;

    for (;;) {
        size_t len = 0;

        for (;;) {
            char c;
            ssize_t nr = recv(client_fd, &c, 1, 0);
            if (nr == -1) {
                int saved_errno = errno;
                reason = "recv_error";
                log_event(client_id, "ERROR",
                          "op=recv error_code=%d error_text=\"%s\"",
                          saved_errno, strerror(saved_errno));
                goto worker_exit;
            }
            if (nr == 0) {
                if (len > 0) {
                    malformed_messages++;
                    reason = "malformed";
                    log_event(client_id, "ERROR",
                              "op=protocol error_text=\"incomplete request\"");
                    goto worker_exit;
                }
                if (completed_exchanges == 0) {
                    reason = "peer_closed_before_request";
                } else {
                    reason = "peer_closed";
                }
                goto worker_exit;
            }
            if (c == '\n') {
                buffer[len] = '\0';
                break;
            }
            if (len >= sizeof(buffer) - 1) {
                malformed_messages++;
                reason = "malformed";
                log_event(client_id, "ERROR",
                          "op=protocol error_text=\"message too long\"");
                goto worker_exit;
            }
            buffer[len++] = c;
        }

        if (strcmp(buffer, "QUIT") == 0) {
            if (send_all(client_fd, "BYE\n", 4) == -1) {
                int saved_errno = errno;
                reason = "send_error";
                log_event(client_id, "ERROR",
                          "op=send error_code=%d error_text=\"%s\"",
                          saved_errno, strerror(saved_errno));
                goto worker_exit;
            }
            reason = "quit";
            goto worker_exit;
        }

        const char *prefix = "PING ";
        if (strncmp(buffer, prefix, strlen(prefix)) != 0) {
            malformed_messages++;
            reason = "malformed";
            log_event(client_id, "ERROR",
                      "op=protocol error_text=\"invalid request\"");
            goto worker_exit;
        }

        char *seq_start = buffer + strlen(prefix);
        errno = 0;
        long seq = strtol(seq_start, &end, 10);
        if (errno != 0 || end == seq_start || *end != '\0' || seq < 1) {
            int saved_errno = errno;
            malformed_messages++;
            reason = "malformed";
            if (saved_errno != 0) {
                log_event(client_id, "ERROR",
                          "op=protocol error_code=%d error_text=\"%s\"",
                          saved_errno, strerror(saved_errno));
            } else {
                log_event(client_id, "ERROR",
                          "op=protocol error_text=\"invalid sequence\"");
            }
            goto worker_exit;
        }

        if (seq != expected_seq) {
            malformed_messages++;
            reason = "malformed";
            log_event(client_id, "ERROR",
                      "op=protocol error_text=\"unexpected sequence\"");
            goto worker_exit;
        }

        messages_received++;

        char response[64];
        int written = snprintf(response, sizeof(response), "PONG %ld\n", seq);
        if (written < 0 || (size_t)written >= sizeof(response)) {
            reason = "internal_error";
            log_event(client_id, "ERROR",
                      "op=snprintf error_text=\"response formatting failed\"");
            goto worker_exit;
        }

        if (send_all(client_fd, response, (size_t)written) == -1) {
            int saved_errno = errno;
            reason = "send_error";
            log_event(client_id, "ERROR",
                      "op=send error_code=%d error_text=\"%s\"",
                      saved_errno, strerror(saved_errno));
            goto worker_exit;
        }

        messages_sent++;
        expected_seq++;
        completed_exchanges++;
    }

worker_exit:
    emit_session_end(client_id, reason, messages_sent, messages_received,
                     malformed_messages, &conn_start, have_conn_start);

    if (counted_active) {
        rc = pthread_mutex_lock(&server_state_mutex);
        if (rc != 0) {
            log_event(client_id, "ERROR",
                      "op=pthread_mutex_lock error_code=%d error_text=\"%s\"",
                      rc, strerror(rc));
            disconnect_active_unknown = 1;
        } else {
            active_n = --active_connections;
            rc = pthread_mutex_unlock(&server_state_mutex);
            if (rc != 0) {
                log_event(client_id, "ERROR",
                          "op=pthread_mutex_unlock error_code=%d error_text=\"%s\"",
                          rc, strerror(rc));
            }
        }
    } else {
        disconnect_active_unknown = 1;
    }

    if (disconnect_active_unknown) {
        log_event(client_id, "DISCONNECT", "active_connections=unknown");
    } else {
        log_event(client_id, "DISCONNECT", "active_connections=%d", active_n);
    }

    close(client_fd);
    return NULL;
}

int main(int argc, char *argv[])
{
    int server_fd;
    struct sockaddr_in addr;
    pthread_attr_t attr;
    int rc;

    if (argc != 3) {
        fprintf(stderr, "usage: ./net_server --port <port>\n");
        return EXIT_FAILURE;
    }

    if (strcmp(argv[1], "--port") != 0) {
        fprintf(stderr, "usage: ./net_server --port <port>\n");
        return EXIT_FAILURE;
    }

    char *end;
    errno = 0;
    long port = strtol(argv[2], &end, 10);
    if (errno != 0 || end == argv[2] || *end != '\0' || port < 1 || port > 65535) {
        fprintf(stderr, "error: invalid port '%s'\n", argv[2]);
        return EXIT_FAILURE;
    }

    server_fd = socket(AF_INET, SOCK_STREAM, 0);
    if (server_fd == -1) {
        perror("socket");
        return EXIT_FAILURE;
    }

    memset(&addr, 0, sizeof(addr));
    addr.sin_family = AF_INET;
    addr.sin_port = htons((uint16_t)port);
    if (inet_pton(AF_INET, "127.0.0.1", &addr.sin_addr) != 1) {
        fprintf(stderr, "inet_pton: invalid address\n");
        close(server_fd);
        return EXIT_FAILURE;
    }

    if (bind(server_fd, (struct sockaddr *)&addr, sizeof(addr)) == -1) {
        perror("bind");
        close(server_fd);
        return EXIT_FAILURE;
    }

    if (listen(server_fd, 10) == -1) {
        perror("listen");
        close(server_fd);
        return EXIT_FAILURE;
    }

    rc = pthread_attr_init(&attr);
    if (rc != 0) {
        fprintf(stderr, "pthread_attr_init: %s\n", strerror(rc));
        close(server_fd);
        return EXIT_FAILURE;
    }

    rc = pthread_attr_setdetachstate(&attr, PTHREAD_CREATE_DETACHED);
    if (rc != 0) {
        fprintf(stderr, "pthread_attr_setdetachstate: %s\n", strerror(rc));
        pthread_attr_destroy(&attr);
        close(server_fd);
        return EXIT_FAILURE;
    }

    for (;;) {
        struct sockaddr_in client_addr;
        socklen_t client_addr_len = sizeof(client_addr);
        int client_fd = accept(server_fd, (struct sockaddr *)&client_addr,
                               &client_addr_len);
        if (client_fd == -1) {
            if (errno == EINTR) {
                continue;
            }
            perror("accept");
            pthread_attr_destroy(&attr);
            close(server_fd);
            return EXIT_FAILURE;
        }

        uint64_t client_id = 0;
        int have_client_id = 0;

        rc = pthread_mutex_lock(&server_state_mutex);
        if (rc != 0) {
            fprintf(stderr, "pthread_mutex_lock: %s\n", strerror(rc));
            close(client_fd);
            continue;
        }
        client_id = next_client_id++;
        have_client_id = 1;
        rc = pthread_mutex_unlock(&server_state_mutex);
        if (rc != 0) {
            fprintf(stderr, "pthread_mutex_unlock: %s\n", strerror(rc));
        }

        struct worker_arg *warg = malloc(sizeof(*warg));
        if (warg == NULL) {
            if (have_client_id) {
                log_event(client_id, "SPAWN_FAIL",
                          "error_text=\"out of memory\"");
            }
            close(client_fd);
            continue;
        }

        warg->client_fd = client_fd;
        warg->client_id = client_id;

        {
            char ip[INET_ADDRSTRLEN];
            if (inet_ntop(AF_INET, &client_addr.sin_addr, ip, sizeof(ip)) == NULL) {
                snprintf(warg->peer, sizeof(warg->peer), "unknown:%u",
                         ntohs(client_addr.sin_port));
            } else {
                snprintf(warg->peer, sizeof(warg->peer), "%s:%u",
                         ip, ntohs(client_addr.sin_port));
            }
        }

        pthread_t thread;
        rc = pthread_create(&thread, &attr, client_thread, warg);
        if (rc != 0) {
            log_event(client_id, "SPAWN_FAIL",
                      "error_code=%d error_text=\"%s\"",
                      rc, strerror(rc));
            free(warg);
            close(client_fd);
            continue;
        }
    }
}

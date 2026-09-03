#define _POSIX_C_SOURCE 200809L

#include <errno.h>
#include <inttypes.h>
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

static int send_all(int fd, const char *buffer, size_t length)
{
    size_t remaining = length;

    while (remaining > 0) {
        ssize_t sent = send(fd, buffer, remaining, 0);
        if (sent == -1) {
            if (errno == EINTR) {
                continue;
            }
            perror("send");
            return -1;
        }
        if (sent == 0) {
            fprintf(stderr, "send: connection closed\n");
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
        perror("clock_gettime");
        return -1;
    }
    *duration_us = timespec_diff_us(conn_start, &conn_end);
    return 0;
}

static void print_session_counters(int64_t messages_sent,
                                   int64_t messages_received,
                                   int64_t malformed_messages,
                                   int64_t duration_us)
{
    printf("messages_sent: %" PRId64 "\n", messages_sent);
    printf("messages_received: %" PRId64 "\n", messages_received);
    printf("malformed_messages: %" PRId64 "\n", malformed_messages);
    printf("connection_duration_us: %" PRId64 "\n", duration_us);
}

int main(int argc, char *argv[])
{
    int sock_fd;
    struct sockaddr_in addr;

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

    sock_fd = socket(AF_INET, SOCK_STREAM, 0);

    if (sock_fd == -1) {
        perror("socket");
        return EXIT_FAILURE;
    }

    if (connect(sock_fd, (struct sockaddr *)&addr, sizeof(addr)) == -1) {
        perror("connect");
        close(sock_fd);
        return EXIT_FAILURE;
    }

    struct timespec conn_start;
    if (clock_gettime(CLOCK_MONOTONIC, &conn_start) == -1) {
        perror("clock_gettime");
        close(sock_fd);
        return EXIT_FAILURE;
    }

    char buffer[64];
    int64_t completed_exchanges = 0;
    int64_t min_rtt_us = INT64_MAX;
    int64_t max_rtt_us = 0;
    int64_t total_rtt_us = 0;
    int64_t messages_sent = 0;
    int64_t messages_received = 0;
    int64_t malformed_messages = 0;

    for (long sent_seq = 1; sent_seq <= count; sent_seq++) {
        char request[64];
        int req_written = snprintf(request, sizeof(request), "PING %ld\n", sent_seq);
        if (req_written < 0 || (size_t)req_written >= sizeof(request)) {
            fprintf(stderr, "request formatting failed\n");
            close(sock_fd);
            return EXIT_FAILURE;
        }

        struct timespec start;
        if (clock_gettime(CLOCK_MONOTONIC, &start) == -1) {
            perror("clock_gettime");
            close(sock_fd);
            return EXIT_FAILURE;
        }

        if (send_all(sock_fd, request, (size_t)req_written) == -1) {
            close(sock_fd);
            return EXIT_FAILURE;
        }
        messages_sent++;

        size_t len = 0;

        for (;;) {
            char c;
            ssize_t n = recv(sock_fd, &c, 1, 0);
            if (n == -1) {
                perror("recv");
                close(sock_fd);
                return EXIT_FAILURE;
            }
            if (n == 0) {
                fprintf(stderr, "incomplete response\n");
                malformed_messages++;
                {
                    int64_t duration_us;

                    if (get_connection_duration_us(&conn_start, &duration_us) == 0) {
                        print_session_counters(messages_sent, messages_received,
                                               malformed_messages, duration_us);
                    }
                }
                close(sock_fd);
                return EXIT_FAILURE;
            }
            if (c == '\n') {
                buffer[len] = '\0';
                break;
            }
            if (len >= sizeof(buffer) - 1) {
                fprintf(stderr, "response too long\n");
                malformed_messages++;
                {
                    int64_t duration_us;

                    if (get_connection_duration_us(&conn_start, &duration_us) == 0) {
                        print_session_counters(messages_sent, messages_received,
                                               malformed_messages, duration_us);
                    }
                }
                close(sock_fd);
                return EXIT_FAILURE;
            }
            buffer[len++] = c;
        }

        const char *prefix = "PONG ";
        if (strncmp(buffer, prefix, strlen(prefix)) != 0) {
            fprintf(stderr, "invalid response\n");
            malformed_messages++;
            {
                int64_t duration_us;

                if (get_connection_duration_us(&conn_start, &duration_us) == 0) {
                    print_session_counters(messages_sent, messages_received,
                                           malformed_messages, duration_us);
                }
            }
            close(sock_fd);
            return EXIT_FAILURE;
        }

        char *seq_start = buffer + strlen(prefix);
        errno = 0;
        long recv_seq = strtol(seq_start, &end, 10);
        if (errno != 0 || end == seq_start || *end != '\0' || recv_seq < 1) {
            fprintf(stderr, "invalid response\n");
            malformed_messages++;
            {
                int64_t duration_us;

                if (get_connection_duration_us(&conn_start, &duration_us) == 0) {
                    print_session_counters(messages_sent, messages_received,
                                           malformed_messages, duration_us);
                }
            }
            close(sock_fd);
            return EXIT_FAILURE;
        }

        if (recv_seq != sent_seq) {
            fprintf(stderr, "unexpected sequence\n");
            malformed_messages++;
            {
                int64_t duration_us;

                if (get_connection_duration_us(&conn_start, &duration_us) == 0) {
                    print_session_counters(messages_sent, messages_received,
                                           malformed_messages, duration_us);
                }
            }
            close(sock_fd);
            return EXIT_FAILURE;
        }

        messages_received++;

        struct timespec end_ts;
        if (clock_gettime(CLOCK_MONOTONIC, &end_ts) == -1) {
            perror("clock_gettime");
            close(sock_fd);
            return EXIT_FAILURE;
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

        printf("%s\n", buffer);
    }

    if (send_all(sock_fd, "QUIT\n", 5) == -1) {
        close(sock_fd);
        return EXIT_FAILURE;
    }

    size_t len = 0;

    for (;;) {
        char c;
        ssize_t n = recv(sock_fd, &c, 1, 0);
        if (n == -1) {
            perror("recv");
            close(sock_fd);
            return EXIT_FAILURE;
        }
        if (n == 0) {
            fprintf(stderr, "incomplete response\n");
            malformed_messages++;
            {
                int64_t duration_us;

                if (get_connection_duration_us(&conn_start, &duration_us) == 0) {
                    print_session_counters(messages_sent, messages_received,
                                           malformed_messages, duration_us);
                }
            }
            close(sock_fd);
            return EXIT_FAILURE;
        }
        if (c == '\n') {
            buffer[len] = '\0';
            break;
        }
        if (len >= sizeof(buffer) - 1) {
            fprintf(stderr, "response too long\n");
            malformed_messages++;
            {
                int64_t duration_us;

                if (get_connection_duration_us(&conn_start, &duration_us) == 0) {
                    print_session_counters(messages_sent, messages_received,
                                           malformed_messages, duration_us);
                }
            }
            close(sock_fd);
            return EXIT_FAILURE;
        }
        buffer[len++] = c;
    }

    if (strcmp(buffer, "BYE") != 0) {
        fprintf(stderr, "invalid response\n");
        malformed_messages++;
        {
            int64_t duration_us;

            if (get_connection_duration_us(&conn_start, &duration_us) == 0) {
                print_session_counters(messages_sent, messages_received,
                                       malformed_messages, duration_us);
            }
        }
        close(sock_fd);
        return EXIT_FAILURE;
    }

    int64_t avg_rtt_us = total_rtt_us / completed_exchanges;
    int64_t duration_us;

    if (get_connection_duration_us(&conn_start, &duration_us) == -1) {
        close(sock_fd);
        return EXIT_FAILURE;
    }
    printf("exchanges: %" PRId64 "\n", completed_exchanges);
    printf("rtt_min_us: %" PRId64 "\n", min_rtt_us);
    printf("rtt_max_us: %" PRId64 "\n", max_rtt_us);
    printf("rtt_avg_us: %" PRId64 "\n", avg_rtt_us);
    print_session_counters(messages_sent, messages_received,
                           malformed_messages, duration_us);

    close(sock_fd);

    return EXIT_SUCCESS;
}

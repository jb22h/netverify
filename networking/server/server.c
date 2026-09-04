#define _POSIX_C_SOURCE 200809L

#include <errno.h>
#include <inttypes.h>
#include <pthread.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include <stdint.h>
#include <time.h>
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

static int print_session_summary(int64_t messages_sent,
                                 int64_t messages_received,
                                 int64_t malformed_messages,
                                 const struct timespec *conn_start)
{
    struct timespec conn_end;

    if (clock_gettime(CLOCK_MONOTONIC, &conn_end) == -1) {
        perror("clock_gettime");
        return -1;
    }

    printf("messages_sent: %" PRId64 "\n", messages_sent);
    printf("messages_received: %" PRId64 "\n", messages_received);
    printf("malformed_messages: %" PRId64 "\n", malformed_messages);
    printf("connection_duration_us: %" PRId64 "\n",
           timespec_diff_us(conn_start, &conn_end));
    return 0;
}

static void *client_thread(void *arg)
{
    int client_fd = *(int *)arg;
    free(arg);

    struct timespec conn_start;
    if (clock_gettime(CLOCK_MONOTONIC, &conn_start) == -1) {
        perror("clock_gettime");
        close(client_fd);
        return NULL;
    }

    char buffer[64];
    char *end;
    long expected_seq = 1;
    int completed_exchanges = 0;
    int64_t messages_sent = 0;
    int64_t messages_received = 0;
    int64_t malformed_messages = 0;

    for (;;) {
        size_t len = 0;

        for (;;) {
            char c;
            ssize_t n = recv(client_fd, &c, 1, 0);
            if (n == -1) {
                perror("recv");
                close(client_fd);
                return NULL;
            }
            if (n == 0) {
                if (len > 0) {
                    fprintf(stderr, "incomplete request\n");
                    malformed_messages++;
                    print_session_summary(messages_sent, messages_received,
                                          malformed_messages, &conn_start);
                    close(client_fd);
                    return NULL;
                }
                if (completed_exchanges == 0) {
                    fprintf(stderr, "client closed before sending a request\n");
                    close(client_fd);
                    return NULL;
                }
                goto session_done;
            }
            if (c == '\n') {
                buffer[len] = '\0';
                break;
            }
            if (len >= sizeof(buffer) - 1) {
                fprintf(stderr, "message too long\n");
                malformed_messages++;
                print_session_summary(messages_sent, messages_received,
                                      malformed_messages, &conn_start);
                close(client_fd);
                return NULL;
            }
            buffer[len++] = c;
        }

        if (strcmp(buffer, "QUIT") == 0) {
            if (send_all(client_fd, "BYE\n", 4) == -1) {
                close(client_fd);
                return NULL;
            }
            goto session_done;
        }

        const char *prefix = "PING ";
        if (strncmp(buffer, prefix, strlen(prefix)) != 0) {
            fprintf(stderr, "invalid request\n");
            malformed_messages++;
            print_session_summary(messages_sent, messages_received,
                                  malformed_messages, &conn_start);
            close(client_fd);
            return NULL;
        }

        char *seq_start = buffer + strlen(prefix);
        errno = 0;
        long seq = strtol(seq_start, &end, 10);
        if (errno != 0 || end == seq_start || *end != '\0' || seq < 1) {
            fprintf(stderr, "invalid request\n");
            malformed_messages++;
            print_session_summary(messages_sent, messages_received,
                                  malformed_messages, &conn_start);
            close(client_fd);
            return NULL;
        }

        if (seq != expected_seq) {
            fprintf(stderr, "unexpected sequence\n");
            malformed_messages++;
            print_session_summary(messages_sent, messages_received,
                                  malformed_messages, &conn_start);
            close(client_fd);
            return NULL;
        }

        messages_received++;
        printf("%s\n", buffer);

        char response[64];
        int written = snprintf(response, sizeof(response), "PONG %ld\n", seq);
        if (written < 0 || (size_t)written >= sizeof(response)) {
            fprintf(stderr, "response formatting failed\n");
            close(client_fd);
            return NULL;
        }

        if (send_all(client_fd, response, (size_t)written) == -1) {
            close(client_fd);
            return NULL;
        }

        messages_sent++;
        expected_seq++;
        completed_exchanges++;
    }

session_done:
    print_session_summary(messages_sent, messages_received,
                          malformed_messages, &conn_start);
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

        int *arg = malloc(sizeof(*arg));
        if (arg == NULL) {
            fprintf(stderr, "malloc: out of memory\n");
            close(client_fd);
            continue;
        }
        *arg = client_fd;

        pthread_t thread;
        rc = pthread_create(&thread, &attr, client_thread, arg);
        if (rc != 0) {
            fprintf(stderr, "pthread_create: %s\n", strerror(rc));
            free(arg);
            close(client_fd);
            continue;
        }
    }
}

#include <errno.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include <stdint.h>
#include <arpa/inet.h>
#include <netinet/in.h>
#include <sys/socket.h>

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

    char buffer[64];

    for (long sent_seq = 1; sent_seq <= count; sent_seq++) {
        char request[64];
        int req_written = snprintf(request, sizeof(request), "PING %ld\n", sent_seq);
        if (req_written < 0 || (size_t)req_written >= sizeof(request)) {
            fprintf(stderr, "request formatting failed\n");
            close(sock_fd);
            return EXIT_FAILURE;
        }

        ssize_t sent = send(sock_fd, request, (size_t)req_written, 0);
        if (sent == -1) {
            perror("send");
            close(sock_fd);
            return EXIT_FAILURE;
        }
        if (sent != req_written) {
            fprintf(stderr, "send: incomplete write\n");
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
                close(sock_fd);
                return EXIT_FAILURE;
            }
            if (c == '\n') {
                buffer[len] = '\0';
                break;
            }
            if (len >= sizeof(buffer) - 1) {
                fprintf(stderr, "response too long\n");
                close(sock_fd);
                return EXIT_FAILURE;
            }
            buffer[len++] = c;
        }

        const char *prefix = "PONG ";
        if (strncmp(buffer, prefix, strlen(prefix)) != 0) {
            fprintf(stderr, "invalid response\n");
            close(sock_fd);
            return EXIT_FAILURE;
        }

        char *seq_start = buffer + strlen(prefix);
        errno = 0;
        long recv_seq = strtol(seq_start, &end, 10);
        if (errno != 0 || end == seq_start || *end != '\0' || recv_seq < 1) {
            fprintf(stderr, "invalid response\n");
            close(sock_fd);
            return EXIT_FAILURE;
        }

        if (recv_seq != sent_seq) {
            fprintf(stderr, "unexpected sequence\n");
            close(sock_fd);
            return EXIT_FAILURE;
        }

        printf("%s\n", buffer);
    }

    close(sock_fd);

    return EXIT_SUCCESS;
}

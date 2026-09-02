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

    if (argc != 5) {
        fprintf(stderr, "usage: ./net_client --host <host> --port <port>\n");
        return EXIT_FAILURE;
    }

    if (strcmp(argv[1], "--host") != 0 || strcmp(argv[3], "--port") != 0) {
        fprintf(stderr, "usage: ./net_client --host <host> --port <port>\n");
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

    ssize_t sent = send(sock_fd, "PING\n", 5, 0);
    if (sent == -1) {
        perror("send");
        close(sock_fd);
        return EXIT_FAILURE;
    }
    if (sent != 5) {
        fprintf(stderr, "send: incomplete write\n");
        close(sock_fd);
        return EXIT_FAILURE;
    }

    char buffer[64];
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

    if (strcmp(buffer, "PONG") != 0) {
        fprintf(stderr, "invalid response\n");
        close(sock_fd);
        return EXIT_FAILURE;
    }

    printf("%s\n", buffer);

    close(sock_fd);

    return EXIT_SUCCESS;
}

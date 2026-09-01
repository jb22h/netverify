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

    sock_fd = socket(AF_INET, SOCK_STREAM, 0);
    if (sock_fd == -1) {
        perror("socket");
        return EXIT_FAILURE;
    }

    memset(&addr, 0, sizeof(addr));
    addr.sin_family = AF_INET;
    addr.sin_port = htons((uint16_t)port);
    if (inet_pton(AF_INET, host, &addr.sin_addr) != 1) {
        fprintf(stderr, "inet_pton: invalid address\n");
        close(sock_fd);
        return EXIT_FAILURE;
    }

    if (connect(sock_fd, (struct sockaddr *)&addr, sizeof(addr)) == -1) {
        perror("connect");
        close(sock_fd);
        return EXIT_FAILURE;
    }

    ssize_t sent = send(sock_fd, "PING", 4, 0);
    if (sent == -1) {
        perror("send");
        close(sock_fd);
        return EXIT_FAILURE;
    }
    if (sent != 4) {
        fprintf(stderr, "send: incomplete write\n");
        close(sock_fd);
        return EXIT_FAILURE;
    }

    char buffer[64];
    ssize_t n = recv(sock_fd, buffer, sizeof(buffer) - 1, 0);
    if (n == -1) {
        perror("recv");
        close(sock_fd);
        return EXIT_FAILURE;
    }
    if (n == 0) {
        fprintf(stderr, "server closed the connection before sending a response\n");
        close(sock_fd);
        return EXIT_FAILURE;
    }

    if (n != 4 || memcmp(buffer, "PONG", 4) != 0) {
        fprintf(stderr, "invalid response\n");
        close(sock_fd);
        return EXIT_FAILURE;
    }

    buffer[n] = '\0';
    printf("%s\n", buffer);

    close(sock_fd);

    return EXIT_SUCCESS;
}

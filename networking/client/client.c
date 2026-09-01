#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>

#include <arpa/inet.h>
#include <netinet/in.h>
#include <sys/socket.h>

int main(void)
{
    int sock_fd;
    struct sockaddr_in addr;

    sock_fd = socket(AF_INET, SOCK_STREAM, 0);
    if (sock_fd == -1) {
        perror("socket");
        return EXIT_FAILURE;
    }

    memset(&addr, 0, sizeof(addr));
    addr.sin_family = AF_INET;
    addr.sin_port = htons(9000);
    if (inet_pton(AF_INET, "127.0.0.1", &addr.sin_addr) != 1) {
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

    buffer[n] = '\0';
    printf("%s\n", buffer);

    close(sock_fd);

    return EXIT_SUCCESS;
}

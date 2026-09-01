#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>

#include <arpa/inet.h>
#include <netinet/in.h>
#include <sys/socket.h>

int main(void)
{
    int server_fd;
    int client_fd;
    struct sockaddr_in addr;
    struct sockaddr_in client_addr;
    socklen_t client_addr_len = sizeof(client_addr);

    server_fd = socket(AF_INET, SOCK_STREAM, 0);
    if (server_fd == -1) {
        perror("socket");
        return EXIT_FAILURE;
    }

    memset(&addr, 0, sizeof(addr));
    addr.sin_family = AF_INET;
    addr.sin_port = htons(9000);
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

    client_fd = accept(server_fd, (struct sockaddr *)&client_addr, &client_addr_len);
    if (client_fd == -1) {
        perror("accept");
        close(server_fd);
        return EXIT_FAILURE;
    }

    char buffer[64];
    ssize_t n = recv(client_fd, buffer, sizeof(buffer) - 1, 0);
    if (n == -1) {
        perror("recv");
        close(client_fd);
        close(server_fd);
        return EXIT_FAILURE;
    }
    if (n == 0) {
        fprintf(stderr, "client closed the connection\n");
    } else {
        if (n != 4 || memcmp(buffer, "PING", 4) != 0) {
            fprintf(stderr, "invalid request\n");
            close(client_fd);
            close(server_fd);
            return EXIT_FAILURE;
        }

        buffer[n] = '\0';
        printf("%s\n", buffer);

        ssize_t sent = send(client_fd, "PONG", 4, 0);
        if (sent == -1) {
            perror("send");
            close(client_fd);
            close(server_fd);
            return EXIT_FAILURE;
        }
        if (sent != 4) {
            fprintf(stderr, "send: incomplete write\n");
            close(client_fd);
            close(server_fd);
            return EXIT_FAILURE;
        }
    }

    close(client_fd);
    close(server_fd);

    return EXIT_SUCCESS;
}

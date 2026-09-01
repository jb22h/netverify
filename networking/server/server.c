#include <stdio.h>
#include <stdlib.h>
#include <unistd.h>

#include <sys/socket.h>

int main(void)
{
    int server_fd;

    server_fd = socket(AF_INET, SOCK_STREAM, 0);
    if (server_fd == -1) {
        perror("socket");
        return EXIT_FAILURE;
    }

    close(server_fd);

    return EXIT_SUCCESS;
}

CC = gcc
CFLAGS = -Wall -Wextra -Wpedantic -std=c11

all: net_server net_client

net_server: networking/server/server.c
	$(CC) $(CFLAGS) -o net_server networking/server/server.c

net_client: networking/client/client.c
	$(CC) $(CFLAGS) -o net_client networking/client/client.c

clean:
	rm -f net_server net_client

.PHONY: all clean

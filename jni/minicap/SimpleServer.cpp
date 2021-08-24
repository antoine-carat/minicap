#include "SimpleServer.hpp"

#include <fcntl.h>
#include <sys/socket.h>
#include <sys/stat.h>
#include <sys/types.h>
#include <sys/un.h>
#include <netinet/in.h>
#include <unistd.h>
#include <string.h>

SimpleServer::SimpleServer(): mFd(0) {
}

SimpleServer::~SimpleServer() {
  if (mFd > 0) {
    ::close(mFd);
  }
}

int
SimpleServer::start(const int portno) {
  int sfd = socket(AF_INET, SOCK_STREAM, 0);


  if (sfd < 0) {
    return sfd;
  }

  struct sockaddr_in addr;
  memset(&addr, 0, sizeof(addr));
  int optval = 1;
  setsockopt(sfd, SOL_SOCKET, SO_REUSEADDR, (const void *)&optval , sizeof(int));

  addr.sin_family = AF_INET;
  addr.sin_addr.s_addr = INADDR_ANY;
  addr.sin_port = htons(portno);

  if (::bind(sfd, (struct sockaddr*) &addr, sizeof(addr)) < 0) {
    ::close(sfd);
    return -1;
  }

  ::listen(sfd, 1);

  mFd = sfd;

  return mFd;
}

int
SimpleServer::accept() {
  struct sockaddr_un addr;
  socklen_t addr_len = sizeof(addr);
  return ::accept(mFd, (struct sockaddr *) &addr, &addr_len);
}

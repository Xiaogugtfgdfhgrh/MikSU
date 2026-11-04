#include <errno.h>
#include <fcntl.h>
#include <stdio.h>
#include <sys/file.h>
#include <sys/wait.h>
#include <unistd.h>

int main(int argc, char **argv)
{
    if (argc != 2)
        return 2;
    int fd = open(argv[1], O_RDWR | O_CLOEXEC | O_NOFOLLOW);
    if (fd < 0) {
        printf("{\"open_errno\":%d}\n", errno);
        return 1;
    }
    int result = flock(fd, LOCK_EX | LOCK_NB);
    int failure = result ? errno : 0;
    int blocked = 0;
    if (!result) {
        pid_t child = fork();
        if (child == 0) {
            close(fd);
            int other = open(argv[1], O_RDWR | O_CLOEXEC | O_NOFOLLOW);
            if (other < 0)
                _exit(2);
            int locked = flock(other, LOCK_EX | LOCK_NB);
            int error = errno;
            close(other);
            _exit(locked && (error == EAGAIN || error == EWOULDBLOCK) ? 0 : 1);
        }
        int status = 0;
        blocked = child > 0 && waitpid(child, &status, 0) == child &&
                  WIFEXITED(status) && WEXITSTATUS(status) == 0;
    }
    printf("{\"flock_result\":%d,\"errno\":%d,\"uid\":%d,\"contender_blocked\":%s}\n",
           result, failure, getuid(), blocked ? "true" : "false");
    close(fd);
    return result || !blocked ? 1 : 0;
}

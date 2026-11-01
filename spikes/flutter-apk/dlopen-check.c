// dlopen-check <lib.so>...: loads each library with RTLD_NOW, as Android's loader would, and says whether it loaded.
#include <dlfcn.h>
#include <stdio.h>
int main(int argc, char **argv) {
    int bad = 0;
    for (int i = 1; i < argc; i++) {
        void *h = dlopen(argv[i], RTLD_NOW | RTLD_LOCAL);
        if (h) printf("%s: loaded\n", argv[i]); else { printf("%s: %s\n", argv[i], dlerror()); bad = 1; }
    }
    return bad;
}

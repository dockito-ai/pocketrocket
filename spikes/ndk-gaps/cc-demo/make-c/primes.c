/* Prints how many primes are below N, with libm in the loop. */
#include <math.h>
#include <stdio.h>
#include <stdlib.h>

int main(int argc, char **argv) {
  long n = argc > 1 ? atol(argv[1]) : 100000, count = 0;
  for (long i = 2; i < n; i++) {
    long r = (long)sqrt((double)i), d = 2;
    while (d <= r && i % d) d++;
    if (d > r) count++;
  }
  printf("%ld primes below %ld\n", count, n);
  return 0;
}

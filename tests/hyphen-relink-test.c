#include <stddef.h>
#include "hyphen.h"

int hnj_hyphen_norm(const char *word, int word_size, char *hyphens, char ***rep, int **pos, int **cut);

int main(void) {
    const char invalid_utf8[] = { (char)0x80, '\0' };
    char hyphens[2] = { 0 };
    return hnj_hyphen_norm(invalid_utf8, 1, hyphens, NULL, NULL, NULL) == 1 ? 0 : 1;
}

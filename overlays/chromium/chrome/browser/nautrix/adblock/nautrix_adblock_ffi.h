#ifndef CHROME_BROWSER_NAUTRIX_ADBLOCK_NAUTRIX_ADBLOCK_FFI_H_
#define CHROME_BROWSER_NAUTRIX_ADBLOCK_NAUTRIX_ADBLOCK_FFI_H_
#include <stdint.h>

#include <stdbool.h>
#ifdef __cplusplus
extern "C" {
#endif
void* nautrix_adblock_create(const char* rules);
void nautrix_adblock_destroy(void* handle);
bool nautrix_adblock_replace_rules(void* handle, const char* rules);
int32_t nautrix_adblock_check_network_request(
    void* handle, const char* url, const char* source_url, const char* resource_type,
    const char* method, char** rewritten_url_out);

bool nautrix_adblock_should_block(void* handle, const char* url, const char* source_url,
                                  const char* resource_type, const char* method);
char* nautrix_adblock_cosmetic_resources(void* handle, const char* url);
void nautrix_adblock_string_free(char* value);
#ifdef __cplusplus
}
#endif
#endif

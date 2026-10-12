#include "rtp_packet.h"
static uint16_t be16(const uint8_t *p) {
    return (uint16_t)(((uint16_t)p[0] << 8) | p[1]);
}
static uint32_t be32(const uint8_t *p) {
    return ((uint32_t)p[0] << 24) | ((uint32_t)p[1] << 16) |
           ((uint32_t)p[2] << 8) | (uint32_t)p[3];
}
int iccoa_rtp_parse(const uint8_t *p, size_t n, iccoa_rtp_packet *out) {
    size_t off, csrc_bytes, payload_size;
    unsigned cc;
    if (!p || !out || n < 12 || (p[0] >> 6) != 2) return -1;
    cc = p[0] & 0x0f;
    csrc_bytes = (size_t)cc * 4;
    if (csrc_bytes > n - 12) return -1;
    off = 12 + csrc_bytes;
    if (p[0] & 0x10) {
        size_t ext_bytes;
        if (n - off < 4) return -1;
        ext_bytes = (size_t)be16(p + off + 2) * 4;
        if (ext_bytes > n - off - 4) return -1;
        off += 4 + ext_bytes;
    }
    if (off > n) return -1;
    payload_size = n - off;
    if (p[0] & 0x20) {
        uint8_t pad;
        if (!payload_size) return -1;
        pad = p[n - 1];
        if (!pad || (size_t)pad > payload_size) return -1;
        payload_size -= pad;
    }
    out->payload_type = p[1] & 0x7f;
    out->marker = (uint8_t)(p[1] >> 7);
    out->sequence = be16(p + 2);
    out->timestamp = be32(p + 4);
    out->ssrc = be32(p + 8);
    out->payload = p + off;
    out->payload_size = payload_size;
    out->header_size = off;
    return 0;
}

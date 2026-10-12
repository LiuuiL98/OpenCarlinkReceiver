#ifndef ICCOA_RTP_PACKET_H
#define ICCOA_RTP_PACKET_H
#include <stddef.h>
#include <stdint.h>

typedef struct {
    uint8_t payload_type;
    uint8_t marker;
    uint16_t sequence;
    uint32_t timestamp;
    uint32_t ssrc;
    const uint8_t *payload;
    size_t payload_size;
    size_t header_size;
} iccoa_rtp_packet;

/* Parse one complete RTP v2 packet, including CSRC list, extension and padding.
 * The returned payload points into the caller-owned input buffer.
 * Returns 0 on success, -1 for malformed/unsupported input. */
int iccoa_rtp_parse(const uint8_t *packet, size_t packet_size,
                    iccoa_rtp_packet *out);
#endif

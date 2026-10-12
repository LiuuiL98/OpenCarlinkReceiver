#include "rtp_packet.h"
#include <assert.h>
#include <stdio.h>

static void test_basic(void) {
    const uint8_t p[] = {0x80,0xE0,0x12,0x34,1,2,3,4,0xA0,0xB0,0xC0,0xD0,0x65,0xAA,0xBB};
    iccoa_rtp_packet r;
    assert(iccoa_rtp_parse(p,sizeof(p),&r)==0);
    assert(r.payload_type==96 && r.marker==1 && r.sequence==0x1234);
    assert(r.timestamp==0x01020304 && r.ssrc==0xA0B0C0D0);
    assert(r.header_size==12 && r.payload_size==3 && r.payload[0]==0x65);
}
static void test_csrc_extension_padding(void) {
    const uint8_t p[] = {0xB1,0x60,0,9,0,0,0,7,0,0,0,8,1,2,3,4,
                         0xBE,0xDE,0,1,0x10,0x20,0x30,0x40,0x61,0xAA,0xBB,0xCC,2,2};
    iccoa_rtp_packet r;
    assert(iccoa_rtp_parse(p,sizeof(p),&r)==0);
    assert(r.header_size==24 && r.payload_size==4);
    assert(r.payload[0]==0x61 && r.payload[3]==0xCC);
}
static void test_bad(void) {
    iccoa_rtp_packet r;
    const uint8_t shortp[11]={0};
    const uint8_t wrong_version[12]={0x40};
    const uint8_t bad_ext[16]={0x90,0x60,0,0,0,0,0,0,0,0,0,0,0,0,0,2};
    const uint8_t bad_pad[]={0xA0,0x60,0,0,0,0,0,0,0,0,0,0,0x61,0};
    assert(iccoa_rtp_parse(shortp,sizeof(shortp),&r)==-1);
    assert(iccoa_rtp_parse(wrong_version,sizeof(wrong_version),&r)==-1);
    assert(iccoa_rtp_parse(bad_ext,sizeof(bad_ext),&r)==-1);
    assert(iccoa_rtp_parse(bad_pad,sizeof(bad_pad),&r)==-1);
}
int main(void) {
    test_basic(); test_csrc_extension_padding(); test_bad();
    puts("RTP packet parser tests: PASS (3 groups)");
    return 0;
}

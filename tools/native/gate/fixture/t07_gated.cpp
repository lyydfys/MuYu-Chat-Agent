// T07 gate fixture - capability-gated TU (models the T01 MACRO_OFF class under
// the T07 case set as well, so the two fixtures cannot silently disagree).

#if MCA_T07_FEATURE
extern "C" void Java_com_muyuchat_t07_Gate_nativeFeature(void*, void*, int) {}
#endif

extern "C" int t07_gated_marker(void) {
#if MCA_T07_FEATURE
    return 1;
#else
    return 0;
#endif
}

#ifndef MCA_MNN_UNET_TIMESTEP_HPP
#define MCA_MNN_UNET_TIMESTEP_HPP

namespace mca {
namespace mnn {

// The Euler "trailing" timetable intentionally ends on the Local Dream sentinel
// -1: diffusion_scheduler.cpp:288-297 subtracts one ratio from every published
// timestep and diffusion_scheduler.cpp:309-313 accepts -1 for trailing spacing so
// the final step selects the terminal sigma entry. The sentinel is internal
// scheduling state.
//
// The UNet graph, however, only accepts a real diffusion timestep
// (mnn_native_engine.cpp UNetSession::run rejects anything outside [0, 1000)),
// and the correct input for the last Euler step is t = 0. Publishing the raw
// sentinel therefore aborted a fully executed run at its final step
// ("UNet timestep must be finite and in [0, 999]."). Clamp only at the graph
// boundary; the scheduler keeps its sentinel for sigma lookups.
inline double unet_input_timestep(double scheduled_timestep) {
    return scheduled_timestep < 0.0 ? 0.0 : scheduled_timestep;
}

} // namespace mnn
} // namespace mca

#endif // MCA_MNN_UNET_TIMESTEP_HPP
